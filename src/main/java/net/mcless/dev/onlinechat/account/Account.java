package net.mcless.dev.onlinechat.account;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent web account record.
 * One account may be bound to at most one Minecraft player (identified by UUID).
 * A deleted account is never removed from storage: it is flagged with {@code deletedAt}
 * and its player binding is severed, keeping the record (including the login history)
 * for later audit.
 */
public class Account {
    /** One entry of the login history: the client IP and the login timestamp. */
    public static class LoginEntry {
        @SerializedName("ip")
        private String ip;

        @SerializedName("ts")
        private long ts;

        public LoginEntry() {}

        public LoginEntry(String ip, long ts) {
            this.ip = ip;
            this.ts = ts;
        }

        public String getIp() { return ip; }
        public long getTs() { return ts; }
    }

    @SerializedName("username")
    private String username;

    @SerializedName("passwordHash")
    private String passwordHash;

    @SerializedName("passwordSalt")
    private String passwordSalt;

    @SerializedName("pbkdf2Iterations")
    private int pbkdf2Iterations;

    @SerializedName("boundPlayerUuid")
    private UUID boundPlayerUuid;

    @SerializedName("boundPlayerName")
    private String boundPlayerName;

    @SerializedName("createdAt")
    private long createdAt;

    @SerializedName("lastLoginAt")
    private long lastLoginAt;

    @SerializedName("lastLoginIp")
    private String lastLoginIp;

    /** Login history (ip + timestamp), newest first, capped (see {@code MAX_LOGIN_HISTORY}). */
    @SerializedName("loginHistory")
    private List<LoginEntry> loginHistory;

    /** Player opted in to the in-game two-factor check. Only meaningful while the account is bound. */
    @SerializedName("twoFactorEnabled")
    private boolean twoFactorEnabled;

    /** Timestamp when the account was deleted (0 = active). Deleted records stay on disk for audit. */
    @SerializedName("deletedAt")
    private long deletedAt;

    /** Real-name verification status — statistics only, never enforced by this mod. */
    @SerializedName("realNameVerified")
    private boolean realNameVerified;

    @SerializedName("realNameVerifiedAt")
    private long realNameVerifiedAt;

    public Account() {}

    public Account(String username, String passwordHash, String passwordSalt, int iterations) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.passwordSalt = passwordSalt;
        this.pbkdf2Iterations = iterations;
        this.createdAt = System.currentTimeMillis();
        this.loginHistory = new ArrayList<>();
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getPasswordSalt() { return passwordSalt; }
    public void setPasswordSalt(String passwordSalt) { this.passwordSalt = passwordSalt; }

    public int getPbkdf2Iterations() { return pbkdf2Iterations; }
    public void setPbkdf2Iterations(int iterations) { this.pbkdf2Iterations = iterations; }

    public UUID getBoundPlayerUuid() { return boundPlayerUuid; }
    public void setBoundPlayerUuid(UUID boundPlayerUuid) { this.boundPlayerUuid = boundPlayerUuid; }

    public String getBoundPlayerName() { return boundPlayerName; }
    public void setBoundPlayerName(String boundPlayerName) { this.boundPlayerName = boundPlayerName; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(long lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public String getLastLoginIp() { return lastLoginIp; }

    public List<LoginEntry> getLoginHistory() {
        return loginHistory == null ? List.of() : loginHistory;
    }

    public boolean isTwoFactorEnabled() { return twoFactorEnabled; }
    public void setTwoFactorEnabled(boolean twoFactorEnabled) { this.twoFactorEnabled = twoFactorEnabled; }

    public long getDeletedAt() { return deletedAt; }
    public boolean isDeleted() { return deletedAt != 0; }

    public boolean isRealNameVerified() { return realNameVerified; }
    public void setRealNameVerified(boolean verified, long at) {
        this.realNameVerified = verified;
        this.realNameVerifiedAt = verified ? at : 0;
    }
    public long getRealNameVerifiedAt() { return realNameVerifiedAt; }

    public boolean isBound() { return boundPlayerUuid != null; }

    /**
     * Records a successful login: updates {@code lastLoginAt}/{@code lastLoginIp} and prepends an
     * entry to the (capped) login history. {@code ip} is the already-sanitised client IP.
     */
    public void recordLogin(String ip, long ts, int cap) {
        this.lastLoginAt = ts;
        this.lastLoginIp = ip;
        if (this.loginHistory == null) this.loginHistory = new ArrayList<>();
        this.loginHistory.add(0, new LoginEntry(ip, ts));
        while (this.loginHistory.size() > cap) this.loginHistory.remove(this.loginHistory.size() - 1);
    }

    /**
     * Marks the account as deleted WITHOUT removing the record: the player binding is severed,
     * 2FA is turned off and the credentials are scrubbed. The username, timestamps and login
     * history stay on disk for later audit.
     */
    public void markDeleted(long at) {
        this.deletedAt = at;
        this.boundPlayerUuid = null;
        this.boundPlayerName = null;
        this.twoFactorEnabled = false;
        this.passwordHash = null;
        this.passwordSalt = null;
        this.pbkdf2Iterations = 0;
    }
}
