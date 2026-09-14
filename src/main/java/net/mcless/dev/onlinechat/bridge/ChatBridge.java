package net.mcless.dev.onlinechat.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import net.mcless.dev.onlinechat.OnlineChat;
import net.mcless.dev.onlinechat.account.Account;
import net.mcless.dev.onlinechat.account.AccountManager;
import net.mcless.dev.onlinechat.config.CommonConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bridges Minecraft chat / system events to the web platform and vice-versa.
 */
public class ChatBridge {
    public enum Kind { CHAT, SYSTEM, WEB }

    public record ChatMessage(long timestamp, Kind kind, String author, String authorUuid, String text, String systemKind) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("type", switch (kind) {
                case CHAT -> "chat";
                case WEB -> "web";
                case SYSTEM -> "system";
            });
            o.addProperty("ts", timestamp);
            if (author != null) o.addProperty("author", author);
            if (authorUuid != null) o.addProperty("authorUuid", authorUuid);
            o.addProperty("text", text);
            if (systemKind != null) o.addProperty("systemKind", systemKind);
            return o;
        }
    }

    private static final Gson GSON = new Gson();

    private final WebSessionManager sessions;
    private final AccountManager accounts;
    private final Deque<ChatMessage> history = new ArrayDeque<>();
    private volatile MinecraftServer server;

    public ChatBridge(WebSessionManager sessions, AccountManager accounts) {
        this.sessions = sessions;
        this.accounts = accounts;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    // ─────────────────────────── Broadcast helpers ───────────────────────────

    public synchronized void rememberAndBroadcast(ChatMessage msg) {
        int cap = Math.max(0, net.mcless.dev.onlinechat.config.ServerConfig.CHAT_HISTORY_SIZE.get());
        if (cap > 0) {
            history.addLast(msg);
            while (history.size() > cap) history.removeFirst();
        }
        String payload = GSON.toJson(msg.toJson());
        for (WebSessionManager.Session s : sessions.all()) {
            if (!s.isAuthenticated()) continue;
            if (s.channel.isActive()) s.channel.writeAndFlush(new TextWebSocketFrame(payload));
        }
    }

    public void broadcastToUsername(String username, JsonObject payload) {
        String json = GSON.toJson(payload);
        for (WebSessionManager.Session s : sessions.byUsername(username)) {
            if (s.channel.isActive()) s.channel.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    public void sendTo(Channel channel, JsonObject payload) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(GSON.toJson(payload)));
        }
    }

    public List<JsonObject> historySnapshot() {
        List<JsonObject> out = new ArrayList<>();
        synchronized (this) {
            for (ChatMessage m : history) out.add(m.toJson());
        }
        return out;
    }

    // ─────────────────────────── Web → Game ───────────────────────────

    /**
     * Called by the WebSocket handler when an authenticated web user sends a chat message.
     */
    public void handleWebChat(WebSessionManager.Session session, String rawText) {
        if (session == null || !session.isAuthenticated()) return;
        if (rawText == null) return;
        int max = CommonConfig.MAX_WEB_MESSAGE_LENGTH.get();
        String text = rawText.replace("\r", " ").replace("\n", " ").trim();
        if (text.isEmpty()) return;
        if (text.length() > max) text = text.substring(0, max);
        // Strip Minecraft formatting characters so a web user cannot inject § codes.
        text = text.replace("§", "&");

        MinecraftServer srv = this.server;
        if (srv == null) return;

        String displayName = Optional.ofNullable(session.boundPlayerUuid)
                .flatMap(accounts::byPlayerUuid)
                .map(Account::getBoundPlayerName)
                .orElse(session.username);

        MutableComponent prefix = Component.literal(CommonConfig.WEB_PREFIX_TEXT.get() + " ")
                .withStyle(colorFromName(CommonConfig.WEB_PREFIX_COLOR.get()));
        String format = CommonConfig.GAME_CHAT_FORMAT.get();
        MutableComponent line = Component.empty();

        // Very small format engine: {prefix}, {name}, {message}
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{prefix\\}|\\{name\\}|\\{message\\}")
                .matcher(format);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) line.append(Component.literal(format.substring(last, m.start())));
            switch (m.group()) {
                case "{prefix}" -> line.append(prefix);
                case "{name}" -> line.append(Component.literal(displayName).withStyle(ChatFormatting.YELLOW));
                case "{message}" -> line.append(Component.literal(text).withStyle(ChatFormatting.WHITE));
            }
            last = m.end();
        }
        if (last < format.length()) line.append(Component.literal(format.substring(last)));

        // broadcastSystemMessage bypasses ServerChatEvent so we do not echo back to the web.
        srv.getPlayerList().broadcastSystemMessage(line, false);

        ChatMessage rec = new ChatMessage(System.currentTimeMillis(), Kind.WEB,
                displayName,
                session.boundPlayerUuid == null ? null : session.boundPlayerUuid.toString(),
                text, null);
        // Only push to history + other web clients (not back to sender's game view).
        synchronized (this) {
            int cap = Math.max(0, net.mcless.dev.onlinechat.config.ServerConfig.CHAT_HISTORY_SIZE.get());
            if (cap > 0) {
                history.addLast(rec);
                while (history.size() > cap) history.removeFirst();
            }
        }
        String payload = GSON.toJson(rec.toJson());
        for (WebSessionManager.Session s : sessions.all()) {
            if (!s.isAuthenticated() || s == session) continue;
            if (s.channel.isActive()) s.channel.writeAndFlush(new TextWebSocketFrame(payload));
        }
    }

    // ─────────────────────────── Game → Web ───────────────────────────

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerChat(ServerChatEvent event) {
        if (!CommonConfig.BRIDGE_ENABLED.get()) return;
        ServerPlayer player = event.getPlayer();
        String plain = event.getRawText();
        if (CommonConfig.STRIP_FORMATTING.get()) {
            plain = plain.replace("§", "&");
        }
        rememberAndBroadcast(new ChatMessage(
                System.currentTimeMillis(), Kind.CHAT,
                player.getGameProfile().getName(),
                player.getUUID().toString(),
                plain, null));
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!shouldBridge("join")) return;
        String name = event.getEntity().getGameProfile().getName();
        rememberAndBroadcast(new ChatMessage(System.currentTimeMillis(), Kind.SYSTEM, null, null,
                name + " joined the game", "join"));
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!shouldBridge("quit")) return;
        String name = event.getEntity().getGameProfile().getName();
        rememberAndBroadcast(new ChatMessage(System.currentTimeMillis(), Kind.SYSTEM, null, null,
                name + " left the game", "quit"));
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!shouldBridge("death")) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Component msg = player.getCombatTracker().getDeathMessage();
        String text = CommonConfig.STRIP_FORMATTING.get() ? msg.getString() : msg.getString();
        rememberAndBroadcast(new ChatMessage(System.currentTimeMillis(), Kind.SYSTEM,
                player.getGameProfile().getName(), player.getUUID().toString(),
                text, "death"));
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!shouldBridge("advancement")) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Component title = event.getAdvancement().value().display()
                .map(d -> d.getTitle())
                .orElse(Component.literal(event.getAdvancement().id().toString()));
        String text = player.getGameProfile().getName() + " has made the advancement [" + title.getString() + "]";
        rememberAndBroadcast(new ChatMessage(System.currentTimeMillis(), Kind.SYSTEM,
                player.getGameProfile().getName(), player.getUUID().toString(),
                text, "advancement"));
    }

    private boolean shouldBridge(String kind) {
        if (!CommonConfig.BRIDGE_ENABLED.get()) return false;
        if (!CommonConfig.BRIDGE_SYSTEM_MESSAGES.get()) return false;
        return CommonConfig.SYSTEM_MESSAGE_KINDS.get().contains(kind);
    }

    /** Broadcast a system-style message to all web clients. */
    public void emitWebSystem(String text) {
        if (!CommonConfig.BRIDGE_JOIN_LEAVE.get()) return;
        rememberAndBroadcast(new ChatMessage(System.currentTimeMillis(), Kind.SYSTEM, null, null, text, "web"));
    }

    /** Broadcast a system-style line to the in-game chat. */
    public void emitGameSystem(Component component) {
        MinecraftServer srv = this.server;
        if (srv == null) return;
        srv.getPlayerList().broadcastSystemMessage(component, false);
    }

    public MinecraftServer server() { return server; }
    public WebSessionManager sessions() { return sessions; }

    public static ChatFormatting colorFromName(String name) {
        try {
            return ChatFormatting.valueOf(name.toUpperCase());
        } catch (Exception e) {
            return ChatFormatting.GOLD;
        }
    }

    /** Convert a UUID to a stable, non-tracking web identifier. */
    public static String safeUuid(UUID uuid) { return uuid == null ? null : uuid.toString(); }

    static {
        OnlineChat.LOGGER.debug("[OnlineChat] ChatBridge ready");
    }
}
