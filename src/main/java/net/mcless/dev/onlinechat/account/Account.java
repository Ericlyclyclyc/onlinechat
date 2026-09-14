package net.mcless.dev.onlinechat.account;

import com.google.gson.annotations.SerializedName;

import java.util.UUID;

/**
 * Persistent web account record.
 * One account may be bound to at most one Minecraft player (identified by UUID).
 */
public class Account {
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

    public Account() {}

    public Account(String username, String passwordHash, String passwordSalt, int iterations) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.passwordSalt = passwordSalt;
        this.pbkdf2Iterations = iterations;
        this.createdAt = System.currentTimeMillis();
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

    public boolean isBound() { return boundPlayerUuid != null; }
}
