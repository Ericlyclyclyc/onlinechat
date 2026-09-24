package net.mcless.dev.onlinechat.bridge;

import io.netty.channel.Channel;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks live WebSocket connections and which web account (and therefore which Minecraft UUID) each one belongs to.
 */
public class WebSessionManager {
    public static final class Session {
        public final Channel channel;
        public final String remoteAddress;
        public volatile String username;   // null until authenticated
        public volatile UUID boundPlayerUuid;
        /**
         * Token captured from the WebSocket upgrade request (Cookie or Authorization header).
         * Consumed once by {@link net.mcless.dev.onlinechat.web.WebSocketFrameHandler} on
         * {@code handlerAdded} so browsers that cannot set custom headers on a WS handshake
         * still get auto-authenticated.
         */
        public volatile String handshakeToken;
        public final long connectedAt = System.currentTimeMillis();

        public Session(Channel channel, String remoteAddress) {
            this.channel = channel;
            this.remoteAddress = remoteAddress;
        }

        public boolean isAuthenticated() { return username != null; }
    }

    private final Map<Channel, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<Session>> byUsername = new ConcurrentHashMap<>();

    public Session register(Channel channel, String remoteAddress) {
        Session s = new Session(channel, remoteAddress);
        sessions.put(channel, s);
        return s;
    }

    public void unregister(Channel channel) {
        Session s = sessions.remove(channel);
        if (s != null && s.username != null) {
            byUsername.computeIfPresent(s.username.toLowerCase(), (k, v) -> { v.remove(s); return v.isEmpty() ? null : v; });
        }
    }

    public Session get(Channel channel) {
        return sessions.get(channel);
    }

    public void authenticate(Session session, String username, UUID boundPlayerUuid) {
        session.username = username;
        session.boundPlayerUuid = boundPlayerUuid;
        byUsername.computeIfAbsent(username.toLowerCase(), k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public Collection<Session> all() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    public Set<Session> byUsername(String username) {
        Set<Session> s = byUsername.get(username.toLowerCase());
        return s == null ? Collections.emptySet() : Collections.unmodifiableSet(s);
    }

    public boolean isUsernameOnline(String username) {
        Set<Session> s = byUsername.get(username.toLowerCase());
        return s != null && !s.isEmpty();
    }

    public int onlineCount() {
        return (int) sessions.values().stream().filter(Session::isAuthenticated).count();
    }

    /** Total number of live WebSocket connections (authenticated or not). */
    public int totalConnections() {
        return sessions.size();
    }

    /** Number of live connections originating from the given IP (port-insensitive). */
    public int connectionsFromIp(String ip) {
        if (ip == null) return 0;
        int n = 0;
        for (Session s : sessions.values()) {
            if (ip.equals(ipOf(s.remoteAddress))) n++;
        }
        return n;
    }

    /** Extracts the bare IP from a Netty remote-address string such as {@code /1.2.3.4:56789}. */
    public static String ipOf(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) return "unknown";
        String r = remoteAddress;
        int slash = r.lastIndexOf('/');
        if (slash >= 0) r = r.substring(slash + 1);
        int colon = r.lastIndexOf(':');
        if (colon >= 0) r = r.substring(0, colon);
        return r.isEmpty() ? "unknown" : r;
    }
}
