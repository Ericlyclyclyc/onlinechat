package net.mcless.dev.onlinechat.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Server-side configuration: HTTPS listener, TLS material, authentication and account storage.
 * Stored per-world in {@code <level-name>/serverconfig/onlinechat-server.toml} on 1.20.1
 * (that is also where the dedicated server reads it: {@code world/serverconfig/}).
 */
public class ServerConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.ConfigValue<String> LANGUAGE;

    public static final ForgeConfigSpec.BooleanValue WEB_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> HOST;
    public static final ForgeConfigSpec.IntValue PORT;
    public static final ForgeConfigSpec.BooleanValue HTTP_ENABLED;
    public static final ForgeConfigSpec.IntValue HTTP_PORT;

    public static final ForgeConfigSpec.ConfigValue<String> CERT_DIR;
    public static final ForgeConfigSpec.ConfigValue<String> CERT_FILE_NAME;
    public static final ForgeConfigSpec.ConfigValue<String> KEY_FILE_NAME;
    public static final ForgeConfigSpec.ConfigValue<String> CERT_CHAIN_PATH;
    public static final ForgeConfigSpec.ConfigValue<String> PRIVATE_KEY_PATH;
    public static final ForgeConfigSpec.ConfigValue<String> PRIVATE_KEY_PASSWORD;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_CLIENT_AUTH;

    public static final ForgeConfigSpec.BooleanValue ALLOW_REGISTRATION;
    public static final ForgeConfigSpec.IntValue MIN_PASSWORD_LENGTH;
    public static final ForgeConfigSpec.IntValue PBKDF2_ITERATIONS;
    public static final ForgeConfigSpec.LongValue TOKEN_TTL_MINUTES;
    public static final ForgeConfigSpec.ConfigValue<String> TOKEN_SECRET;
    public static final ForgeConfigSpec.IntValue MAX_LOGIN_ATTEMPTS;
    public static final ForgeConfigSpec.IntValue LOGIN_COOLDOWN_SECONDS;

    public static final ForgeConfigSpec.IntValue BIND_CODE_TTL_SECONDS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_REBIND;

    public static final ForgeConfigSpec.ConfigValue<String> ACCOUNTS_FILE;
    public static final ForgeConfigSpec.IntValue CHAT_HISTORY_SIZE;
    public static final ForgeConfigSpec.IntValue CHAT_PAGE_SIZE;
    public static final ForgeConfigSpec.ConfigValue<String> CHAT_LOG_FILE;
    public static final ForgeConfigSpec.ConfigValue<String> WEB_DIR;

    public static final ForgeConfigSpec.BooleanValue TWO_FACTOR_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> TWO_FACTOR_PUBLIC_URL;
    public static final ForgeConfigSpec.IntValue TWO_FACTOR_TIMEOUT_SECONDS;

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ALLOWED_ORIGINS;

    public static final ForgeConfigSpec.IntValue MAX_CONNECTIONS_PER_IP;
    public static final ForgeConfigSpec.IntValue MAX_CONNECTIONS_TOTAL;
    public static final ForgeConfigSpec.IntValue MAX_CHAT_MESSAGES_PER_MINUTE;
    public static final ForgeConfigSpec.IntValue REGISTER_ATTEMPTS_PER_HOUR;
    public static final ForgeConfigSpec.IntValue BIND_REQUESTS_PER_MINUTE;

    public static final ForgeConfigSpec.BooleanValue VERBOSE_LOGGING;

    public static final ForgeConfigSpec SPEC;

    static {
        LANGUAGE = BUILDER
                .comment("Language used for every in-game message this mod sends (chat prompts, command feedback).",
                        "The server renders the text itself so vanilla clients without the mod see it translated.",
                        "Must match a file in assets/onlinechat/lang/ (e.g. en_us, zh_cn). Falls back to en_us.")
                .define("language", "en_us");

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

        CERT_DIR = BUILDER
                .comment("Directory holding the TLS PEM files. Relative paths resolve against the Minecraft run directory.",
                        "Used to locate the certificate and key whenever certChainPath / privateKeyPath are left blank.")
                .define("certDir", "./ssl");

        CERT_FILE_NAME = BUILDER
                .comment("Certificate file name inside certDir (PEM leaf + intermediate chain, e.g. Let's Encrypt fullchain.pem).",
                        "Ignored when certChainPath is set to a non-blank value.")
                .define("certFileName", "fullchain.pem");

        KEY_FILE_NAME = BUILDER
                .comment("Private key file name inside certDir (PKCS#8 or PKCS#1).",
                        "Ignored when privateKeyPath is set to a non-blank value.")
                .define("keyFileName", "privkey.pem");

        CERT_CHAIN_PATH = BUILDER
                .comment("Optional explicit path to the PEM certificate chain, overriding certDir + certFileName.",
                        "Leave blank to use <certDir>/<certFileName>. Relative paths resolve against the run directory.")
                .define("certChainPath", "");

        PRIVATE_KEY_PATH = BUILDER
                .comment("Optional explicit path to the PEM private key, overriding certDir + keyFileName.",
                        "Leave blank to use <certDir>/<keyFileName>. Relative paths resolve against the run directory.")
                .define("privateKeyPath", "");

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
                .comment("Number of most recent chat messages cached in RAM. Older messages are not lost: they",
                        "live in the append-only chat archive (chatLogFile) and are paged back on demand.")
                .defineInRange("chatHistorySize", 300, 1, 100_000);

        CHAT_PAGE_SIZE = BUILDER
                .comment("Number of messages delivered per page: the initial batch replayed to a freshly",
                        "connected web client, and the size of each 'scroll up to load more' request.")
                .defineInRange("chatPageSize", 30, 1, 200);

        CHAT_LOG_FILE = BUILDER
                .comment("Location of the append-only chat archive (JSON Lines). Relative paths resolve against",
                        "the run directory. Every message is written here; it doubles as a deployable backup.")
                .define("chatLogFile", "onlinechat/chat_history.jsonl");

        WEB_DIR = BUILDER
                .comment("Directory the bundled web front-end (HTML/JS/CSS/locales) is extracted to on first start so it",
                        "can be customised. Relative paths resolve against the run directory. Files here are served in",
                        "preference to the copies inside the jar. The extraction only happens when the hidden marker",
                        "file '.exist' is missing from the directory; delete it to re-extract the pristine defaults.")
                .define("webDir", "config/onlinechat/web");

        BUILDER.pop();

        BUILDER.push("twoFactor");

        TWO_FACTOR_ENABLED = BUILDER
                .comment("Offer two-factor login protection to players. When enabled, a player whose bound web account",
                        "has 2FA switched on is frozen after joining until they open the one-time link shown in chat",
                        "from a browser that is signed in to that web account. Players without 2FA are unaffected.")
                .define("enabled", false);

        TWO_FACTOR_PUBLIC_URL = BUILDER
                .comment("Public base URL of this web server as seen by players' browsers, e.g. https://play.example.com:8443",
                        "It is prepended to the /2fa/auth/<token> path in the chat link. No trailing slash.",
                        "Leave blank to fall back to https://<server-ip>:<port> which is rarely what you want.")
                .define("publicUrl", "");

        TWO_FACTOR_TIMEOUT_SECONDS = BUILDER
                .comment("Seconds a frozen player has to complete the browser verification before being kicked.")
                .defineInRange("timeoutSeconds", 120, 15, 600);

        BUILDER.pop();

        BUILDER.push("cors");

        ALLOWED_ORIGINS = BUILDER
                .comment("Allowed CORS origins for the REST/WebSocket API.",
                        "Use '*' to allow any origin (not recommended on the public internet).")
                .defineListAllowEmpty("allowedOrigins",
                        List.of("*"),
                        o -> o instanceof String);

        BUILDER.pop();

        BUILDER.push("limits");

        MAX_CONNECTIONS_PER_IP = BUILDER
                .comment("Maximum simultaneous WebSocket connections allowed from a single IP. 0 disables the limit.")
                .defineInRange("maxConnectionsPerIp", 8, 0, 100_000);

        MAX_CONNECTIONS_TOTAL = BUILDER
                .comment("Maximum simultaneous WebSocket connections across all clients. 0 disables the limit.")
                .defineInRange("maxConnectionsTotal", 200, 0, 1_000_000);

        MAX_CHAT_MESSAGES_PER_MINUTE = BUILDER
                .comment("Maximum chat messages a single web session may send per minute. 0 disables the limit.")
                .defineInRange("maxChatMessagesPerMinute", 20, 0, 100_000);

        REGISTER_ATTEMPTS_PER_HOUR = BUILDER
                .comment("Maximum /api/register calls per IP per hour (anti account-flooding). 0 disables the limit.")
                .defineInRange("registerAttemptsPerHour", 5, 0, 100_000);

        BIND_REQUESTS_PER_MINUTE = BUILDER
                .comment("Maximum binding requests a single web account may start per minute (anti popup-harassment). 0 disables the limit.")
                .defineInRange("bindRequestsPerMinute", 3, 0, 100_000);

        BUILDER.pop();

        VERBOSE_LOGGING = BUILDER
                .comment("Log every HTTP request and WebSocket frame at INFO level. Useful for debugging only.")
                .define("verboseLogging", false);

        SPEC = BUILDER.build();
    }
}
