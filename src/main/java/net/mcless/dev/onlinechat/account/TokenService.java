package net.mcless.dev.onlinechat.account;

import net.mcless.dev.onlinechat.OnlineChat;
import net.mcless.dev.onlinechat.config.ServerConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Stateless HMAC-SHA256 signed tokens: {@code base64url(username).base64url(expiryMillis).base64url(signature)}.
 * The shared secret is either provided by config or auto-generated once and stored next to the accounts file.
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
        long expiry = System.currentTimeMillis() + ServerConfig.TOKEN_TTL_MINUTES.get() * 60_000L;
        String payload = b64(username.getBytes(StandardCharsets.UTF_8)) + "." + b64(Long.toString(expiry).getBytes(StandardCharsets.UTF_8));
        String sig = b64(sign(payload.getBytes(StandardCharsets.UTF_8)));
        return payload + "." + sig;
    }

    public Optional<String> validate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String[] parts = token.split("\\.");
        if (parts.length != 3) return Optional.empty();
        String payload = parts[0] + "." + parts[1];
        byte[] expectedSig = sign(payload.getBytes(StandardCharsets.UTF_8));
        byte[] actualSig;
        try {
            actualSig = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException bad) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expectedSig, actualSig)) return Optional.empty();
        try {
            String username = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            long expiry = Long.parseLong(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            if (expiry < System.currentTimeMillis()) return Optional.empty();
            if (accounts.byUsername(username).isEmpty()) return Optional.empty();
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
}
