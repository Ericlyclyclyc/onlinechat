package net.mcless.dev.onlinechat.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Server-side configuration: HTTPS listener, TLS material, authentication and account storage.
 * Stored in {@code config/onlinechat-server.toml} (per-world on integrated servers, global on dedicated servers).
 */
public class ServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue WEB_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> HOST;
    public static final ModConfigSpec.IntValue PORT;
    public static final ModConfigSpec.BooleanValue HTTP_ENABLED;
    public static final ModConfigSpec.IntValue HTTP_PORT;

    public static final ModConfigSpec.ConfigValue<String> CERT_CHAIN_PATH;
    public static final ModConfigSpec.ConfigValue<String> PRIVATE_KEY_PATH;
    public static final ModConfigSpec.ConfigValue<String> PRIVATE_KEY_PASSWORD;
    public static final ModConfigSpec.BooleanValue REQUIRE_CLIENT_AUTH;

    public static final ModConfigSpec.BooleanValue ALLOW_REGISTRATION;
    public static final ModConfigSpec.IntValue MIN_PASSWORD_LENGTH;
    public static final ModConfigSpec.IntValue PBKDF2_ITERATIONS;
    public static final ModConfigSpec.LongValue TOKEN_TTL_MINUTES;
    public static final ModConfigSpec.ConfigValue<String> TOKEN_SECRET;
    public static final ModConfigSpec.IntValue MAX_LOGIN_ATTEMPTS;
    public static final ModConfigSpec.IntValue LOGIN_COOLDOWN_SECONDS;

    public static final ModConfigSpec.IntValue BIND_CODE_TTL_SECONDS;
    public static final ModConfigSpec.BooleanValue ALLOW_REBIND;

    public static final ModConfigSpec.ConfigValue<String> ACCOUNTS_FILE;
    public static final ModConfigSpec.IntValue CHAT_HISTORY_SIZE;

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED_ORIGINS;

    public static final ModConfigSpec.BooleanValue VERBOSE_LOGGING;

    public static final ModConfigSpec SPEC;

    static {
        WEB_ENABLED = BUILDER
                .comment("Start the embedded HTTPS/WebSocket server when the Minecraft server starts.")
                .define("enabled", true);

        HOST = BUILDER
                .comment("Bind address of the HTTPS server. Use 0.0.0.0 to listen on all interfaces.")
                .define("host", "0.0.0.0");

        PORT = BUILDER
                .comment("TCP port of the HTTPS server (the default, encrypted listener).")
                .defineInRange("port", 8443, 1, 65535);

        HTTP_ENABLED = BUILDER
                .comment("Also start a plain-HTTP (unencrypted) listener alongside HTTPS.",
                        "HTTPS stays the default and recommended listener. Enable HTTP only behind a reverse",
                        "proxy or for LAN testing. On this listener the auth cookie is issued WITHOUT the",
                        "Secure flag so browsers will accept it over http://.")
                .define("httpEnabled", false);

        HTTP_PORT = BUILDER
                .comment("TCP port of the plain-HTTP listener. Only used when httpEnabled = true.")
                .defineInRange("httpPort", 8080, 1, 65535);

        BUILDER.push("tls");

        CERT_CHAIN_PATH = BUILDER
                .comment("Path to the PEM full-chain certificate. Relative paths resolve against the Minecraft run directory.")
                .define("certChainPath", "./ssl/fullchain.pem");

        PRIVATE_KEY_PATH = BUILDER
                .comment("Path to the PEM private key (PKCS#8 or PKCS#1). Relative paths resolve against the Minecraft run directory.")
                .define("privateKeyPath", "./ssl/privkey.pem");

        PRIVATE_KEY_PASSWORD = BUILDER
                .comment("Password of the private key. Leave empty if the key is not encrypted.")
                .define("privateKeyPassword", "");

        REQUIRE_CLIENT_AUTH = BUILDER
                .comment("Require clients to present a TLS certificate (mutual TLS). Almost always false.")
                .define("requireClientAuth", false);

        BUILDER.pop();

        BUILDER.push("auth");

        ALLOW_REGISTRATION = BUILDER
                .comment("Allow new web accounts to be created through /api/register.")
                .define("allowRegistration", true);

        MIN_PASSWORD_LENGTH = BUILDER
                .comment("Minimum length of a web account password.")
                .defineInRange("minPasswordLength", 8, 4, 256);

        PBKDF2_ITERATIONS = BUILDER
                .comment("PBKDF2 iteration count used to hash passwords. Higher = slower but safer.")
                .defineInRange("pbkdf2Iterations", 210_000, 10_000, 2_000_000);

        TOKEN_TTL_MINUTES = BUILDER
                .comment("Lifetime of an authentication token, in minutes.")
                .defineInRange("tokenTtlMinutes", 1440L, 5L, 525_600L);

        TOKEN_SECRET = BUILDER
                .comment("HMAC secret used to sign authentication tokens.",
                        "Leave blank to auto-generate a random secret and persist it next to the accounts file.")
                .define("tokenSecret", "");

        MAX_LOGIN_ATTEMPTS = BUILDER
                .comment("Maximum failed login attempts per IP within the cooldown window before temporary lock-out.")
                .defineInRange("maxLoginAttempts", 8, 0, 1000);

        LOGIN_COOLDOWN_SECONDS = BUILDER
                .comment("Duration of the temporary lock-out, in seconds.")
                .defineInRange("loginCooldownSeconds", 300, 10, 86_400);

        BUILDER.pop();

        BUILDER.push("binding");

        BIND_CODE_TTL_SECONDS = BUILDER
                .comment("How long a pending bind code stays valid while waiting for the in-game player to click [Yes].")
                .defineInRange("bindCodeTtlSeconds", 120, 15, 3600);

        ALLOW_REBIND = BUILDER
                .comment("Allow an already-bound account to request a new binding (it must be re-confirmed in game).")
                .define("allowRebind", true);

        BUILDER.pop();

        BUILDER.push("storage");

        ACCOUNTS_FILE = BUILDER
                .comment("Location of the accounts JSON file. Relative paths resolve against the run directory.")
                .define("accountsFile", "onlinechat/accounts.json");

        CHAT_HISTORY_SIZE = BUILDER
                .comment("Number of recent chat messages kept in memory and replayed to newly connected web clients.")
                .defineInRange("chatHistorySize", 200, 0, 5000);

        BUILDER.pop();

        BUILDER.push("cors");

        ALLOWED_ORIGINS = BUILDER
                .comment("Allowed CORS origins for the REST/WebSocket API.",
                        "Use '*' to allow any origin (not recommended on the public internet).")
                .defineListAllowEmpty("allowedOrigins",
                        List.of("*"),
                        () -> "*",
                        o -> o instanceof String);

        BUILDER.pop();

        VERBOSE_LOGGING = BUILDER
                .comment("Log every HTTP request and WebSocket frame at INFO level. Useful for debugging only.")
                .define("verboseLogging", false);

        SPEC = BUILDER.build();
    }
}
