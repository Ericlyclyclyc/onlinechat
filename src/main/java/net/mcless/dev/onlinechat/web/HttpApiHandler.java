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
import net.mcless.dev.onlinechat.auth.TwoFactorGuard;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Handles plain HTTP requests on the same Netty pipeline as the WebSocket:
 * <ul>
 *   <li>{@code /ws} — upgrades to WebSocket, hands the channel to {@link WebSocketFrameHandler}</li>
 *   <li>{@code /api/*} — small JSON REST surface</li>
 *   <li>{@code /2fa/auth/<token>} — the two-factor verification page (serves {@code 2fa.html})</li>
 *   <li>{@code /} and everything else — serves the web front-end, preferring the extracted copy on disk
 *       ({@link WebAssets}) and falling back to the files bundled under {@code /web/} in the mod jar</li>
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
    private final TwoFactorGuard twoFactor;
    private final WebAssets webAssets;
    private final ExecutorService blockingPool;
    private final Map<String, byte[]> staticCache = new ConcurrentHashMap<>();

    /**
     * Login/register limits are shared across every connection. Each channel gets its own handler
     * instance, so these MUST be static — otherwise an attacker could bypass them simply by opening
     * a fresh TCP connection per attempt.
     */
    private static final LoginRateLimiter LOGIN_LIMITER = new LoginRateLimiter();
    private static final RateLimiter REGISTER_LIMITER = new RateLimiter(3_600_000L); // 1-hour window
    /** Full-archive search is disk I/O; cap it per IP even though it requires auth. */
    private static final RateLimiter SEARCH_LIMITER = new RateLimiter(60_000L);      // 30 searches/min/IP

    public HttpApiHandler(AccountManager accounts, TokenService tokens, BindingManager bindings,
                          ChatBridge bridge, WebSessionManager sessions, TwoFactorGuard twoFactor,
                          WebAssets webAssets, ExecutorService blockingPool) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.bindings = bindings;
        this.bridge = bridge;
        this.sessions = sessions;
        this.twoFactor = twoFactor;
        this.webAssets = webAssets;
        this.blockingPool = blockingPool;
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

        // The verification link shown in game; the token stays in the URL for 2fa.js to pick up.
        if (path.startsWith("/2fa/auth/")) {
            handleStatic(ctx, req, "/2fa.html");
            return;
        }

        // The old binding page now lives inside the account page.
        if ("/bind.html".equals(path)) {
            redirect(ctx, req, "/account.html");
            return;
        }

        handleStatic(ctx, req, path);
    }

    // ─────────────────────────── WebSocket ───────────────────────────

    private void handshakeWebSocket(ChannelHandlerContext ctx, FullHttpRequest req) {
        // Reject cross-site WebSocket handshakes (CSWSH) using the same allow-list as CORS.
        String origin = req.headers().get(HttpHeaderNames.ORIGIN);
        if (!isOriginAllowed(origin)) {
            rejectHandshake(ctx, HttpResponseStatus.FORBIDDEN, "Origin not allowed");
            return;
        }
        String remote = ctx.channel().remoteAddress() == null ? "unknown" : ctx.channel().remoteAddress().toString();
        String ip = WebSessionManager.ipOf(remote);
        int maxTotal = ServerConfig.MAX_CONNECTIONS_TOTAL.get();
        int maxPerIp = ServerConfig.MAX_CONNECTIONS_PER_IP.get();
        if (maxTotal > 0 && sessions.totalConnections() >= maxTotal) {
            rejectHandshake(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "Server connection limit reached");
            return;
        }
        if (maxPerIp > 0 && sessions.connectionsFromIp(ip) >= maxPerIp) {
            rejectHandshake(ctx, HttpResponseStatus.TOO_MANY_REQUESTS, "Too many connections from your IP");
            return;
        }

        String scheme = isSecure(ctx) ? "wss://" : "ws://";
        String wsUrl = scheme + req.headers().get(HttpHeaderNames.HOST, "localhost") + "/ws";
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                wsUrl, null, true, 64 * 1024);
        WebSocketServerHandshaker handshaker = factory.newHandshaker(req);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            return;
        }
        WebSessionManager.Session session = sessions.register(ctx.channel(), remote);
        // Browsers cannot set custom headers on a WebSocket handshake, so pick up the token from
        // the Cookie or Authorization header of the upgrade request and let the frame handler
        // authenticate as soon as the channel goes live.
        session.handshakeToken = extractToken(req);
        ctx.channel().attr(WebSocketFrameHandler.SESSION_KEY).set(session);
        // WebSocketFrameHandler.handlerAdded already fired at pipeline-init time (before this session
        // existed), so its cookie auto-auth never ran. Kick it off now that the upgrade succeeded and
        // the session attribute is set - otherwise every frame returns "Authenticate first" and no
        // in-game chat is ever delivered to this client.
        handshaker.handshake(ctx.channel(), req).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) return;
            WebSocketFrameHandler wsHandler = ctx.pipeline().get(WebSocketFrameHandler.class);
            if (wsHandler != null) wsHandler.onHandshakeComplete(ctx);
        });
        if (ServerConfig.VERBOSE_LOGGING.get()) {
            OnlineChat.LOGGER.info("[OnlineChat] WebSocket handshake from {}", remote);
        }
    }

    /** Sends a plain HTTP error for a rejected WebSocket upgrade and closes the channel. */
    private void rejectHandshake(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        byte[] bytes = GSON.toJson(error(message)).getBytes(StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        if (ServerConfig.VERBOSE_LOGGING.get()) {
            OnlineChat.LOGGER.info("[OnlineChat] Rejected WebSocket handshake from {}: {}", ctx.channel().remoteAddress(), message);
        }
    }

    /**
     * WebSocket {@code Origin} allow-list, kept consistent with {@link #applyCorsHeaders}. Browsers always
     * send {@code Origin} on a WebSocket upgrade, so a missing header means a non-browser client (which
     * cannot mount a cross-site attack) and is permitted.
     */
    private static boolean isOriginAllowed(String origin) {
        if (origin == null || origin.isBlank()) return true;
        List<String> allowed = ServerConfig.ALLOWED_ORIGINS.get().stream().map(Object::toString).toList();
        return allowed.contains("*") || allowed.contains(origin);
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
                case "/api/history" -> handleHistory(ctx, req, qs);
                case "/api/search" -> handleSearch(ctx, req, qs);
                case "/api/webusers" -> handleWebUsers(ctx, req);
                case "/api/online" -> handleOnline(ctx, req);
                case "/api/account/password" -> handleChangePassword(ctx, req);
                case "/api/account/delete" -> handleDeleteAccount(ctx, req);
                case "/api/2fa/toggle" -> handleTwoFactorToggle(ctx, req);
                case "/api/2fa/info" -> handleTwoFactorInfo(ctx, req, qs);
                case "/api/2fa/verify" -> handleTwoFactorVerify(ctx, req);
                case "/api/2fa/reject" -> handleTwoFactorReject(ctx, req);
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
        o.addProperty("twoFactor", TwoFactorGuard.featureEnabled());
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
        String registerIp = WebSessionManager.ipOf(ctx.channel().remoteAddress() == null ? "?" : ctx.channel().remoteAddress().toString());
        if (!REGISTER_LIMITER.allow(registerIp, ServerConfig.REGISTER_ATTEMPTS_PER_HOUR.get())) {
            sendJson(ctx, req, HttpResponseStatus.TOO_MANY_REQUESTS, error("Too many accounts created from your IP, try again later"));
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
        // PBKDF2 hashing + the JSON flush run off the Netty EventLoop. retain()/release() keeps the
        // request alive for the worker thread, since SimpleChannelInboundHandler frees it on return.
        req.retain();
        blockingPool.execute(() -> {
            try {
                // Re-check inside the worker: a concurrent request may have claimed the name meanwhile.
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
            } catch (Exception e) {
                OnlineChat.LOGGER.error("[OnlineChat] Registration failure", e);
                sendJson(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("Internal error"));
            } finally {
                req.release();
            }
        });
    }

    private void handleLogin(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!HttpMethod.POST.equals(req.method())) {
            sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("POST required"));
            return;
        }
        String remoteIp = ctx.channel().remoteAddress() == null ? "?" : ctx.channel().remoteAddress().toString();
        if (!LOGIN_LIMITER.allow(remoteIp)) {
            sendJson(ctx, req, HttpResponseStatus.TOO_MANY_REQUESTS, error("Too many login attempts, try again later"));
            return;
        }
        JsonObject body = readJson(req);
        if (body == null) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Invalid JSON")); return; }
        String username = str(body, "username");
        String password = str(body, "password");
        Optional<Account> opt = username == null ? Optional.empty() : accounts.byUsername(username);
        if (opt.isEmpty() || password == null) {
            LOGIN_LIMITER.recordFailure(remoteIp);
            sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Invalid credentials"));
            return;
        }
        Account acc = opt.get();
        // PBKDF2 verification (hundreds of thousands of iterations) must not run on the Netty EventLoop,
        // or it stalls every other socket pinned to that loop thread. Offload it; retain()/release() keeps
        // the request valid for the worker thread.
        req.retain();
        blockingPool.execute(() -> {
            try {
                if (!PasswordHasher.verify(password, acc)) {
                    LOGIN_LIMITER.recordFailure(remoteIp);
                    sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Invalid credentials"));
                    return;
                }
                LOGIN_LIMITER.recordSuccess(remoteIp);
                accounts.touchLogin(acc);   // bumps lastLoginAt -> supersedes every previously issued token
                // Single active web session: kick any other device currently signed in as this account.
                // Their old token is now invalid (TokenService.validate) and this closes their live socket
                // with a force_logout frame so the browser can explain why it was signed out.
                bridge.forceLogout(acc.getUsername(), "login_elsewhere");
                String token = tokens.issue(acc.getUsername());
                OnlineChat.LOGGER.debug("[OnlineChat] Web login: {} from {}", acc.getUsername(), WebSessionManager.ipOf(remoteIp));
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
            } catch (Exception e) {
                OnlineChat.LOGGER.error("[OnlineChat] Login failure", e);
                sendJson(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("Internal error"));
            } finally {
                req.release();
            }
        });
    }

    private void handleLogout(ChannelHandlerContext ctx, FullHttpRequest req) {
        // Best-effort: also drop any live WebSocket sessions belonging to this token so the
        // browser does not keep receiving pushes after the user signs out.
        Optional<Account> opt = auth(req);
        opt.ifPresent(acc -> {
            String ip = WebSessionManager.ipOf(ctx.channel().remoteAddress() == null ? "?" : ctx.channel().remoteAddress().toString());
            OnlineChat.LOGGER.debug("[OnlineChat] Web logout: {} from {}", acc.getUsername(), ip);
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
        o.addProperty("twoFactorAvailable", TwoFactorGuard.featureEnabled());
        o.addProperty("twoFactor", acc.isTwoFactorEnabled());
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
        OnlineChat runtime = OnlineChat.instance();
        if (runtime != null) runtime.unbindAccount(acc); else accounts.unbind(acc);
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        sendJson(ctx, req, HttpResponseStatus.OK, o);
    }

    // ─────────────────────────── Account self-service ───────────────────────────

    /** {@code POST /api/account/password {current, next}} — re-authenticates, then rotates the password. */
    private void handleChangePassword(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!HttpMethod.POST.equals(req.method())) { sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("POST required")); return; }
        Optional<Account> opt = auth(req);
        if (opt.isEmpty()) { sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Unauthorized")); return; }
        Account acc = opt.get();
        JsonObject body = readJson(req);
        if (body == null) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Invalid JSON")); return; }
        String current = str(body, "current");
        String next = str(body, "next");
        if (current == null || next == null) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("current and next are required")); return; }
        if (next.length() < ServerConfig.MIN_PASSWORD_LENGTH.get()) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Password too short")); return; }
        OnlineChat runtime = OnlineChat.instance();
        if (runtime == null) { sendJson(ctx, req, HttpResponseStatus.SERVICE_UNAVAILABLE, error("Server not ready")); return; }
        req.retain();
        blockingPool.execute(() -> {
            try {
                if (!PasswordHasher.verify(current, acc)) {
                    sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Current password is incorrect"));
                    return;
                }
                // Rotating the password signs out every device (tokens predate the new lastLoginAt), so
                // hand this browser a fresh token right away instead of bouncing it to the login page.
                runtime.changePassword(acc, next);
                String token = tokens.issue(acc.getUsername());
                JsonObject o = new JsonObject();
                o.addProperty("ok", true);
                o.addProperty("token", token);
                sendJsonWithCookie(ctx, req, HttpResponseStatus.OK, o, token);
            } catch (Exception e) {
                OnlineChat.LOGGER.error("[OnlineChat] Password change failure", e);
                sendJson(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("Internal error"));
            } finally {
                req.release();
            }
        });
    }

    /** {@code POST /api/account/delete {password}} — deletes the account, unbinding its player and clearing the cookie. */
    private void handleDeleteAccount(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!HttpMethod.POST.equals(req.method())) { sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("POST required")); return; }
        Optional<Account> opt = auth(req);
        if (opt.isEmpty()) { sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Unauthorized")); return; }
        Account acc = opt.get();
        JsonObject body = readJson(req);
        if (body == null) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Invalid JSON")); return; }
        String password = str(body, "password");
        if (password == null) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("password is required")); return; }
        OnlineChat runtime = OnlineChat.instance();
        if (runtime == null) { sendJson(ctx, req, HttpResponseStatus.SERVICE_UNAVAILABLE, error("Server not ready")); return; }
        req.retain();
        blockingPool.execute(() -> {
            try {
                if (!PasswordHasher.verify(password, acc)) {
                    sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Password is incorrect"));
                    return;
                }
                runtime.deleteAccount(acc);
                JsonObject o = new JsonObject();
                o.addProperty("ok", true);
                sendJsonWithCookie(ctx, req, HttpResponseStatus.OK, o, null);
            } catch (Exception e) {
                OnlineChat.LOGGER.error("[OnlineChat] Account deletion failure", e);
                sendJson(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("Internal error"));
            } finally {
                req.release();
            }
        });
    }

    // ─────────────────────────── Two-factor ───────────────────────────

    /** {@code POST /api/2fa/toggle {enabled}} — player-side opt in/out; requires a bound account. */
    private void handleTwoFactorToggle(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!HttpMethod.POST.equals(req.method())) { sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("POST required")); return; }
        Optional<Account> opt = auth(req);
        if (opt.isEmpty()) { sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Unauthorized")); return; }
        if (!TwoFactorGuard.featureEnabled()) { sendJson(ctx, req, HttpResponseStatus.FORBIDDEN, error("Two-factor authentication is disabled on this server")); return; }
        Account acc = opt.get();
        JsonObject body = readJson(req);
        if (body == null) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Invalid JSON")); return; }
        boolean enabled = body.has("enabled") && !body.get("enabled").isJsonNull() && body.get("enabled").getAsBoolean();
        if (enabled && !acc.isBound()) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Bind a Minecraft player first")); return; }
        accounts.setTwoFactor(acc, enabled);
        OnlineChat.LOGGER.info("[OnlineChat] Web user '{}' turned 2FA {}", acc.getUsername(), enabled ? "on" : "off");
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("twoFactor", acc.isTwoFactorEnabled());
        sendJson(ctx, req, HttpResponseStatus.OK, o);
    }

    /**
     * {@code GET /api/2fa/info?token=} — what the verification page needs to render: whether the token is
     * live, which player is waiting, and whether the browser's current sign-in is the matching account.
     */
    private void handleTwoFactorInfo(ChannelHandlerContext ctx, FullHttpRequest req, QueryStringDecoder qs) {
        String token = firstParam(qs, "token");
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("available", TwoFactorGuard.featureEnabled() && twoFactor != null);
        Optional<TwoFactorGuard.Pending> pending = twoFactor == null ? Optional.empty() : twoFactor.pendingByToken(token);
        o.addProperty("valid", pending.isPresent());
        Optional<Account> me = auth(req);
        o.addProperty("authenticated", me.isPresent());
        me.ifPresent(a -> o.addProperty("username", a.getUsername()));
        pending.ifPresent(p -> {
            o.addProperty("playerName", p.playerName);
            o.addProperty("expiresAt", p.deadline);
            o.addProperty("matches", me.isPresent()
                    && p.webUsername.equalsIgnoreCase(me.get().getUsername())
                    && p.playerUuid.equals(me.get().getBoundPlayerUuid()));
        });
        sendJson(ctx, req, HttpResponseStatus.OK, o);
    }

    /** {@code POST /api/2fa/verify {token}} — releases the frozen player when the cookie belongs to the right account. */
    private void handleTwoFactorVerify(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!HttpMethod.POST.equals(req.method())) { sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("POST required")); return; }
        Optional<Account> opt = auth(req);
        if (opt.isEmpty()) { sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Unauthorized")); return; }
        JsonObject body = readJson(req);
        if (body == null) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Invalid JSON")); return; }
        String token = str(body, "token");
        if (twoFactor == null) { sendJson(ctx, req, HttpResponseStatus.SERVICE_UNAVAILABLE, error("Server not ready")); return; }
        TwoFactorGuard.Result result = twoFactor.verify(token, opt.get());
        JsonObject o = new JsonObject();
        o.addProperty("result", result.name().toLowerCase(java.util.Locale.ROOT));
        switch (result) {
            case OK -> { o.addProperty("ok", true); sendJson(ctx, req, HttpResponseStatus.OK, o); }
            case INVALID_TOKEN -> { o.addProperty("ok", false); o.addProperty("error", "Token is invalid or expired"); sendJson(ctx, req, HttpResponseStatus.NOT_FOUND, o); }
            case WRONG_ACCOUNT -> { o.addProperty("ok", false); o.addProperty("error", "This browser is signed in to a different account; the player was kicked"); sendJson(ctx, req, HttpResponseStatus.FORBIDDEN, o); }
            default -> { o.addProperty("ok", false); o.addProperty("error", "Server not ready"); sendJson(ctx, req, HttpResponseStatus.SERVICE_UNAVAILABLE, o); }
        }
    }

    /** {@code POST /api/2fa/reject {token}} — "that's not me": kicks the player waiting behind the token. */
    private void handleTwoFactorReject(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!HttpMethod.POST.equals(req.method())) { sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("POST required")); return; }
        Optional<Account> opt = auth(req);
        if (opt.isEmpty()) { sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Unauthorized")); return; }
        JsonObject body = readJson(req);
        if (body == null) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Invalid JSON")); return; }
        if (twoFactor == null) { sendJson(ctx, req, HttpResponseStatus.SERVICE_UNAVAILABLE, error("Server not ready")); return; }
        boolean done = twoFactor.reject(str(body, "token"), opt.get());
        if (!done) { sendJson(ctx, req, HttpResponseStatus.NOT_FOUND, error("Token is invalid or expired")); return; }
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        sendJson(ctx, req, HttpResponseStatus.OK, o);
    }

    private void handleHistory(ChannelHandlerContext ctx, FullHttpRequest req, QueryStringDecoder qs) {
        Optional<Account> opt = auth(req);
        if (opt.isEmpty()) { sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Unauthorized")); return; }
        // Pagination: ?before=<seq>&limit=<n>. Without `before` we return the newest page.
        String beforeParam = firstParam(qs, "before");
        boolean hasBefore = beforeParam != null;
        long before = parseLongOr(beforeParam, 0L);
        int limit = (int) parseLongOr(firstParam(qs, "limit"), ServerConfig.CHAT_PAGE_SIZE.get());
        // Older pages are read from the on-disk archive, so keep that I/O off the Netty event loop.
        req.retain();
        blockingPool.execute(() -> {
            try {
                var page = hasBefore ? bridge.messagesBefore(before, limit) : bridge.recentPage(limit);
                JsonObject o = new JsonObject();
                o.addProperty("ok", true);
                var arr = new com.google.gson.JsonArray();
                page.messages().forEach(arr::add);
                o.add("messages", arr);
                o.addProperty("hasMore", page.hasMore());
                sendJson(ctx, req, HttpResponseStatus.OK, o);
            } catch (Exception e) {
                OnlineChat.LOGGER.error("[OnlineChat] History query failure", e);
                sendJson(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("Internal error"));
            } finally {
                req.release();
            }
        });
    }

    /**
     * {@code GET /api/search?q=<text>&before=<seq>&limit=<n>} — full-archive chat search.
     * Requires auth. Matches message text and author names case-insensitively, newest first; the
     * {@code before} cursor (a message {@code id}) pages further into older hits.
     */
    private void handleSearch(ChannelHandlerContext ctx, FullHttpRequest req, QueryStringDecoder qs) {
        Optional<Account> opt = auth(req);
        if (opt.isEmpty()) { sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Unauthorized")); return; }
        if (!HttpMethod.GET.equals(req.method())) {
            sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("GET required"));
            return;
        }
        String ip = WebSessionManager.ipOf(ctx.channel().remoteAddress() == null ? "?" : ctx.channel().remoteAddress().toString());
        if (!SEARCH_LIMITER.allow(ip, 30)) {
            sendJson(ctx, req, HttpResponseStatus.TOO_MANY_REQUESTS, error("Search rate limit exceeded, try again later"));
            return;
        }
        String q = firstParam(qs, "q");
        if (q == null || q.isBlank() || q.trim().length() > 64) {
            sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("q is required (1-64 characters)"));
            return;
        }
        long before = parseLongOr(firstParam(qs, "before"), 0L);
        int limit = (int) parseLongOr(firstParam(qs, "limit"), ServerConfig.CHAT_PAGE_SIZE.get());
        // The archive lives on disk: keep the scan off the Netty event loop.
        req.retain();
        blockingPool.execute(() -> {
            try {
                var page = bridge.searchMessages(q, before, limit);
                JsonObject o = new JsonObject();
                o.addProperty("ok", true);
                var arr = new com.google.gson.JsonArray();
                page.messages().forEach(arr::add);
                o.add("messages", arr);
                o.addProperty("hasMore", page.hasMore());
                sendJson(ctx, req, HttpResponseStatus.OK, o);
            } catch (Exception e) {
                OnlineChat.LOGGER.error("[OnlineChat] Search query failure", e);
                sendJson(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("Internal error"));
            } finally {
                req.release();
            }
        });
    }

    /** {@code GET /api/webusers} — which web accounts have a live authenticated socket right now. Requires auth. */
    private void handleWebUsers(ChannelHandlerContext ctx, FullHttpRequest req) {
        Optional<Account> opt = auth(req);
        if (opt.isEmpty()) { sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, error("Unauthorized")); return; }
        if (!HttpMethod.GET.equals(req.method())) {
            sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("GET required"));
            return;
        }
        String meName = opt.get().getUsername();
        Map<String, WebSessionManager.Session> byUser = new LinkedHashMap<>();
        for (WebSessionManager.Session s : sessions.all()) {
            if (!s.isAuthenticated()) continue;
            byUser.putIfAbsent(s.username.toLowerCase(java.util.Locale.ROOT), s);
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        var arr = new com.google.gson.JsonArray();
        byUser.values().stream()
                .sorted(Comparator.comparing(s -> s.username.toLowerCase(java.util.Locale.ROOT)))
                .forEach(s -> {
                    JsonObject u = new JsonObject();
                    u.addProperty("username", s.username);
                    u.addProperty("self", s.username.equalsIgnoreCase(meName));
                    Account acc = accounts.byUsername(s.username).orElse(null);
                    if (acc != null && acc.isBound()) {
                        u.addProperty("mcName", acc.getBoundPlayerName());
                        u.addProperty("mcUuid", acc.getBoundPlayerUuid().toString());
                    }
                    arr.add(u);
                });
        o.add("webUsers", arr);
        o.addProperty("count", arr.size());
        sendJson(ctx, req, HttpResponseStatus.OK, o);
    }

    private static String firstParam(QueryStringDecoder qs, String name) {
        List<String> v = qs.parameters().get(name);
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    private static long parseLongOr(String s, long def) {
        if (s == null) return def;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return def; }
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
        String rel = path.equals("/") ? "/index.html" : path;
        // Prevent path traversal
        if (rel.contains("..")) { sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Bad path")); return; }
        byte[] data = loadStatic(rel);
        if (data == null) {
            // Fallback to index.html so SPA-style routing works.
            rel = "/index.html";
            data = loadStatic(rel);
            if (data == null) {
                sendJson(ctx, req, HttpResponseStatus.NOT_FOUND, error("Not found"));
                return;
            }
        }
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(data));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType(rel));
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
        resp.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        write(ctx, req, resp);
    }

    /** Extracted copy on disk first (operator customisations), then the pristine file inside the jar. */
    private byte[] loadStatic(String rel) {
        if (webAssets != null) {
            byte[] fromDisk = webAssets.read(rel);
            if (fromDisk != null) return fromDisk;
        }
        return staticCache.computeIfAbsent("/web" + rel, HttpApiHandler::loadResource);
    }

    private void redirect(ChannelHandlerContext ctx, FullHttpRequest req, String location) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
        resp.headers().set(HttpHeaderNames.LOCATION, location);
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
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
        applySecurityHeaders(ctx, resp);
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

    /** Defence-in-depth response headers applied to every reply (HTML pages and JSON alike). */
    private void applySecurityHeaders(ChannelHandlerContext ctx, FullHttpResponse resp) {
        var h = resp.headers();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                        "img-src 'self' data:; connect-src 'self' ws: wss:; font-src 'self'; " +
                        "object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
        // HSTS only makes sense (and is only honoured by browsers) over a genuine TLS connection.
        if (isSecure(ctx)) {
            h.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
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

    /** True if {@code cause} or any nested cause is a Netty {@code NotSslRecordException}. */
    private static boolean isNotSslRecord(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof io.netty.handler.ssl.NotSslRecordException) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    /**
     * A client spoke plaintext HTTP to the TLS port (e.g. typed http://host:8443 instead of https://).
     * Redirect it to the https:// equivalent so the browser lands on the right scheme. The SslHandler has
     * already failed, so drop it and answer in cleartext. The request was never decoded, so all we know is
     * this listener's own host:port - the redirect targets the HTTPS root and does not preserve the path.
     */
    private static void redirectToHttpsAndClose(ChannelHandlerContext ctx) {
        if (!ctx.channel().isActive()) { ctx.close(); return; }
        String host = "localhost";
        int port = ServerConfig.PORT.get();
        if (ctx.channel().localAddress() instanceof java.net.InetSocketAddress local) {
            java.net.InetAddress addr = local.getAddress();
            if (addr != null && !addr.isAnyLocalAddress()) host = local.getHostString();
            port = local.getPort();
        }
        try {
            ctx.pipeline().remove(io.netty.handler.ssl.SslHandler.class);
        } catch (RuntimeException ignored) {
            // No SslHandler to remove - still send the plaintext redirect.
        }
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
        resp.headers().set(HttpHeaderNames.LOCATION, "https://" + host + ":" + port + "/");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Plaintext HTTP (or a port scan) arriving on the TLS port surfaces as a DecoderException
        // wrapping NotSslRecordException. Its message embeds the raw request bytes - which can include
        // Cookie / session-token headers - so we must NOT log cause.toString() here (that would leak
        // credentials into the server log). Redirect the browser to https:// and log only when verbose.
        if (isNotSslRecord(cause)) {
            if (ServerConfig.VERBOSE_LOGGING.get()) {
                OnlineChat.LOGGER.info("[OnlineChat] Plaintext HTTP on the TLS port from {} - redirecting to https://",
                        ctx.channel().remoteAddress());
            }
            redirectToHttpsAndClose(ctx);
            return;
        }
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
