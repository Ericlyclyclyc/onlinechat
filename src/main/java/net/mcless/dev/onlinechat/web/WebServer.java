package net.mcless.dev.onlinechat.web;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import net.mcless.dev.onlinechat.OnlineChat;
import net.mcless.dev.onlinechat.account.AccountManager;
import net.mcless.dev.onlinechat.account.TokenService;
import net.mcless.dev.onlinechat.auth.TwoFactorGuard;
import net.mcless.dev.onlinechat.bridge.BindingManager;
import net.mcless.dev.onlinechat.bridge.ChatBridge;
import net.mcless.dev.onlinechat.bridge.WebSessionManager;
import net.mcless.dev.onlinechat.config.ServerConfig;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Netty-backed HTTPS + WebSocket server.
 * Uses the SSL material in {@code ./ssl} (configurable) and shares Netty with Minecraft — no extra runtime deps.
 */
public class WebServer {
    private final AccountManager accounts;
    private final TokenService tokens;
    private final BindingManager bindings;
    private final ChatBridge bridge;
    private final WebSessionManager sessions;
    private final TwoFactorGuard twoFactor;
    private final WebAssets webAssets;
    private final Path runDirectory;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;   // HTTPS listener (default)
    private Channel httpChannel;     // plain-HTTP listener (optional)
    /** Off-EventLoop pool for blocking work (PBKDF2 hashing, account file writes). */
    private ExecutorService blockingPool;
    private volatile boolean running;

    public WebServer(Path runDirectory, AccountManager accounts, TokenService tokens,
                     BindingManager bindings, ChatBridge bridge, WebSessionManager sessions,
                     TwoFactorGuard twoFactor, WebAssets webAssets) {
        this.runDirectory = runDirectory;
        this.accounts = accounts;
        this.tokens = tokens;
        this.bindings = bindings;
        this.bridge = bridge;
        this.sessions = sessions;
        this.twoFactor = twoFactor;
        this.webAssets = webAssets;
    }

    public synchronized void start() {
        if (running) return;
        if (!ServerConfig.WEB_ENABLED.get()) {
            OnlineChat.LOGGER.info("[OnlineChat] Web server disabled by config.");
            return;
        }
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup();
        blockingPool = new ThreadPoolExecutor(
                2, Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(512),
                r -> { Thread t = new Thread(r, "OnlineChat-blocking"); t.setDaemon(true); return t; },
                // Back-pressure: if the queue is saturated, run on the caller rather than reject the request.
                new ThreadPoolExecutor.CallerRunsPolicy());
        String host = ServerConfig.HOST.get();
        boolean anyBound = false;

        // HTTPS listener — the default and recommended surface.
        // On first run (no PEM material yet) SslContexts creates the certificate directory and logs
        // an actionable "configure SSL" guide instead of failing later with a bare stack trace.
        if (SslContexts.prepareAndWarnIfMissing(runDirectory)) {
            try {
                SslContext sslCtx = SslContexts.buildServerContext(runDirectory);
                int port = ServerConfig.PORT.get();
                serverChannel = newBootstrap(sslCtx).bind(new InetSocketAddress(host, port)).sync().channel();
                anyBound = true;
                OnlineChat.LOGGER.info("[OnlineChat] HTTPS/WebSocket server listening on https://{}:{}/", host, port);
            } catch (Exception e) {
                OnlineChat.LOGGER.error("[OnlineChat] Unable to start the HTTPS listener", e);
            }
        }

        // Optional plain-HTTP listener (unencrypted).
        if (ServerConfig.HTTP_ENABLED.get()) {
            try {
                int httpPort = ServerConfig.HTTP_PORT.get();
                httpChannel = newBootstrap(null).bind(new InetSocketAddress(host, httpPort)).sync().channel();
                anyBound = true;
                OnlineChat.LOGGER.warn("[OnlineChat] Plain-HTTP/WebSocket server listening on http://{}:{}/ (UNENCRYPTED)", host, httpPort);
            } catch (Exception e) {
                OnlineChat.LOGGER.error("[OnlineChat] Unable to start the plain-HTTP listener", e);
            }
        }

        if (anyBound) {
            running = true;
        } else {
            OnlineChat.LOGGER.error("[OnlineChat] No web listener could be started; releasing event loops.");
            stop();
        }
    }

    /**
     * Builds a {@link ServerBootstrap} sharing this server's event loops.
     * When {@code sslCtx} is {@code null} the pipeline serves plain HTTP; otherwise it terminates TLS first.
     */
    private ServerBootstrap newBootstrap(SslContext sslCtx) {
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        if (sslCtx != null) p.addLast("ssl", sslCtx.newHandler(ch.alloc()));
                        p.addLast("idle", new IdleStateHandler(120, 0, 0, TimeUnit.SECONDS));
                        p.addLast("http-codec", new HttpServerCodec());
                        p.addLast("http-aggregator", new HttpObjectAggregator(1024 * 1024));
                        p.addLast("chunked", new ChunkedWriteHandler());
                        p.addLast("api", new HttpApiHandler(accounts, tokens, bindings, bridge, sessions, twoFactor, webAssets, blockingPool));
                        p.addLast("ws", new WebSocketFrameHandler(accounts, tokens, bindings, bridge, sessions));
                    }
                });
        return b;
    }

    public synchronized void stop() {
        running = false;
        serverChannel = closeQuietly(serverChannel);
        httpChannel = closeQuietly(httpChannel);
        if (boss != null) { boss.shutdownGracefully(); boss = null; }
        if (worker != null) { worker.shutdownGracefully(); worker = null; }
        if (blockingPool != null) { blockingPool.shutdownNow(); blockingPool = null; }
        OnlineChat.LOGGER.info("[OnlineChat] Web server stopped.");
    }

    private static Channel closeQuietly(Channel ch) {
        if (ch == null) return null;
        try { ch.close().sync(); } catch (Exception ignored) {}
        return null;
    }

    public boolean isRunning() { return running; }
}
