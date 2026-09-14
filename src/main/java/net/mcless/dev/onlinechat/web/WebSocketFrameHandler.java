package net.mcless.dev.onlinechat.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.AttributeKey;
import net.mcless.dev.onlinechat.OnlineChat;
import net.mcless.dev.onlinechat.account.Account;
import net.mcless.dev.onlinechat.account.AccountManager;
import net.mcless.dev.onlinechat.account.TokenService;
import net.mcless.dev.onlinechat.bridge.BindingManager;
import net.mcless.dev.onlinechat.bridge.ChatBridge;
import net.mcless.dev.onlinechat.bridge.WebSessionManager;
import net.mcless.dev.onlinechat.config.ServerConfig;

import java.util.Optional;

/**
 * WebSocket frame handler. All traffic is JSON:
 * <pre>
 *   client → server:
 *     { "type":"auth",       "token":"..." }
 *     { "type":"chat",       "text":"hello" }
 *     { "type":"bind",       "mcName":"Steve" }
 *     { "type":"unbind" }
 *     { "type":"ping" }
 *
 *   server → client:
 *     { "type":"ready",      "requiresAuth":true }
 *     { "type":"auth_ok",    "username":"...", "bound":true|false }
 *     { "type":"auth_error", "error":"..." }
 *     { "type":"chat"|"web"|"system", ... }   (bridged messages)
 *     { "type":"history",    "messages":[...] }
 *     { "type":"bind_pending","code":"ABC123","mcName":"Steve","expiresAt":1699999999999 }
 *     { "type":"bind_ok"|"bind_error"|"bind_expired", ... }
 *     { "type":"pong" }
 *     { "type":"error",      "error":"..." }
 * </pre>
 */
public class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    public static final AttributeKey<WebSessionManager.Session> SESSION_KEY =
            AttributeKey.valueOf("onlinechat.session");

    private static final Gson GSON = new Gson();

    private final AccountManager accounts;
    private final TokenService tokens;
    private final BindingManager bindings;
    private final ChatBridge bridge;
    private final WebSessionManager sessions;

    public WebSocketFrameHandler(AccountManager accounts, TokenService tokens, BindingManager bindings,
                                 ChatBridge bridge, WebSessionManager sessions) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.bindings = bindings;
        this.bridge = bridge;
        this.sessions = sessions;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        WebSessionManager.Session s = ctx.channel().attr(SESSION_KEY).get();
        if (s == null) return;
        JsonObject ready = new JsonObject();
        ready.addProperty("type", "ready");
        ready.addProperty("requiresAuth", true);
        bridge.sendTo(ctx.channel(), ready);

        // Auto-authenticate from the token captured on the HTTP upgrade request
        // (Cookie or Authorization header). Browsers cannot set custom headers on
        // a WebSocket handshake, so this is the only way to authenticate a cookie session.
        if (s.handshakeToken != null && !s.handshakeToken.isBlank()) {
            String token = s.handshakeToken;
            s.handshakeToken = null;
            tryAuthenticate(ctx, s, token, true);
        }
    }

    private void tryAuthenticate(ChannelHandlerContext ctx, WebSessionManager.Session session, String token, boolean silent) {
        Optional<String> username = tokens.validate(token);
        if (username.isEmpty()) {
            if (!silent) {
                JsonObject o = new JsonObject();
                o.addProperty("type", "auth_error");
                o.addProperty("error", "Invalid or expired token");
                bridge.sendTo(ctx.channel(), o);
            }
            return;
        }
        Account acc = accounts.byUsername(username.get()).orElse(null);
        if (acc == null) {
            if (!silent) {
                JsonObject o = new JsonObject();
                o.addProperty("type", "auth_error");
                o.addProperty("error", "Account no longer exists");
                bridge.sendTo(ctx.channel(), o);
            }
            return;
        }
        sessions.authenticate(session, acc.getUsername(), acc.getBoundPlayerUuid());

        JsonObject o = new JsonObject();
        o.addProperty("type", "auth_ok");
        o.addProperty("username", acc.getUsername());
        o.addProperty("bound", acc.isBound());
        if (acc.isBound()) {
            o.addProperty("mcName", acc.getBoundPlayerName());
            o.addProperty("mcUuid", acc.getBoundPlayerUuid().toString());
        }
        bridge.sendTo(ctx.channel(), o);

        JsonObject hist = new JsonObject();
        hist.addProperty("type", "history");
        var arr = new com.google.gson.JsonArray();
        bridge.historySnapshot().forEach(arr::add);
        hist.add("messages", arr);
        bridge.sendTo(ctx.channel(), hist);

        if (sessions.byUsername(acc.getUsername()).size() == 1) {
            bridge.emitWebSystem(acc.getUsername() + " connected to the web chat");
        }
        if (ServerConfig.VERBOSE_LOGGING.get()) {
            OnlineChat.LOGGER.info("[OnlineChat] Web user '{}' authenticated from {}", acc.getUsername(), session.remoteAddress);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        WebSessionManager.Session s = ctx.channel().attr(SESSION_KEY).get();
        if (s != null) {
            boolean wasAuth = s.isAuthenticated();
            String user = s.username;
            sessions.unregister(ctx.channel());
            if (wasAuth && !sessions.isUsernameOnline(user)) {
                bridge.emitWebSystem(user + " disconnected from the web chat");
                if (ServerConfig.VERBOSE_LOGGING.get()) {
                    OnlineChat.LOGGER.info("[OnlineChat] Web user '{}' disconnected", user);
                }
            }
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        WebSessionManager.Session session = ctx.channel().attr(SESSION_KEY).get();
        if (session == null) {
            ctx.close();
            return;
        }
        if (frame instanceof PingWebSocketFrame ping) {
            ctx.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
            return;
        }
        if (frame instanceof CloseWebSocketFrame) {
            ctx.close();
            return;
        }
        if (!(frame instanceof TextWebSocketFrame text)) {
            sendError(ctx, "Only text frames are supported");
            return;
        }
        JsonObject msg;
        try {
            msg = JsonParser.parseString(text.text()).getAsJsonObject();
        } catch (Exception e) {
            sendError(ctx, "Invalid JSON");
            return;
        }
        String type = msg.has("type") ? msg.get("type").getAsString() : "";
        try {
            switch (type) {
                case "auth" -> handleAuth(ctx, session, msg);
                case "chat" -> handleChat(ctx, session, msg);
                case "bind" -> handleBind(ctx, session, msg);
                case "unbind" -> handleUnbind(ctx, session);
                case "ping" -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("type", "pong");
                    o.addProperty("ts", System.currentTimeMillis());
                    bridge.sendTo(ctx.channel(), o);
                }
                case "history" -> handleHistory(ctx, session);
                default -> sendError(ctx, "Unknown type: " + type);
            }
        } catch (Exception e) {
            OnlineChat.LOGGER.error("[OnlineChat] WebSocket handler failure", e);
            sendError(ctx, "Internal error");
        }
    }

    private void handleAuth(ChannelHandlerContext ctx, WebSessionManager.Session session, JsonObject msg) {
        String token = msg.has("token") ? msg.get("token").getAsString() : null;
        tryAuthenticate(ctx, session, token, false);
    }

    private void handleChat(ChannelHandlerContext ctx, WebSessionManager.Session session, JsonObject msg) {
        if (!session.isAuthenticated()) { sendError(ctx, "Authenticate first"); return; }
        String text = msg.has("text") ? msg.get("text").getAsString() : "";
        bridge.handleWebChat(session, text);
    }

    private void handleBind(ChannelHandlerContext ctx, WebSessionManager.Session session, JsonObject msg) {
        if (!session.isAuthenticated()) { sendError(ctx, "Authenticate first"); return; }
        String mcName = msg.has("mcName") ? msg.get("mcName").getAsString() : null;
        if (mcName == null || mcName.isBlank()) { sendError(ctx, "mcName required"); return; }
        var srv = bridge.server();
        if (srv == null) { sendError(ctx, "Server not ready"); return; }
        Optional<BindingManager.PendingBind> pb = bindings.request(srv, session.username, mcName);
        JsonObject o = new JsonObject();
        if (pb.isEmpty()) {
            o.addProperty("type", "bind_error");
            o.addProperty("error", "Player not online, unknown, or already bound");
        } else {
            o.addProperty("type", "bind_pending");
            o.addProperty("code", pb.get().code);
            o.addProperty("mcName", pb.get().playerName);
            o.addProperty("expiresAt", pb.get().expiresAt);
        }
        bridge.sendTo(ctx.channel(), o);
    }

    private void handleUnbind(ChannelHandlerContext ctx, WebSessionManager.Session session) {
        if (!session.isAuthenticated()) { sendError(ctx, "Authenticate first"); return; }
        Account acc = accounts.byUsername(session.username).orElse(null);
        if (acc == null || !acc.isBound()) { sendError(ctx, "Not bound"); return; }
        accounts.unbind(acc);
        session.boundPlayerUuid = null;
        JsonObject o = new JsonObject();
        o.addProperty("type", "unbind_ok");
        bridge.sendTo(ctx.channel(), o);
    }

    private void handleHistory(ChannelHandlerContext ctx, WebSessionManager.Session session) {
        if (!session.isAuthenticated()) { sendError(ctx, "Authenticate first"); return; }
        JsonObject o = new JsonObject();
        o.addProperty("type", "history");
        var arr = new com.google.gson.JsonArray();
        bridge.historySnapshot().forEach(arr::add);
        o.add("messages", arr);
        bridge.sendTo(ctx.channel(), o);
    }

    private void sendError(ChannelHandlerContext ctx, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "error");
        o.addProperty("error", message);
        ctx.writeAndFlush(new TextWebSocketFrame(GSON.toJson(o)));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        OnlineChat.LOGGER.warn("[OnlineChat] WebSocket error on {}: {}", ctx.channel().remoteAddress(), cause.toString());
        ctx.close();
    }
}
