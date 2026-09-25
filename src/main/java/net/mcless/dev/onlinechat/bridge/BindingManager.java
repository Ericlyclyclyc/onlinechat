package net.mcless.dev.onlinechat.bridge;

import com.google.gson.JsonObject;
import net.mcless.dev.onlinechat.OnlineChat;
import net.mcless.dev.onlinechat.account.Account;
import net.mcless.dev.onlinechat.account.AccountManager;
import net.mcless.dev.onlinechat.config.ServerConfig;
import net.mcless.dev.onlinechat.web.RateLimiter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles pending "bind web-account to Minecraft player" requests.
 * <p>
 * Flow:
 * <ol>
 *   <li>A web user asks to bind to a Minecraft username.</li>
 *   <li>If that player is online, a short random code is generated and both a clickable chat message
 *       (with [Yes] / [No] buttons) and a system message are sent to the player.</li>
 *   <li>The player clicks [Yes] which runs {@code /onlinechat bind confirm <code>} (or [No] for deny).</li>
 *   <li>The web user is notified through their WebSocket.</li>
 * </ol>
 */
public class BindingManager {
    public static final class PendingBind {
        public final String code;
        public final String webUsername;
        public final UUID playerUuid;
        public final String playerName;
        public final long expiresAt;

        PendingBind(String code, String webUsername, UUID playerUuid, String playerName, long expiresAt) {
            this.code = code;
            this.webUsername = webUsername;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.expiresAt = expiresAt;
        }

        public boolean expired() { return System.currentTimeMillis() > expiresAt; }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final Map<String, PendingBind> pending = new ConcurrentHashMap<>();
    private final AccountManager accounts;
    private final ChatBridge bridge;
    /** Per-web-account limiter on bind requests, so a user cannot spam confirmation popups at an online player. */
    private final RateLimiter bindLimiter = new RateLimiter(60_000L);

    public BindingManager(AccountManager accounts, ChatBridge bridge) {
        this.accounts = accounts;
        this.bridge = bridge;
    }

    public Optional<PendingBind> request(MinecraftServer server, String webUsername, String targetPlayerName) {
        purgeExpired();

        if (!bindLimiter.allow(webUsername == null ? "" : webUsername.toLowerCase(Locale.ROOT),
                ServerConfig.BIND_REQUESTS_PER_MINUTE.get())) {
            OnlineChat.LOGGER.warn("[OnlineChat] Bind-request rate limit hit for web user '{}'", webUsername);
            return Optional.empty();
        }

        Account account = accounts.byUsername(webUsername).orElse(null);
        if (account == null) return Optional.empty();
        if (account.isBound() && !ServerConfig.ALLOW_REBIND.get()) return Optional.empty();

        ServerPlayer player = server.getPlayerList().getPlayers().stream()
                .filter(p -> p.getGameProfile().name().equalsIgnoreCase(targetPlayerName))
                .findFirst().orElse(null);
        if (player == null) return Optional.empty();

        // Reject if this Minecraft player is already bound to another web account.
        Optional<Account> existing = accounts.byPlayerUuid(player.getUUID());
        if (existing.isPresent() && !existing.get().getUsername().equalsIgnoreCase(webUsername)) {
            return Optional.empty();
        }

        String code = generateCode();
        PendingBind pb = new PendingBind(code, webUsername, player.getUUID(),
                player.getGameProfile().name(),
                System.currentTimeMillis() + ServerConfig.BIND_CODE_TTL_SECONDS.get() * 1000L);
        pending.put(code, pb);
        ChatBindNotifier.sendConfirmationPrompt(player, webUsername, code);
        return Optional.of(pb);
    }

    public Optional<PendingBind> confirm(MinecraftServer server, ServerPlayer player, String code) {
        PendingBind pb = pending.remove(upper(code));
        if (pb == null || pb.expired()) return Optional.empty();
        if (!pb.playerUuid.equals(player.getUUID())) {
            // Wrong player tried to confirm — put it back so the rightful player can still confirm.
            pending.put(pb.code, pb);
            return Optional.empty();
        }
        Account account = accounts.byUsername(pb.webUsername).orElse(null);
        if (account == null) return Optional.empty();
        accounts.bind(account, pb.playerUuid, pb.playerName);
        return Optional.of(pb);
    }

    public Optional<PendingBind> deny(ServerPlayer player, String code) {
        PendingBind pb = pending.get(upper(code));
        if (pb == null || pb.expired()) return Optional.empty();
        if (!pb.playerUuid.equals(player.getUUID())) return Optional.empty();
        pending.remove(pb.code);
        return Optional.of(pb);
    }

    public Optional<PendingBind> peek(String code) {
        PendingBind pb = pending.get(upper(code));
        if (pb == null) return Optional.empty();
        if (pb.expired()) { pending.remove(pb.code); return Optional.empty(); }
        return Optional.of(pb);
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(pb -> {
            if (pb.expiresAt < now) {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "bind_expired");
                obj.addProperty("code", pb.code);
                obj.addProperty("mcName", pb.playerName);
                bridge.broadcastToUsername(pb.webUsername, obj);
                return true;
            }
            return false;
        });
    }

    public int pendingCount() { return pending.size(); }

    /** Drops every pending request started by {@code webUsername} (account deleted). */
    public void cancelFor(String webUsername) {
        if (webUsername == null) return;
        pending.values().removeIf(pb -> pb.webUsername.equalsIgnoreCase(webUsername));
    }

    private static String generateCode() {
        char[] out = new char[6];
        for (int i = 0; i < out.length; i++) out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        return new String(out);
    }

    private static String upper(String s) { return s == null ? "" : s.trim().toUpperCase(); }

    /** Small helper so the notifier can be swapped out (unit tests, custom titles, ...). */
    public interface Notifier { void notifyBind(ServerPlayer player, String webUsername, String code); }

    /** Default implementation kept in a nested class to avoid cyclic imports at file scope. */
    public static final class ChatBindNotifier {
        private ChatBindNotifier() {}
        public static void sendConfirmationPrompt(ServerPlayer player, String webUsername, String code) {
            net.mcless.dev.onlinechat.command.OnlineChatCommand.sendBindPrompt(player, webUsername, code);
        }
    }

    /** Base64 utility used by other packages. */
    public static String encode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    static {
        OnlineChat.LOGGER.debug("[OnlineChat] BindingManager ready");
    }
}
