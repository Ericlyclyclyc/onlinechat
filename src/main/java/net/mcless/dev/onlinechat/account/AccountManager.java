package net.mcless.dev.onlinechat.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.mcless.dev.onlinechat.OnlineChat;
import net.mcless.dev.onlinechat.config.ServerConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * JSON-backed account storage. Kept in memory, flushed to disk after every mutation.
 */
public class AccountManager {
    public static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,32}$");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path storageFile;
    private final ConcurrentMap<String, Account> byUsername = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> usernameByPlayerUuid = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public AccountManager(Path storageFile) {
        this.storageFile = storageFile;
    }

    public void load() {
        if (Files.exists(storageFile)) {
            if (tryLoad(storageFile)) {
                restrictPermissions(storageFile);
                return;
            }
            OnlineChat.LOGGER.error("[OnlineChat] accounts file {} is corrupt or unreadable; trying the .bak backup", storageFile);
            Path bak = storageFile.resolveSibling(storageFile.getFileName() + ".bak");
            if (Files.exists(bak) && tryLoad(bak)) {
                OnlineChat.LOGGER.info("[OnlineChat] Recovered {} account(s) from backup {}; rewriting the primary file", byUsername.size(), bak);
                save();
                return;
            }
            // Both primary and backup are unusable. Leave them on disk untouched so an operator can
            // inspect/recover manually, and continue with an empty in-memory set. Deliberately NOT calling
            // save() here, which would overwrite the (possibly recoverable) corrupt file with an empty list.
            OnlineChat.LOGGER.error("[OnlineChat] Backup recovery failed; starting with an empty account set. " +
                    "The corrupt files were left untouched for manual recovery.");
            return;
        }
        try {
            Files.createDirectories(storageFile.getParent());
            save();
        } catch (Exception e) {
            OnlineChat.LOGGER.error("[OnlineChat] Failed to create initial accounts file {}", storageFile, e);
        }
    }

    /** Attempts to parse {@code file} into the in-memory maps. Returns false on any failure or empty parse. */
    private boolean tryLoad(Path file) {
        try {
            Account[] list = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Account[].class);
            if (list == null) return false;
            byUsername.clear();
            usernameByPlayerUuid.clear();
            for (Account a : list) {
                if (a == null || a.getUsername() == null) continue;
                byUsername.put(a.getUsername().toLowerCase(Locale.ROOT), a);
                if (a.getBoundPlayerUuid() != null) {
                    usernameByPlayerUuid.put(a.getBoundPlayerUuid(), a.getUsername());
                }
            }
            OnlineChat.LOGGER.info("[OnlineChat] Loaded {} web account(s) from {}", byUsername.size(), file);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void save() {
        synchronized (writeLock) {
            try {
                if (storageFile.getParent() != null) {
                    Files.createDirectories(storageFile.getParent());
                }
                List<Account> all = new ArrayList<>(byUsername.values());
                Path tmp = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
                Files.writeString(tmp, GSON.toJson(all), StandardCharsets.UTF_8);
                restrictPermissions(tmp);
                // Keep a one-generation backup of the previous good state before overwriting it.
                if (Files.exists(storageFile)) {
                    try {
                        Path bak = storageFile.resolveSibling(storageFile.getFileName() + ".bak");
                        Files.copy(storageFile, bak, StandardCopyOption.REPLACE_EXISTING);
                        restrictPermissions(bak);
                    } catch (IOException backupFail) {
                        OnlineChat.LOGGER.warn("[OnlineChat] Unable to write the accounts .bak backup", backupFail);
                    }
                }
                try {
                    Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFail) {
                    Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING);
                }
                restrictPermissions(storageFile);
            } catch (Exception e) {
                OnlineChat.LOGGER.error("[OnlineChat] Failed to save accounts to {}", storageFile, e);
            }
        }
    }

    /**
     * Best-effort restriction of a file to owner read/write only (POSIX {@code 0600}). On filesystems
     * without POSIX permission support (e.g. Windows NTFS) this is a silent no-op.
     */
    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem; nothing more we can do portably.
        }
    }

    public Optional<Account> byUsername(String username) {
        if (username == null) return Optional.empty();
        return Optional.ofNullable(byUsername.get(username.toLowerCase(Locale.ROOT)));
    }

    public Optional<Account> byPlayerUuid(UUID uuid) {
        if (uuid == null) return Optional.empty();
        String name = usernameByPlayerUuid.get(uuid);
        return name == null ? Optional.empty() : byUsername(name);
    }

    public Collection<Account> all() {
        return byUsername.values();
    }

    public boolean exists(String username) {
        return byUsername.containsKey(username.toLowerCase(Locale.ROOT));
    }

    public Account register(String username, String rawPassword) {
        String normalized = username.toLowerCase(Locale.ROOT);
        if (byUsername.containsKey(normalized)) {
            throw new IllegalArgumentException("Username already exists");
        }
        String salt = PasswordHasher.newSalt();
        int iterations = ServerConfig.PBKDF2_ITERATIONS.get();
        String hash = PasswordHasher.hash(rawPassword, salt, iterations);
        Account account = new Account(username, hash, salt, iterations);
        byUsername.put(normalized, account);
        save();
        return account;
    }

    public void bind(Account account, UUID playerUuid, String playerName) {
        // Remove any previous binding for the same player UUID.
        String previous = usernameByPlayerUuid.get(playerUuid);
        if (previous != null && !previous.equalsIgnoreCase(account.getUsername())) {
            Account other = byUsername.get(previous.toLowerCase(Locale.ROOT));
            if (other != null) {
                other.setBoundPlayerUuid(null);
                other.setBoundPlayerName(null);
                other.setTwoFactorEnabled(false);
            }
        }
        // Remove previous binding for this account, if any.
        if (account.getBoundPlayerUuid() != null && !account.getBoundPlayerUuid().equals(playerUuid)) {
            usernameByPlayerUuid.remove(account.getBoundPlayerUuid());
        }
        account.setBoundPlayerUuid(playerUuid);
        account.setBoundPlayerName(playerName);
        usernameByPlayerUuid.put(playerUuid, account.getUsername());
        save();
    }

    public void unbind(Account account) {
        if (account.getBoundPlayerUuid() != null) {
            usernameByPlayerUuid.remove(account.getBoundPlayerUuid());
        }
        account.setBoundPlayerUuid(null);
        account.setBoundPlayerName(null);
        // 2FA protects a bound player; without a binding there is nothing left to protect.
        account.setTwoFactorEnabled(false);
        save();
    }

    /** Replaces the password with a freshly salted hash. The caller is responsible for revoking sessions. */
    public void setPassword(Account account, String rawPassword) {
        String salt = PasswordHasher.newSalt();
        int iterations = ServerConfig.PBKDF2_ITERATIONS.get();
        account.setPasswordSalt(salt);
        account.setPbkdf2Iterations(iterations);
        account.setPasswordHash(PasswordHasher.hash(rawPassword, salt, iterations));
        // Bumping lastLoginAt supersedes every token issued before this instant (see TokenService).
        account.setLastLoginAt(System.currentTimeMillis());
        save();
    }

    public void setTwoFactor(Account account, boolean enabled) {
        account.setTwoFactorEnabled(enabled && account.isBound());
        save();
    }

    /** Removes the account and its player binding. Returns true if it existed. */
    public boolean delete(Account account) {
        if (account == null || account.getUsername() == null) return false;
        String normalized = account.getUsername().toLowerCase(Locale.ROOT);
        Account removed = byUsername.remove(normalized);
        if (removed == null) return false;
        UUID uuid = removed.getBoundPlayerUuid();
        if (uuid != null) usernameByPlayerUuid.remove(uuid, removed.getUsername());
        save();
        return true;
    }

    public void touchLogin(Account account) {
        account.setLastLoginAt(System.currentTimeMillis());
        save();
    }

    public Path getStorageFile() {
        return storageFile;
    }
}
