package net.mcless.dev.onlinechat.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Common configuration for the chat bridge behaviour.
 * Stored in {@code config/onlinechat-common.toml}.
 */
public class CommonConfig {
    /** Schema version of this config file. Bump when keys are added, renamed or re-purposed. */
    public static final int SCHEMA_VERSION = 2;

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue CONFIG_VERSION;
    public static final ForgeConfigSpec.BooleanValue BRIDGE_ENABLED;
    public static final ForgeConfigSpec.BooleanValue BRIDGE_SYSTEM_MESSAGES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SYSTEM_MESSAGE_KINDS;
    public static final ForgeConfigSpec.BooleanValue WEB_PRESENCE_MESSAGES;
    /** Deprecated alias of {@link #WEB_PRESENCE_MESSAGES} kept for upgrades; both must be true. */
    public static final ForgeConfigSpec.BooleanValue BRIDGE_JOIN_LEAVE;

    public static final ForgeConfigSpec.ConfigValue<String> WEB_PREFIX_TEXT;
    public static final ForgeConfigSpec.ConfigValue<String> WEB_PREFIX_COLOR;
    public static final ForgeConfigSpec.ConfigValue<String> IN_GAME_PREFIX_TEXT;
    public static final ForgeConfigSpec.ConfigValue<String> IN_GAME_PREFIX_COLOR;
    public static final ForgeConfigSpec.ConfigValue<String> WEB_PREFIX_COLOR_CSS;

    public static final ForgeConfigSpec.ConfigValue<String> GAME_CHAT_FORMAT;
    public static final ForgeConfigSpec.BooleanValue STRIP_FORMATTING;
    public static final ForgeConfigSpec.IntValue MAX_WEB_MESSAGE_LENGTH;

    public static final ForgeConfigSpec SPEC;

    static {
        CONFIG_VERSION = BUILDER
                .comment("Schema version of this config file, written by the mod. Older versions trigger the",
                        "built-in migration notice on start-up; do not edit by hand.")
                .defineInRange("configVersion", SCHEMA_VERSION, 0, Integer.MAX_VALUE);

        BRIDGE_ENABLED = BUILDER
                .comment("Master switch: bridge in-game chat to the web platform.")
                .define("bridgeEnabled", true);

        BRIDGE_SYSTEM_MESSAGES = BUILDER
                .comment("Bridge non-player chat messages (join, quit, death, advancement) to the web platform.")
                .define("bridgeSystemMessages", true);

        SYSTEM_MESSAGE_KINDS = BUILDER
                .comment("Which non-player messages should be bridged. Valid values: join, quit, death, advancement.")
                .defineListAllowEmpty("systemMessageKinds",
                        List.of("join", "quit", "death", "advancement"),
                        o -> o instanceof String s && List.of("join", "quit", "death", "advancement").contains(s));

        WEB_PRESENCE_MESSAGES = BUILDER
                .comment("Send a system message to the web chat when a web user connects to or disconnects",
                        "from the WebSocket ('alice connected to the web chat' / 'alice disconnected ...').",
                        "Turn this off to keep the web chat free of connect/disconnect noise.")
                .define("webPresenceMessages", true);

        BRIDGE_JOIN_LEAVE = BUILDER
                .comment("Deprecated pre-1.0.0 name of webPresenceMessages; kept so existing configs keep their",
                        "setting on upgrade. Presence messages are sent only when BOTH keys are true.")
                .define("bridgeWebPresence", true);

        BUILDER.push("prefixes");

        WEB_PREFIX_TEXT = BUILDER
                .comment("Text shown before the sender name in the in-game chat when the message came from the web.")
                .define("webPrefixText", "[Web Chat]");

        WEB_PREFIX_COLOR = BUILDER
                .comment("Colour of the [Web Chat] prefix. Any ChatColor name (GOLD, YELLOW, RED, ...). Orange-ish default: GOLD.")
                .define("webPrefixColor", "GOLD");

        IN_GAME_PREFIX_TEXT = BUILDER
                .comment("Text shown before the sender name on the web page when the message came from the game.")
                .define("inGamePrefixText", "[In Game]");

        IN_GAME_PREFIX_COLOR = BUILDER
                .comment("CSS colour applied to the [In Game] prefix on the web page.")
                .define("inGamePrefixColor", "#2ecc71");

        WEB_PREFIX_COLOR_CSS = BUILDER
                .comment("CSS colour applied to the [Web Chat] prefix on the web page (used for messages relayed back).")
                .define("webPrefixColorCss", "#e67e22");

        BUILDER.pop();

        BUILDER.push("format");

        GAME_CHAT_FORMAT = BUILDER
                .comment("Format for a web user's message relayed into the in-game chat.",
                        "Placeholders: {prefix} {name} {message}")
                .define("gameChatFormat", "{prefix} <{name}> {message}");

        STRIP_FORMATTING = BUILDER
                .comment("Strip Minecraft formatting codes (section-sign sequences) before forwarding a game message to the web.")
                .define("stripFormatting", true);

        MAX_WEB_MESSAGE_LENGTH = BUILDER
                .comment("Maximum length of a chat message a web user can send.")
                .defineInRange("maxWebMessageLength", 500, 1, 4000);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
