package net.mcless.dev.onlinechat.web;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import net.mcless.dev.onlinechat.OnlineChat;
import net.mcless.dev.onlinechat.config.ServerConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds a Netty {@link SslContext} from the PEM files configured in {@link ServerConfig}.
 * Relative paths resolve against the Minecraft run directory (working directory).
 */
public final class SslContexts {
    private SslContexts() {}

    /**
     * Defaults of {@code certChainPath} / {@code privateKeyPath} in versions before {@code certDir} existed.
     * A config carried over from such a version still contains these literals; they are treated as "not set"
     * so the new {@code certDir + fileName} keys take effect instead of silently overriding them.
     */
    private static final String LEGACY_CERT_CHAIN_PATH = "./ssl/fullchain.pem";
    private static final String LEGACY_PRIVATE_KEY_PATH = "./ssl/privkey.pem";
    private static volatile boolean legacyPathsLogged;

    public static SslContext buildServerContext(Path runDirectory) throws Exception {
        File cert = resolveCert(runDirectory);
        File key = resolveKey(runDirectory);
        if (!cert.isFile()) throw new IllegalStateException("TLS certificate not found: " + cert.getAbsolutePath());
        if (!key.isFile()) throw new IllegalStateException("TLS private key not found: " + key.getAbsolutePath());

        String pass = ServerConfig.PRIVATE_KEY_PASSWORD.get();
        SslContextBuilder builder = (pass == null || pass.isEmpty())
                ? SslContextBuilder.forServer(cert, key)
                : SslContextBuilder.forServer(cert, key, pass);
        builder.sslProvider(pickProvider());
        if (ServerConfig.REQUIRE_CLIENT_AUTH.get()) {
            builder.clientAuth(io.netty.handler.ssl.ClientAuth.REQUIRE);
        }
        OnlineChat.LOGGER.info("[OnlineChat] TLS material loaded: cert={}, key={}", cert.getAbsolutePath(), key.getAbsolutePath());
        return builder.build();
    }

    private static SslProvider pickProvider() {
        try {
            if (SslProvider.isAlpnSupported(SslProvider.OPENSSL)) return SslProvider.OPENSSL;
        } catch (Throwable ignored) {}
        return SslProvider.JDK;
    }

    private static File resolve(Path runDirectory, String configured) {
        File f = new File(configured);
        if (f.isAbsolute()) return f;
        return runDirectory.resolve(configured).normalize().toFile();
    }

    /**
     * Resolves the certificate file: an explicit {@code certChainPath} wins; otherwise it is
     * {@code <certDir>/<certFileName>}. Both the directory and an explicit relative path resolve
     * against the Minecraft run directory.
     */
    private static File resolveCert(Path runDirectory) {
        String explicit = explicitCertChainPath();
        if (explicit != null) return resolve(runDirectory, explicit);
        return resolveIn(runDirectory, ServerConfig.CERT_DIR.get(), ServerConfig.CERT_FILE_NAME.get());
    }

    /** Resolves the private key file: an explicit {@code privateKeyPath} wins; otherwise {@code <certDir>/<keyFileName>}. */
    private static File resolveKey(Path runDirectory) {
        String explicit = explicitPrivateKeyPath();
        if (explicit != null) return resolve(runDirectory, explicit);
        return resolveIn(runDirectory, ServerConfig.CERT_DIR.get(), ServerConfig.KEY_FILE_NAME.get());
    }

    /** Trimmed {@code certChainPath}, or {@code null} when blank or equal to the pre-{@code certDir} default. */
    private static String explicitCertChainPath() {
        return explicitOrNull(ServerConfig.CERT_CHAIN_PATH.get(), LEGACY_CERT_CHAIN_PATH, "certChainPath");
    }

    /** Trimmed {@code privateKeyPath}, or {@code null} when blank or equal to the pre-{@code certDir} default. */
    private static String explicitPrivateKeyPath() {
        return explicitOrNull(ServerConfig.PRIVATE_KEY_PATH.get(), LEGACY_PRIVATE_KEY_PATH, "privateKeyPath");
    }

    private static String explicitOrNull(String configured, String legacyDefault, String key) {
        if (configured == null || configured.isBlank()) return null;
        String value = configured.trim();
        if (value.replace('\\', '/').equals(legacyDefault)) {
            if (!legacyPathsLogged) {
                legacyPathsLogged = true;
                OnlineChat.LOGGER.info("[OnlineChat] [tls] {} still holds the old default '{}' from a previous version; "
                        + "treating it as unset so certDir/certFileName/keyFileName apply. Clear it in onlinechat-server.toml to silence this.",
                        key, value);
            }
            return null;
        }
        return value;
    }

    /** Joins a (relative-or-absolute) directory with a file name, resolving relative dirs against the run directory. */
    private static File resolveIn(Path runDirectory, String dir, String fileName) {
        String f = (fileName == null || fileName.isBlank()) ? "" : fileName.trim();
        return dirBase(runDirectory, dir).resolve(f).normalize().toFile();
    }

    /** Resolves a (relative-or-absolute) directory against the run directory. */
    private static Path dirBase(Path runDirectory, String dir) {
        String d = (dir == null || dir.isBlank()) ? "." : dir.trim();
        File dirFile = new File(d);
        return dirFile.isAbsolute() ? dirFile.toPath() : runDirectory.resolve(d);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * The directory the PEM files are loaded from in {@code certDir + fileName} mode.
     * Returns {@code null} when both explicit overrides ({@code certChainPath} and
     * {@code privateKeyPath}) are set, since no single directory applies then.
     */
    public static File resolveCertDir(Path runDirectory) {
        if (explicitCertChainPath() != null && explicitPrivateKeyPath() != null) {
            return null;
        }
        return dirBase(runDirectory, ServerConfig.CERT_DIR.get()).normalize().toFile();
    }

    /**
     * First-run helper. When the certificate or key is missing it (a) creates the configured
     * certificate directory — but only for a relative {@code certDir} such as the default
     * {@code ./ssl}, so we never scatter folders across absolute system paths an admin may have
     * mistyped — and (b) logs an actionable "configure SSL" guide instead of letting
     * {@link #buildServerContext} fail later with a bare stack trace.
     *
     * @return {@code true} when both the certificate and the private key exist and the HTTPS
     *         listener should be attempted; {@code false} when the material is missing (guide logged).
     */
    public static boolean prepareAndWarnIfMissing(Path runDirectory) {
        File cert = resolveCert(runDirectory);
        File key = resolveKey(runDirectory);
        if (cert.isFile() && key.isFile()) {
            // Both files exist - but a key in a legacy/traditional PEM format (SEC1 "EC PRIVATE KEY",
            // PKCS#1 "RSA PRIVATE KEY", OpenSSH, ...) or an encrypted PKCS#8 key without a configured
            // password cannot be read by Netty's JDK provider and would fail with a cryptic
            // "algid parse error, not a sequence". Detect it here and log an actionable fix instead.
            String problem = inspectKeyFormat(key);
            if (problem != null) {
                logKeyFormatGuide(key, problem);
                return false;
            }
            return true;
        }

        File dir = resolveCertDir(runDirectory);
        boolean created = false;
        String dirCfg = ServerConfig.CERT_DIR.get();
        boolean dirIsRelative = !notBlank(dirCfg) || !new File(dirCfg.trim()).isAbsolute();
        if (dir != null && dirIsRelative && !dir.isDirectory()) {
            try {
                Files.createDirectories(dir.toPath());
                created = dir.isDirectory();
                if (created) {
                    OnlineChat.LOGGER.info("[OnlineChat] Created TLS certificate directory: {}", dir.getAbsolutePath());
                }
            } catch (IOException | RuntimeException e) {
                OnlineChat.LOGGER.warn("[OnlineChat] Could not create TLS certificate directory {}: {}",
                        dir.getAbsolutePath(), e.toString());
            }
        }
        logSetupGuide(cert, key, dir, created);
        return false;
    }

    /** Logs a prominent, copy-pasteable guide for providing TLS material. ASCII-only on purpose. */
    private static void logSetupGuide(File cert, File key, File dir, boolean dirCreated) {
        var log = OnlineChat.LOGGER;
        log.warn("[OnlineChat] ====================== TLS NOT CONFIGURED ======================");
        log.warn("[OnlineChat] The HTTPS listener was NOT started because no TLS certificate/key was found.");
        log.warn("[OnlineChat]   Expected certificate : {}", cert.getAbsolutePath());
        log.warn("[OnlineChat]   Expected private key : {}", key.getAbsolutePath());
        if (dir != null) {
            log.warn("[OnlineChat]   Certificate directory: {}{}", dir.getAbsolutePath(),
                    dirCreated ? "   (just created - drop the PEM files here)" : "");
        }
        log.warn("[OnlineChat] Enable HTTPS by doing ONE of the following, then run '/onlinechat reload' or restart:");
        log.warn("[OnlineChat]   1) Real cert (Let's Encrypt / your CA): copy fullchain.pem + privkey.pem into the directory above.");
        log.warn("[OnlineChat]   2) Self-signed (LAN / testing):");
        log.warn("[OnlineChat]        openssl req -x509 -newkey rsa:2048 -nodes -days 365 -keyout \"{}\" -out \"{}\" -subj \"/CN=localhost\"",
                key.getAbsolutePath(), cert.getAbsolutePath());
        log.warn("[OnlineChat]   3) Certs live elsewhere: in onlinechat-server.toml [tls] set certDir / certFileName / keyFileName,");
        log.warn("[OnlineChat]      or the explicit certChainPath / privateKeyPath. Encrypted key? Also set privateKeyPassword.");
        log.warn("[OnlineChat] Only testing on an isolated LAN without TLS? Set httpEnabled = true (top level of the server");
        log.warn("[OnlineChat]   config) to serve plain HTTP on httpPort - UNENCRYPTED; never expose it to the public internet.");
        log.warn("[OnlineChat] Docs: docs/INSTALL.md (English) or docs/zh/INSTALL.md (Chinese)");
        log.warn("[OnlineChat] ====================================================================");
    }

    /**
     * Read only the first non-empty line of a PEM file (its {@code -----BEGIN ...-----} header) so the
     * key format can be classified without loading the secret material into memory.
     *
     * @return the header line, or {@code null} if it could not be read or is not a PEM header
     */
    private static String pemHeader(File file) {
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                return s.startsWith("-----BEGIN ") ? s : null;
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable here - let Netty produce its own error downstream.
        }
        return null;
    }

    /**
     * Classify a private key's PEM format for compatibility with Netty's JDK TLS provider, which only
     * reads PKCS#8 ({@code -----BEGIN PRIVATE KEY-----}).
     *
     * @return {@code null} when the key should be readable; {@code "encrypted"} when it is an encrypted
     *         PKCS#8 key but {@code [tls] privateKeyPassword} is empty; {@code "legacy"} when it is a
     *         traditional SEC1/PKCS#1/OpenSSH key that must be converted to PKCS#8
     */
    private static String inspectKeyFormat(File key) {
        String header = pemHeader(key);
        if (header == null) return null;
        if (header.contains("ENCRYPTED PRIVATE KEY")) {
            return notBlank(ServerConfig.PRIVATE_KEY_PASSWORD.get()) ? null : "encrypted";
        }
        if (header.startsWith("-----BEGIN PRIVATE KEY-----")) return null;   // unencrypted PKCS#8 - supported
        if (header.contains("PRIVATE KEY")) return "legacy";                  // EC / RSA / DSA / OPENSSH traditional
        return null;
    }

    /** Log an actionable fix for an unreadable private-key format (see {@link #inspectKeyFormat}). */
    private static void logKeyFormatGuide(File key, String problem) {
        var log = OnlineChat.LOGGER;
        String p = key.getAbsolutePath();
        log.error("[OnlineChat] ====================== TLS KEY UNUSABLE ======================");
        if ("encrypted".equals(problem)) {
            log.error("[OnlineChat] The HTTPS listener was NOT started: the private key is encrypted but no password is set.");
            log.error("[OnlineChat]   Private key file: {}", p);
            log.error("[OnlineChat] Fix - set the passphrase in onlinechat-server.toml:   [tls] privateKeyPassword = \"your-passphrase\"");
            log.error("[OnlineChat]   or decrypt it once:  openssl pkcs8 -in \"{}\" -out privkey-dec.pem   and use that file", p);
        } else {
            log.error("[OnlineChat] The HTTPS listener was NOT started: the private key is in a legacy/traditional PEM format");
            log.error("[OnlineChat]   (e.g. 'BEGIN EC PRIVATE KEY' or 'BEGIN RSA PRIVATE KEY') that the JDK TLS provider cannot read.");
            log.error("[OnlineChat]   Private key file: {}", p);
            log.error("[OnlineChat] Fix - convert it to unencrypted PKCS#8 (header must become '-----BEGIN PRIVATE KEY-----'):");
            log.error("[OnlineChat]   openssl pkcs8 -topk8 -nocrypt -in \"{}\" -out privkey-pkcs8.pem", p);
            log.error("[OnlineChat]   then replace the key file, or set   [tls] keyFileName = \"privkey-pkcs8.pem\".");
        }
        log.error("[OnlineChat] After fixing, run '/onlinechat reload' or restart the server.");
        log.error("[OnlineChat] Docs: docs/INSTALL.md (English) or docs/zh/INSTALL.md (Chinese)");
        log.error("[OnlineChat] ==================================================================");
    }
}
