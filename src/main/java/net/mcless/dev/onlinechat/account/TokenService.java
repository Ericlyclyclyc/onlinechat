package net.mcless.dev.onlinechat.account;

import net.mcless.dev.onlinechat.OnlineChat;
import net.mcless.dev.onlinechat.config.ServerConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * Stateless HMAC-SHA256 signed tokens:
 * {@code base64url(username).base64url(issuedAtMillis).base64url(expiryMillis).base64url(signature)}.
 * Signing in again bumps the account's {@code lastLoginAt}; any token issued before that moment is
 * treated as superseded and rejected, which enforces a single active web session per account.
 * The shared secret is either provided by config or auto-generated once and stored next to the accounts file.
 * <p>
 * Tokens minted by versions before the {@code issuedAt} segment existed ({@code username.expiry.signature})
 * are still accepted until they expire, so upgrading does not log every web user out; their issue time is
 * derived from the expiry and the configured TTL.
 */
public class TokenService {
    private static final String HMAC = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountManager accounts;
    private final Path secretFile;
    private volatile byte[] secret;

    public TokenService(AccountManager accounts, Path secretFile) {
        this.accounts = accounts;
        this.secretFile = secretFile;
    }

    public void init() {
        String configured = ServerConfig.TOKEN_SECRET.get();
        if (configured != null && !configured.isBlank()) {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
            return;
        }
        try {
            if (Files.exists(secretFile)) {
                this.secret = Files.readString(secretFile, StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8);
                return;
            }
            byte[] rnd = new byte[48];
            RANDOM.nextBytes(rnd);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
            if (secretFile.getParent() != null) Files.createDirectories(secretFile.getParent());
            Files.writeString(secretFile, encoded, StandardCharsets.UTF_8);
            restrictPermissions(secretFile);
            this.secret = encoded.getBytes(StandardCharsets.UTF_8);
            OnlineChat.LOGGER.info("[OnlineChat] Generated a new HMAC token secret at {}", secretFile);
        } catch (Exception e) {
            OnlineChat.LOGGER.error("[OnlineChat] Unable to initialise token secret; falling back to ephemeral secret", e);
            byte[] rnd = new byte[48];
            RANDOM.nextBytes(rnd);
            this.secret = rnd;
        }
    }

    public String issue(String username) {
        long issuedAt = System.currentTimeMillis();
        long expiry = issuedAt + ServerConfig.TOKEN_TTL_MINUTES.get() * 60_000L;
        String payload = b64(username.getBytes(StandardCharsets.UTF_8)) + "."
                + b64(Long.toString(issuedAt).getBytes(StandardCharsets.UTF_8)) + "."
                + b64(Long.toString(expiry).getBytes(StandardCharsets.UTF_8));
        String sig = b64(sign(payload.getBytes(StandardCharsets.UTF_8)));
        return payload + "." + sig;
    }

    public Optional<String> validate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String[] parts = token.split("\\.");
        boolean legacy = parts.length == 3;
        if (!legacy && parts.length != 4) return Optional.empty();
        int sigIndex = parts.length - 1;
        String payload = String.join(".", Arrays.copyOf(parts, sigIndex));
        byte[] expectedSig = sign(payload.getBytes(StandardCharsets.UTF_8));
        byte[] actualSig;
        try {
            actualSig = Base64.getUrlDecoder().decode(parts[sigIndex]);
        } catch (IllegalArgumentException bad) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expectedSig, actualSig)) return Optional.empty();
        try {
            String username = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            long expiry = Long.parseLong(new String(Base64.getUrlDecoder().decode(parts[sigIndex - 1]), StandardCharsets.UTF_8));
            long issuedAt = legacy
                    ? expiry - ServerConfig.TOKEN_TTL_MINUTES.get() * 60_000L
                    : Long.parseLong(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            if (expiry < System.currentTimeMillis()) return Optional.empty();
            Account acc = accounts.byUsername(username).orElse(null);
            if (acc == null) return Optional.empty();
            // Single active web session: logging in again bumps lastLoginAt, which supersedes every
            // token issued before it - so signing in on another device invalidates this one.
            if (issuedAt < acc.getLastLoginAt()) return Optional.empty();
            return Optional.of(username);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] sign(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign token", e);
        }
    }

    private static String b64(byte[] in) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(in);
    }

    /**
     * Best-effort restriction of the secret file to owner read/write only (POSIX {@code 0600}).
     * A silent no-op on filesystems without POSIX permission support (e.g. Windows NTFS).
     */
    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // Non-POSIX filesystem; nothing more we can do portably.
        }
    }
}
