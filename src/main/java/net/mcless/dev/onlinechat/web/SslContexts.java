package net.mcless.dev.onlinechat.web;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import net.mcless.dev.onlinechat.OnlineChat;
import net.mcless.dev.onlinechat.config.ServerConfig;

import java.io.File;
import java.nio.file.Path;

/**
 * Builds a Netty {@link SslContext} from the PEM files configured in {@link ServerConfig}.
 * Relative paths resolve against the Minecraft run directory (working directory).
 */
public final class SslContexts {
    private SslContexts() {}

    public static SslContext buildServerContext(Path runDirectory) throws Exception {
        File cert = resolve(runDirectory, ServerConfig.CERT_CHAIN_PATH.get());
        File key = resolve(runDirectory, ServerConfig.PRIVATE_KEY_PATH.get());
        if (!cert.isFile()) throw new IllegalStateException("TLS certificate not found: " + cert.getAbsolutePath());
        if (!key.isFile()) throw new IllegalStateException("TLS private key not found: " + key.getAbsolutePath());

        String pass = ServerConfig.PRIVATE_KEY_PASSWORD.get();
        SslContextBuilder builder = (pass == null || pass.isEmpty())
                ? SslContextBuilder.forServer(cert, key)
                : SslContextBuilder.forServer(cert, key, pass);
        builder.sslProvider(pickProvider());
        if (ServerConfig.REQUIRE_CLIENT_AUTH.get()) {
            builder.clientAuth(io.netty.handler.ssl.ClientAuth.REQUIRE);
        }
        OnlineChat.LOGGER.info("[OnlineChat] TLS material loaded: cert={}, key={}", cert.getAbsolutePath(), key.getAbsolutePath());
        return builder.build();
    }

    private static SslProvider pickProvider() {
        try {
            if (SslProvider.isAlpnSupported(SslProvider.OPENSSL)) return SslProvider.OPENSSL;
        } catch (Throwable ignored) {}
        return SslProvider.JDK;
    }

    private static File resolve(Path runDirectory, String configured) {
        File f = new File(configured);
        if (f.isAbsolute()) return f;
        return runDirectory.resolve(configured).normalize().toFile();
    }
}
