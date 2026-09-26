package net.mcless.dev.onlinechat.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Common configuration for the chat bridge behaviour.
 * Stored in {@code config/onlinechat-common.toml}.
 */
public class CommonConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue BRIDGE_ENABLED;
    public static final ForgeConfigSpec.BooleanValue BRIDGE_SYSTEM_MESSAGES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SYSTEM_MESSAGE_KINDS;
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

        BRIDGE_JOIN_LEAVE = BUILDER
                .comment("Broadcast a game-side chat line when a web user connects or disconnects from the WebSocket.")
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
