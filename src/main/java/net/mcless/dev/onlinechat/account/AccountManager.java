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
        try {
            if (!Files.exists(storageFile)) {
                Files.createDirectories(storageFile.getParent());
                save();
                return;
            }
            Account[] list = GSON.fromJson(Files.readString(storageFile, StandardCharsets.UTF_8), Account[].class);
            byUsername.clear();
            usernameByPlayerUuid.clear();
            if (list != null) {
                for (Account a : list) {
                    byUsername.put(a.getUsername().toLowerCase(Locale.ROOT), a);
                    if (a.getBoundPlayerUuid() != null) {
                        usernameByPlayerUuid.put(a.getBoundPlayerUuid(), a.getUsername());
                    }
                }
            }
            OnlineChat.LOGGER.info("[OnlineChat] Loaded {} web account(s) from {}", byUsername.size(), storageFile);
        } catch (Exception e) {
            OnlineChat.LOGGER.error("[OnlineChat] Failed to load accounts from {}", storageFile, e);
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
                try {
                    Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFail) {
                    Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                OnlineChat.LOGGER.error("[OnlineChat] Failed to save accounts to {}", storageFile, e);
            }
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
        save();
    }

    public void touchLogin(Account account) {
        account.setLastLoginAt(System.currentTimeMillis());
        save();
    }

    public Path getStorageFile() {
        return storageFile;
    }
}
