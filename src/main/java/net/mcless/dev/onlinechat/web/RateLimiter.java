package net.mcless.dev.onlinechat.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window, per-key rate limiter. Thread-safe.
 * <p>
 * The maximum count is supplied per call so config changes take effect without recreating
 * the limiter; a {@code max <= 0} disables limiting entirely. The window length is fixed at
 * construction time, so create one instance per distinct window (e.g. one per minute, one per hour).
 */
public final class RateLimiter {
    private static final class Window { long start; int count; }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final long windowMillis;

    public RateLimiter(long windowMillis) {
        this.windowMillis = Math.max(1L, windowMillis);
    }

    /**
     * Records an action for {@code key} and reports whether it is still within the {@code max} budget
     * for the current window.
     */
    public boolean allow(String key, int max) {
        if (max <= 0) return true;
        if (key == null) key = "";
        long now = System.currentTimeMillis();
        Window w = windows.computeIfAbsent(key, k -> new Window());
        synchronized (w) {
            if (now - w.start >= windowMillis) { w.start = now; w.count = 0; }
            if (w.count >= max) return false;
            w.count++;
            return true;
        }
    }

    /** Drops windows that have gone idle for two full windows, bounding memory for churning keys. */
    public void purge() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return now - e.getValue().start >= windowMillis * 2;
            }
        });
    }
}
