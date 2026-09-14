package net.mcless.dev.onlinechat.web;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import net.mcless.dev.onlinechat.OnlineChat;
import net.mcless.dev.onlinechat.account.Account;
import net.mcless.dev.onlinechat.account.AccountManager;
import net.mcless.dev.onlinechat.account.PasswordHasher;
import net.mcless.dev.onlinechat.account.TokenService;
import net.mcless.dev.onlinechat.bridge.BindingManager;
import net.mcless.dev.onlinechat.bridge.ChatBridge;
import net.mcless.dev.onlinechat.bridge.WebSessionManager;
import net.mcless.dev.onlinechat.config.ServerConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles plain HTTP requests on the same Netty pipeline as the WebSocket:
 * <ul>
 *   <li>{@code /ws} — upgrades to WebSocket, hands the channel to {@link WebSocketFrameHandler}</li>
 *   <li>{@code /api/*} — small JSON REST surface</li>
 *   <li>{@code /} and everything else — serves files bundled under {@code /web/} in the mod jar</li>
 * </ul>
 */
public class HttpApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Gson GSON = new Gson();
    /** Name of the auth cookie. Value is the same HMAC token that would go in the Authorization header. */
    public static final String COOKIE_NAME = "oc_token";

    private final AccountManager accounts;
    private final TokenService tokens;
    private final BindingManager bindings;
    private final ChatBridge bridge;
    private final WebSessionManager sessions;
    private final LoginRateLimiter rateLimiter = new LoginRateLimiter();
    private final Map<String, byte[]> staticCache = new ConcurrentHashMap<>();

    public HttpApiHandler(AccountManager accounts, TokenService tokens, BindingManager bindings,
                          ChatBridge bridge, WebSessionManager sessions) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.bindings = bindings;
        this.bridge = bridge;
        this.sessions = sessions;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        QueryStringDecoder qs = new QueryStringDecoder(req.uri());
        String path = qs.path();

        applyCors(req, ctx);

        if (HttpMethod.OPTIONS.equals(req.method())) {
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
            resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            write(ctx, req, resp);
            return;
        }

        if ("/ws".equals(path)) {
            handshakeWebSocket(ctx, req);
            return;
        }

        if (path.startsWith("/api/")) {
            handleApi(ctx, req, path, qs);
            return;
        }

        handleStatic(ctx, req, path);
    }

    // ─────────────────────────── WebSocket ───────────────────────────

    private void handshakeWebSocket(ChannelHandlerContext ctx, FullHttpRequest req) {
        String scheme = isSecure(ctx) ? "wss://" : "ws://";
        String wsUrl = scheme + req.headers().get(HttpHeaderNames.HOST, "localhost") + "/ws";
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                wsUrl, null, true, 64 * 1024);
        WebSocketServerHandshaker handshaker = factory.newHandshaker(req);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            return;
        }
        String remote = ctx.channel().remoteAddress() == null ? "unknown" : ctx.channel().remoteAddress().toString();
        WebSessionManager.Session session = sessions.register(ctx.channel(), remote);
        // Browsers cannot set custom headers on a WebSocket handshake, so pick up the token from
        // the Cookie or Authorization header of the upgrade request and let the frame handler
        // authenticate as soon as the channel goes live.
        session.handshakeToken = extractToken(req);
        ctx.channel().attr(WebSocketFrameHandler.SESSION_KEY).set(session);
        handshaker.handshake(ctx.channel(), req);
        if (ServerConfig.VERBOSE_LOGGING.get()) {
            OnlineChat.LOGGER.info("[OnlineChat] WebSocket handshake from {}", remote);
        }
    }

    // ─────────────────────────── REST ───────────────────────────

    private void handleApi(ChannelHandlerContext ctx, FullHttpRequest req, String path, QueryStringDecoder qs) {
        try {
            switch (path) {
                case "/api/status" -> handleStatus(ctx, req);
                case "/api/register" -> handleRegister(ctx, req);
                case "/api/login" -> handleLogin(ctx, req);
                case "/api/logout" -> handleLogout(ctx, req);
                case "/api/me" -> handleMe(ctx, req);
                case "/api/bind" -> handleBind(ctx, req);
                case "/api/unbind" -> handleUnbind(ctx, req);
                case "/api/history" -> handleHistory(ctx, req);
                case "/api/online" -> handleOnline(ctx, req);
                default -> sendJson(ctx, req, HttpResponseStatus.NOT_FOUND, error("Not found"));
            }
        } catch (Exception e) {
            OnlineChat.LOGGER.error("[OnlineChat] API failure on {}", path, e);
            sendJson(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("Internal error"));
        }
    }

    private void handleStatus(ChannelHandlerContext ctx, FullHttpRequest req) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("service", "onlinechat");
        o.addProperty("registration", ServerConfig.ALLOW_REGISTRATION.get());
        o.addProperty("onlineWeb", sessions.onlineCount());
        var srv = bridge.server();
        o.addProperty("onlinePlayers", srv == null ? 0 : srv.getPlayerCount());
        o.addProperty("maxPlayers", srv == null ? 0 : srv.getMaxPlayers());
        o.addProperty("motd", srv == null ? "" : stripNulls(srv.getMotd()));
        JsonObject style = new JsonObject();
        style.addProperty("inGamePrefixText", net.mcless.dev.onlinechat.config.CommonConfig.IN_GAME_PREFIX_TEXT.get());
        style.addProperty("inGamePrefixColor", net.mcless.dev.onlinechat.config.CommonConfig.IN_GAME_PREFIX_COLOR.get());
        style.addProperty("webPrefixText", net.mcless.dev.onlinechat.config.CommonConfig.WEB_PREFIX_TEXT.get());
        style.addProperty("webPrefixColor", net.mcless.dev.onlinechat.config.CommonConfig.WEB_PREFIX_COLOR_CSS.get());
        style.addProperty("maxMessageLength", net.mcless.dev.onlinechat.config.CommonConfig.MAX_WEB_MESSAGE_LENGTH.get());
        style.addProperty("minPasswordLength", ServerConfig.MIN_PASSWORD_LENGTH.get());
        o.add("style", style);
        sendJson(ctx, req, HttpResponseStatus.OK, o);
    }

    private void handleRegister(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!ServerConfig.ALLOW_REGISTRATION.get()) {
            sendJson(ctx, req, HttpResponseStatus.FORBIDDEN, error("Registration is disabled"));
            return;
        }
        if (!HttpMethod.POST.equals(req.method())) {
            sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("POST required"));
            return;
        }
        JsonObject body = readJson(req);
        if (body == null) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Invalid JSON")); return; }
        String username = str(body, "username");
        String password = str(body, "password");
        if (username == null || password == null) {
            sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("username and password are required"));
            return;
        }
        if (!AccountManager.USERNAME_PATTERN.matcher(username).matches()) {
            sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Username must be 3-32 chars of [A-Za-z0-9_]"));
            return;
        }
        if (password.length() < ServerConfig.MIN_PASSWORD_LENGTH.get()) {
            sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Password too short"));
            return;
        }
        if (accounts.exists(username)) {
            sendJson(ctx, req, HttpResponseStatus.CONFLICT, error("Username already exists"));
            return;
        }
        Account created = accounts.register(username, password);
        String token = tokens.issue(created.getUsername());
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("token", token);
        o.addProperty("username", created.getUsername());
        sendJsonWithCookie(ctx, req, HttpResponseStatus.OK, o, token);
    }

    private void handleLogin(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!HttpMethod.POST.equals(req.method())) {
            sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("POST required"));
            return;
        }
        String remoteIp = ctx.channel().remoteAddress() == null ? "?" : ctx.channel().remoteAddress().toString();
        if (!rateLimiter.allow(remoteIp)) {
            sendJson(ctx, req, HttpResponseStatus.TOO_MANY_REQUESTS, error("Too many login attempts, try again later"));
            return;
        }
        JsonObject body = readJson(req);
        if (body == null) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Invalid JSON")); return; }
        String username = str(body, "username");
        String password = str(body, "password");
        Optional<Account> opt = username == null ? Optional.empty() : accounts.byUsername(username);
        if (opt.isEmpty() || password == null || !PasswordHasher.verify(password, opt.get())) {
            rateLimiter.recordFailure(remoteIp);
            sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Invalid credentials"));
            return;
        }
        rateLimiter.recordSuccess(remoteIp);
        Account acc = opt.get();
        accounts.touchLogin(acc);
        String token = tokens.issue(acc.getUsername());
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("token", token);
        o.addProperty("username", acc.getUsername());
        o.addProperty("bound", acc.isBound());
        if (acc.isBound()) {
            o.addProperty("mcName", acc.getBoundPlayerName());
            o.addProperty("mcUuid", acc.getBoundPlayerUuid().toString());
        }
        sendJsonWithCookie(ctx, req, HttpResponseStatus.OK, o, token);
    }

    private void handleLogout(ChannelHandlerContext ctx, FullHttpRequest req) {
        // Best-effort: also drop any live WebSocket sessions belonging to this token so the
        // browser does not keep receiving pushes after the user signs out.
        Optional<Account> opt = auth(req);
        opt.ifPresent(acc -> {
            for (WebSessionManager.Session s : sessions.byUsername(acc.getUsername())) {
                if (s.channel.isActive()) s.channel.close();
            }
        });
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        sendJsonWithCookie(ctx, req, HttpResponseStatus.OK, o, null);
    }

    private void handleMe(ChannelHandlerContext ctx, FullHttpRequest req) {
        Optional<Account> opt = auth(req);
        if (opt.isEmpty()) { sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Unauthorized")); return; }
        Account acc = opt.get();
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("username", acc.getUsername());
        o.addProperty("bound", acc.isBound());
        if (acc.isBound()) {
            o.addProperty("mcName", acc.getBoundPlayerName());
            o.addProperty("mcUuid", acc.getBoundPlayerUuid().toString());
            var srv = bridge.server();
            boolean online = srv != null && srv.getPlayerList().getPlayer(acc.getBoundPlayerUuid()) != null;
            o.addProperty("mcOnline", online);
        }
        o.addProperty("createdAt", acc.getCreatedAt());
        o.addProperty("lastLoginAt", acc.getLastLoginAt());
        sendJson(ctx, req, HttpResponseStatus.OK, o);
    }

    private void handleBind(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!HttpMethod.POST.equals(req.method())) { sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("POST required")); return; }
        Optional<Account> opt = auth(req);
        if (opt.isEmpty()) { sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Unauthorized")); return; }
        Account acc = opt.get();
        JsonObject body = readJson(req);
        if (body == null) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Invalid JSON")); return; }
        String mcName = str(body, "mcName");
        if (mcName == null || mcName.isBlank()) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("mcName required")); return; }
        if (acc.isBound() && !ServerConfig.ALLOW_REBIND.get()) {
            sendJson(ctx, req, HttpResponseStatus.FORBIDDEN, error("Rebinding is disabled"));
            return;
        }
        var srv = bridge.server();
        if (srv == null) { sendJson(ctx, req, HttpResponseStatus.SERVICE_UNAVAILABLE, error("Server not ready")); return; }
        Optional<BindingManager.PendingBind> pb = bindings.request(srv, acc.getUsername(), mcName);
        if (pb.isEmpty()) {
            JsonObject o = error("Player not online, already bound, or unknown");
            o.addProperty("hint", "The Minecraft player must be online and click [Yes] on the confirmation prompt.");
            sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, o);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("code", pb.get().code);
        o.addProperty("expiresAt", pb.get().expiresAt);
        o.addProperty("mcName", pb.get().playerName);
        sendJson(ctx, req, HttpResponseStatus.OK, o);
    }

    private void handleUnbind(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!HttpMethod.POST.equals(req.method())) { sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("POST required")); return; }
        Optional<Account> opt = auth(req);
        if (opt.isEmpty()) { sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Unauthorized")); return; }
        Account acc = opt.get();
        if (!acc.isBound()) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Not bound")); return; }
        accounts.unbind(acc);
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        sendJson(ctx, req, HttpResponseStatus.OK, o);
    }

    private void handleHistory(ChannelHandlerContext ctx, FullHttpRequest req) {
        Optional<Account> opt = auth(req);
        if (opt.isEmpty()) { sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Unauthorized")); return; }
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        var arr = new com.google.gson.JsonArray();
        bridge.historySnapshot().forEach(arr::add);
        o.add("messages", arr);
        sendJson(ctx, req, HttpResponseStatus.OK, o);
    }

    private void handleOnline(ChannelHandlerContext ctx, FullHttpRequest req) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        var arr = new com.google.gson.JsonArray();
        var srv = bridge.server();
        if (srv != null) {
            srv.getPlayerList().getPlayers().forEach(p -> {
                JsonObject po = new JsonObject();
                po.addProperty("name", p.getGameProfile().getName());
                po.addProperty("uuid", p.getUUID().toString());
                arr.add(po);
            });
        }
        o.add("players", arr);
        o.addProperty("webOnline", sessions.onlineCount());
        sendJson(ctx, req, HttpResponseStatus.OK, o);
    }

    // ─────────────────────────── Static ───────────────────────────

    private void handleStatic(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        String resource = path.equals("/") ? "/web/index.html" : "/web" + path;
        // Prevent path traversal
        if (resource.contains("..")) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Bad path")); return; }
        byte[] data = staticCache.computeIfAbsent(resource, HttpApiHandler::loadResource);
        if (data == null) {
            // Fallback to index.html so SPA-style routing works.
            data = staticCache.computeIfAbsent("/web/index.html", HttpApiHandler::loadResource);
            if (data == null) {
                sendJson(ctx, req, HttpResponseStatus.NOT_FOUND, error("Not found"));
                return;
            }
            resource = "/web/index.html";
        }
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(data));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType(resource));
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
        resp.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        write(ctx, req, resp);
    }

    private static byte[] loadResource(String resource) {
        try (InputStream in = HttpApiHandler.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    // ─────────────────────────── Helpers ───────────────────────────

    private Optional<Account> auth(FullHttpRequest req) {
        String token = extractToken(req);
        return tokens.validate(token).flatMap(accounts::byUsername);
    }

    /**
     * Pulls the auth token from (in order of precedence) the {@code Authorization: Bearer} header,
     * the {@code X-Auth-Token} header, or the {@value #COOKIE_NAME} cookie.
     */
    private String extractToken(FullHttpRequest req) {
        String header = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String t = header.substring(7).trim();
            if (!t.isEmpty()) return t;
        }
        String x = req.headers().get("X-Auth-Token");
        if (x != null && !x.isBlank()) return x.trim();
        String cookieHeader = req.headers().get(HttpHeaderNames.COOKIE);
        if (cookieHeader != null) {
            for (String part : cookieHeader.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(COOKIE_NAME + "=")) {
                    String value = trimmed.substring(COOKIE_NAME.length() + 1);
                    if (!value.isEmpty()) return value;
                }
            }
        }
        return null;
    }

    /** Builds a {@code Set-Cookie} header value for the auth token (or clears it when token is null). */
    private static String buildAuthCookieHeader(String token, boolean secure) {
        long maxAge = token == null ? 0L : ServerConfig.TOKEN_TTL_MINUTES.get() * 60L;
        String value = token == null ? "" : token;
        StringBuilder sb = new StringBuilder(COOKIE_NAME).append('=').append(value)
                .append("; Path=/; HttpOnly; SameSite=Lax");
        // Only mark the cookie Secure when the request actually arrived over TLS; otherwise a
        // plain-HTTP browser would refuse to store/send it and login would silently fail.
        if (secure) sb.append("; Secure");
        sb.append("; Max-Age=").append(maxAge);
        return sb.toString();
    }

    /** True when this request arrived over TLS (the pipeline has the {@code "ssl"} handler installed). */
    private static boolean isSecure(ChannelHandlerContext ctx) {
        return ctx != null && ctx.pipeline().get("ssl") != null;
    }

    private void applyCors(FullHttpRequest req, ChannelHandlerContext ctx) {
        // Actual CORS headers are applied per-response in write(); this hook exists for future pre-processing.
    }

    private void write(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse resp) {
        applyCorsHeaders(req, resp);
        boolean keepAlive = HttpUtil.isKeepAlive(req);
        if (keepAlive) {
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(resp);
        } else {
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void applyCorsHeaders(FullHttpRequest req, FullHttpResponse resp) {
        List<String> allowed = ServerConfig.ALLOWED_ORIGINS.get().stream().map(Object::toString).toList();
        String origin = req.headers().get(HttpHeaderNames.ORIGIN);
        String allow;
        if (allowed.contains("*")) allow = origin == null ? "*" : origin;
        else if (origin != null && allowed.contains(origin)) allow = origin;
        else allow = null;
        if (allow != null) {
            resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, allow);
            resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            resp.headers().set(HttpHeaderNames.VARY, HttpHeaderNames.ORIGIN);
        }
        resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
        resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization, X-Auth-Token");
        resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "600");
    }

    private void sendJson(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus status, JsonObject body) {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        write(ctx, req, resp);
    }

    /** Same as {@link #sendJson} but attaches an auth cookie (or clears it when {@code token} is null). */
    private void sendJsonWithCookie(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus status,
                                    JsonObject body, String token) {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        resp.headers().add(HttpHeaderNames.SET_COOKIE, buildAuthCookieHeader(token, isSecure(ctx)));
        write(ctx, req, resp);
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        return o;
    }

    private static JsonObject readJson(FullHttpRequest req) {
        try {
            ByteBuf content = req.content();
            byte[] bytes = new byte[content.readableBytes()];
            content.getBytes(content.readerIndex(), bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.isBlank()) return new JsonObject();
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static String stripNulls(String s) { return s == null ? "" : s.replace("\u0000", ""); }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        OnlineChat.LOGGER.warn("[OnlineChat] HTTP handler error on {}: {}", ctx.channel().remoteAddress(), cause.toString());
        if (ctx.channel().isActive()) {
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Unpooled.copiedBuffer("{\"ok\":false,\"error\":\"internal\"}", StandardCharsets.UTF_8));
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        }
    }

    // ─────────────────────────── Rate limiting ───────────────────────────

    private static final class LoginRateLimiter {
        private static final class Bucket { int failures; long windowStart; }
        private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

        boolean allow(String ip) {
            int max = ServerConfig.MAX_LOGIN_ATTEMPTS.get();
            if (max <= 0) return true;
            long window = ServerConfig.LOGIN_COOLDOWN_SECONDS.get() * 1000L;
            long now = System.currentTimeMillis();
            Bucket b = buckets.computeIfAbsent(ip, k -> new Bucket());
            synchronized (b) {
                if (now - b.windowStart > window) { b.windowStart = now; b.failures = 0; }
                return b.failures < max;
            }
        }

        void recordFailure(String ip) {
            Bucket b = buckets.computeIfAbsent(ip, k -> new Bucket());
            synchronized (b) {
                long now = System.currentTimeMillis();
                long window = ServerConfig.LOGIN_COOLDOWN_SECONDS.get() * 1000L;
                if (now - b.windowStart > window) { b.windowStart = now; b.failures = 0; }
                b.failures++;
            }
        }

        void recordSuccess(String ip) {
            Bucket b = buckets.get(ip);
            if (b != null) synchronized (b) { b.failures = 0; b.windowStart = System.currentTimeMillis(); }
        }
    }
}
