package net.mcless.dev.onlinechat.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import net.mcless.dev.onlinechat.OnlineChat;
import net.mcless.dev.onlinechat.account.Account;
import net.mcless.dev.onlinechat.account.AccountManager;
import net.mcless.dev.onlinechat.config.CommonConfig;
import net.mcless.dev.onlinechat.i18n.Lang;
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

import java.util.ArrayList;
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
    private final MessageStore store;
    private volatile MinecraftServer server;

    public ChatBridge(WebSessionManager sessions, AccountManager accounts, MessageStore store) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.store = store;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    // ─────────────────────────── Broadcast helpers ───────────────────────────

    public synchronized void rememberAndBroadcast(ChatMessage msg) {
        long seq = store.append(msg);
        String payload = GSON.toJson(MessageStore.toClientJson(seq, msg));
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

    /**
     * Force-logs-out every live WebSocket session for {@code username}, used when the account signs in on
     * another device: pushes a {@code force_logout} frame so the client can show a message, then closes the
     * channel. The account's existing tokens are invalidated separately by bumping {@code lastLoginAt}.
     */
    public void forceLogout(String username, String reason) {
        OnlineChat.LOGGER.debug("[OnlineChat] Web force-logout: {} (reason: {})", username, reason);
        JsonObject o = new JsonObject();
        o.addProperty("type", "force_logout");
        o.addProperty("reason", reason);
        String json = GSON.toJson(o);
        for (WebSessionManager.Session s : sessions.byUsername(username)) {
            if (s.channel.isActive()) {
                s.channel.writeAndFlush(new TextWebSocketFrame(json)).addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    /**
     * Notifies every live WebSocket client that the server is shutting down, then closes the sockets.
     * Invoked from {@link net.mcless.dev.onlinechat.OnlineChat#onServerStopping} <em>before</em> the web
     * server tears down its event loops, so each browser can show a "server closed" dialog instead of
     * silently spinning in a reconnect loop. Writes are flushed and channels closed before this returns
     * (bounded await), guaranteeing the frame reaches the client ahead of the imminent shutdown.
     */
    public void broadcastShutdown() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "server_shutdown");
        String json = GSON.toJson(o);
        List<ChannelFuture> futures = new ArrayList<>();
        for (WebSessionManager.Session s : sessions.all()) {
            Channel ch = s.channel;
            if (ch != null && ch.isActive()) {
                futures.add(ch.writeAndFlush(new TextWebSocketFrame(json)).addListener(ChannelFutureListener.CLOSE));
            }
        }
        // Give the frames a brief, bounded moment to reach the browsers before the event loops are torn down.
        for (ChannelFuture f : futures) {
            try {
                f.await(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void sendTo(Channel channel, JsonObject payload) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(GSON.toJson(payload)));
        }
    }

    /** The newest {@code limit} messages for a freshly connected client, plus whether older history exists. */
    public MessageStore.Page recentPage(int limit) {
        return store.latest(limit);
    }

    /** Up to {@code limit} messages older than {@code cursorSeq} (the client's "scroll up to load more" page). */
    public MessageStore.Page messagesBefore(long cursorSeq, int limit) {
        return store.before(cursorSeq, limit);
    }

    /** Full-archive search (see {@link MessageStore#search}); run it off the Netty event loop. */
    public MessageStore.Page searchMessages(String query, long beforeSeq, int limit) {
        return store.search(query, beforeSeq, limit);
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
        OnlineChat.LOGGER.info("[OnlineChat] Web chat <{}>: {}", displayName, text);

        ChatMessage rec = new ChatMessage(System.currentTimeMillis(), Kind.WEB,
                displayName,
                session.boundPlayerUuid == null ? null : session.boundPlayerUuid.toString(),
                text, null);
        // Persist, then push the archived frame to EVERY authenticated web session — including the
        // sender. The web UI renders only server-echoed frames (no optimistic local echo), so
        // skipping the sender would make their own messages invisible on the web.
        long seq;
        synchronized (this) {
            seq = store.append(rec);
        }
        String payload = GSON.toJson(MessageStore.toClientJson(seq, rec));
        for (WebSessionManager.Session s : sessions.all()) {
            if (!s.isAuthenticated()) continue;
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
                player.getGameProfile().name(),
                player.getUUID().toString(),
                plain, null));
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!shouldBridge("join")) return;
        String name = event.getEntity().getGameProfile().name();
        rememberAndBroadcast(new ChatMessage(System.currentTimeMillis(), Kind.SYSTEM, null, null,
                Lang.tr("onlinechat.bridge.join", name), "join"));
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!shouldBridge("quit")) return;
        String name = event.getEntity().getGameProfile().name();
        rememberAndBroadcast(new ChatMessage(System.currentTimeMillis(), Kind.SYSTEM, null, null,
                Lang.tr("onlinechat.bridge.quit", name), "quit"));
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!shouldBridge("death")) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Component msg = player.getCombatTracker().getDeathMessage();
        String text = CommonConfig.STRIP_FORMATTING.get() ? msg.getString() : msg.getString();
        rememberAndBroadcast(new ChatMessage(System.currentTimeMillis(), Kind.SYSTEM,
                player.getGameProfile().name(), player.getUUID().toString(),
                text, "death"));
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!shouldBridge("advancement")) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Component title = event.getAdvancement().value().display()
                .map(d -> d.getTitle())
                .orElse(Component.literal(event.getAdvancement().id().toString()));
        String text = Lang.tr("onlinechat.bridge.advancement", player.getGameProfile().name(), title.getString());
        rememberAndBroadcast(new ChatMessage(System.currentTimeMillis(), Kind.SYSTEM,
                player.getGameProfile().name(), player.getUUID().toString(),
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

    /**
     * Sends an operator announcement to BOTH sides: a highlighted line in the in-game chat and a
     * {@code system} message with {@code systemKind: "announce"} to every web client (which styles it
     * prominently). Not gated by {@code bridgeWebPresence} — an announcement is an explicit action.
     */
    public void emitAnnouncement(String text) {
        if (text == null || text.isBlank()) return;
        String t = text.trim();
        MinecraftServer srv = this.server;
        if (srv != null) {
            MutableComponent line = Component.empty()
                    .append(Lang.text("onlinechat.prefix").withStyle(ChatFormatting.GOLD))
                    .append(Lang.text("onlinechat.command.announce.line", t).withStyle(ChatFormatting.LIGHT_PURPLE));
            srv.getPlayerList().broadcastSystemMessage(line, false);
        }
        OnlineChat.LOGGER.info("[OnlineChat] Announcement: {}", t);
        rememberAndBroadcast(new ChatMessage(System.currentTimeMillis(), Kind.SYSTEM, null, null, t, "announce"));
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
