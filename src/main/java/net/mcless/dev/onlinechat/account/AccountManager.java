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
 * Deleted accounts are never removed from storage: they are flagged (see {@link Account#markDeleted})
 * and excluded from every active-account lookup, keeping the record for later audit.
 */
public class AccountManager {
    public static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,32}$");
    /** How many login-history entries are kept per account. */
    public static final int MAX_LOGIN_HISTORY = 50;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path storageFile;
    /** Every record ever stored, including deleted ones (insertion order). */
    private final List<Account> records = new ArrayList<>();
    /** Active accounts only, keyed by lowercase username. */
    private final ConcurrentMap<String, Account> byUsername = new ConcurrentHashMap<>();
    /** Active bindings only: player UUID -> username. */
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

    /** Attempts to parse {@code file} into the in-memory records and index maps. Returns false on any failure or empty parse. */
    private boolean tryLoad(Path file) {
        try {
            Account[] list = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Account[].class);
            if (list == null) return false;
            records.clear();
            byUsername.clear();
            usernameByPlayerUuid.clear();
            int active = 0, deleted = 0;
            for (Account a : list) {
                if (a == null || a.getUsername() == null) continue;
                records.add(a);
                if (a.isDeleted()) {
                    deleted++;
                    continue;   // archived record: kept for audit, invisible to active lookups
                }
                active++;
                byUsername.put(a.getUsername().toLowerCase(Locale.ROOT), a);
                if (a.getBoundPlayerUuid() != null) {
                    usernameByPlayerUuid.put(a.getBoundPlayerUuid(), a.getUsername());
                }
            }
            OnlineChat.LOGGER.info("[OnlineChat] Loaded {} web account(s) from {} ({} active, {} archived)", active + deleted, file, active, deleted);
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
                List<Account> all = new ArrayList<>(records);
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
        records.add(account);
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

    /**
     * Archives the account instead of removing it: the player binding is severed (UUID and
     * username are unlinked), credentials are scrubbed, and the record — including its login
     * history — stays on disk for later audit. The username becomes available for re-registration.
     * Returns true if the account existed and was active.
     */
    public boolean delete(Account account) {
        if (account == null || account.getUsername() == null || account.isDeleted()) return false;
        String normalized = account.getUsername().toLowerCase(Locale.ROOT);
        Account removed = byUsername.remove(normalized);
        if (removed == null) return false;
        UUID uuid = removed.getBoundPlayerUuid();
        if (uuid != null) usernameByPlayerUuid.remove(uuid, removed.getUsername());
        removed.markDeleted(System.currentTimeMillis());
        save();
        return true;
    }

    /** Records a successful login (IP + time) on the account. */
    public void recordLogin(Account account, String ip) {
        account.recordLogin(ip, System.currentTimeMillis(), MAX_LOGIN_HISTORY);
        save();
    }

    /** Sets the real-name verification flag (statistics only — never enforced). */
    public void recordRealName(Account account, boolean verified) {
        account.setRealNameVerified(verified, System.currentTimeMillis());
        save();
    }

    /**
     * Looks a record up by username INCLUDING archived (deleted) accounts — used by admin audit
     * commands. The most recently created record wins when the name has been re-registered.
     */
    public Optional<Account> recordByUsername(String username) {
        if (username == null) return Optional.empty();
        String needle = username.toLowerCase(Locale.ROOT);
        for (int i = records.size() - 1; i >= 0; i--) {
            Account a = records.get(i);
            if (a != null && needle.equals(a.getUsername().toLowerCase(Locale.ROOT))) return Optional.of(a);
        }
        return Optional.empty();
    }

    /** Verified / active counts for the real-name statistics. */
    public long[] realNameStats() {
        long total = 0, verified = 0;
        for (Account a : records) {
            if (a == null || a.isDeleted()) continue;
            total++;
            if (a.isRealNameVerified()) verified++;
        }
        return new long[] { verified, total };
    }

    public void touchLogin(Account account) {
        account.setLastLoginAt(System.currentTimeMillis());
        save();
    }

    public Path getStorageFile() {
        return storageFile;
    }
}
