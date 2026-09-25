package net.mcless.dev.onlinechat.auth;

import net.mcless.dev.onlinechat.OnlineChat;
import net.mcless.dev.onlinechat.account.Account;
import net.mcless.dev.onlinechat.account.AccountManager;
import net.mcless.dev.onlinechat.config.ServerConfig;
import net.mcless.dev.onlinechat.i18n.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.minecraft.util.TriState;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second factor for joining the game.
 * <p>
 * When a player whose bound web account has 2FA switched on logs in, they are put on hold: a one-time
 * link ({@code <publicUrl>/2fa/auth/<token>}) is shown in chat and, until it is opened from a browser
 * that is signed in to that very web account, the player is frozen in place and every chat message,
 * command, interaction, attack, block break, item toss/pickup and incoming damage is cancelled. Movement
 * is prevented on the client side too by zeroing the synced movement attributes, and the server snaps the
 * player back should anything else move them. No packets are filtered, so every other mod keeps working
 * normally (chunks, tick sync, custom networking) — the player simply cannot act.
 * <p>
 * Failing the check (browser signed in to a different account, or the configured timeout elapsing) kicks
 * the player. Tokens are 256-bit random values, single use, bound to the player UUID and expire with the
 * hold.
 */
public class TwoFactorGuard {
    public static final class Pending {
        public final UUID playerUuid;
        public final String playerName;
        public final String webUsername;
        public final String token;
        public final long startedAt;
        public final long deadline;
        final Vec3 anchor;
        int nextReminder;

        Pending(UUID playerUuid, String playerName, String webUsername, String token, long deadline, Vec3 anchor) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.webUsername = webUsername;
            this.token = token;
            this.startedAt = System.currentTimeMillis();
            this.deadline = deadline;
            this.anchor = anchor;
            this.nextReminder = REMINDERS.length - 1;
        }

        public boolean expired() { return System.currentTimeMillis() > deadline; }
    }

    /** Verification outcome for the web API. */
    public enum Result { OK, INVALID_TOKEN, WRONG_ACCOUNT, UNAVAILABLE }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Identifier FREEZE_ID = Identifier.fromNamespaceAndPath(OnlineChat.MODID, "two_factor_freeze");
    /** Attributes zeroed while frozen. All of them are synced to the client, so the client stops moving by itself. */
    private static final List<Holder<Attribute>> FROZEN_ATTRIBUTES = List.of(
            Attributes.MOVEMENT_SPEED, Attributes.FLYING_SPEED, Attributes.JUMP_STRENGTH, Attributes.GRAVITY,
            Attributes.BLOCK_INTERACTION_RANGE, Attributes.ENTITY_INTERACTION_RANGE, Attributes.BLOCK_BREAK_SPEED);
    /** Seconds-remaining marks at which the frozen player is reminded (descending order in the array). */
    private static final int[] REMINDERS = {10, 30, 60};

    private final AccountManager accounts;
    private final Map<UUID, Pending> byPlayer = new ConcurrentHashMap<>();
    private final Map<String, Pending> byToken = new ConcurrentHashMap<>();
    private volatile MinecraftServer server;

    public TwoFactorGuard(AccountManager accounts) {
        this.accounts = accounts;
    }

    public void setServer(MinecraftServer server) { this.server = server; }

    /** Whether the feature is switched on by the operator. */
    public static boolean featureEnabled() { return ServerConfig.TWO_FACTOR_ENABLED.get(); }

    public boolean isFrozen(UUID playerUuid) { return playerUuid != null && byPlayer.containsKey(playerUuid); }
    public boolean isFrozen(Entity entity) { return entity instanceof ServerPlayer p && isFrozen(p.getUUID()); }
    public Optional<Pending> pendingByToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Pending p = byToken.get(token);
        if (p == null) return Optional.empty();
        if (p.expired()) return Optional.empty();
        return Optional.of(p);
    }
    public int pendingCount() { return byPlayer.size(); }

    // ─────────────────────────── Web-side verification ───────────────────────────

    /**
     * Called from the web API on a Netty thread when a signed-in browser opens the link.
     * {@code account} is the web account behind the browser's cookie.
     */
    public Result verify(String token, Account account) {
        MinecraftServer srv = this.server;
        if (srv == null) return Result.UNAVAILABLE;
        Pending p = pendingByToken(token).orElse(null);
        if (p == null) return Result.INVALID_TOKEN;
        boolean match = account != null
                && p.webUsername.equalsIgnoreCase(account.getUsername())
                && p.playerUuid.equals(account.getBoundPlayerUuid());
        if (!match) {
            // A browser signed in as somebody else tried to release this player: fail closed.
            OnlineChat.LOGGER.warn("[OnlineChat] 2FA failure for {}: browser is signed in as '{}'", p.playerName,
                    account == null ? "<nobody>" : account.getUsername());
            srv.execute(() -> kick(p, Lang.text("onlinechat.twofactor.kick.failed")));
            return Result.WRONG_ACCOUNT;
        }
        srv.execute(() -> release(p, true));
        return Result.OK;
    }

    /** "This is not me" from the web page: kicks the player waiting behind {@code token}. */
    public boolean reject(String token, Account account) {
        MinecraftServer srv = this.server;
        Pending p = pendingByToken(token).orElse(null);
        if (srv == null || p == null || account == null) return false;
        if (!p.webUsername.equalsIgnoreCase(account.getUsername())) return false;
        OnlineChat.LOGGER.warn("[OnlineChat] 2FA rejected by web user '{}' for player {}", account.getUsername(), p.playerName);
        srv.execute(() -> kick(p, Lang.text("onlinechat.twofactor.kick.failed")));
        return true;
    }

    /** The account was unbound or deleted: nothing is left to protect, let the player through. */
    public void releaseFor(UUID playerUuid) {
        Pending p = playerUuid == null ? null : byPlayer.get(playerUuid);
        MinecraftServer srv = this.server;
        if (p == null || srv == null) return;
        srv.execute(() -> release(p, false));
    }

    // ─────────────────────────── Lifecycle events ───────────────────────────

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Clean up a stale freeze modifier left by a crash mid-verification.
        unfreeze(player);
        if (!featureEnabled()) return;
        Optional<Account> acc = accounts.byPlayerUuid(player.getUUID());
        if (acc.isEmpty() || !acc.get().isTwoFactorEnabled()) return;

        OnlineChat runtime = OnlineChat.instance();
        if (runtime == null || runtime.getWebServer() == null || !runtime.getWebServer().isRunning()) {
            OnlineChat.LOGGER.warn("[OnlineChat] {} has 2FA enabled but the web server is not running; skipping the check", player.getGameProfile().name());
            return;
        }

        String token = newToken();
        long deadline = System.currentTimeMillis() + ServerConfig.TWO_FACTOR_TIMEOUT_SECONDS.get() * 1000L;
        Pending p = new Pending(player.getUUID(), player.getGameProfile().name(), acc.get().getUsername(),
                token, deadline, player.position());
        byPlayer.put(p.playerUuid, p);
        byToken.put(token, p);
        freeze(player);
        if (player.containerMenu != player.inventoryMenu) player.closeContainer();

        String url = publicUrl() + "/2fa/auth/" + token;
        player.sendSystemMessage(prefix().append(Lang.text("onlinechat.twofactor.required").withStyle(ChatFormatting.YELLOW)));
        player.sendSystemMessage(Component.literal("  ").append(Component.literal(url).withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(Lang.text("onlinechat.twofactor.link.hover"))))));
        player.sendSystemMessage(prefix().append(Lang.text("onlinechat.twofactor.frozen",
                ServerConfig.TWO_FACTOR_TIMEOUT_SECONDS.get()).withStyle(ChatFormatting.GRAY)));
        OnlineChat.LOGGER.info("[OnlineChat] {} is waiting for 2FA verification (web account '{}')", p.playerName, p.webUsername);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Pending p = byPlayer.remove(event.getEntity().getUUID());
        if (p != null) byToken.remove(p.token);
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Pending p = byPlayer.get(player.getUUID());
        if (p == null) return;
        long now = System.currentTimeMillis();
        if (now > p.deadline) {
            kick(p, Lang.text("onlinechat.twofactor.kick.timeout"));
            return;
        }
        // Keep the freeze in place (a respawn or another mod may have rebuilt the attribute map).
        freeze(player);
        if (player.containerMenu != player.inventoryMenu) player.closeContainer();
        // Snap back if anything (piston, contraption, a laggy client) moved the player. Looking around is fine.
        if (player.position().distanceToSqr(p.anchor) > 0.0025) {
            player.connection.teleport(p.anchor.x, p.anchor.y, p.anchor.z, player.getYRot(), player.getXRot());
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;

        int remaining = (int) Math.ceil((p.deadline - now) / 1000.0);
        while (p.nextReminder >= 0 && remaining <= REMINDERS[p.nextReminder]) {
            int mark = REMINDERS[p.nextReminder--];
            if (remaining >= mark - 1) {
                player.sendSystemMessage(prefix().append(Lang.text("onlinechat.twofactor.frozen", remaining).withStyle(ChatFormatting.GRAY)));
            }
        }
    }

    // ─────────────────────────── Action blocking ───────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onServerChat(ServerChatEvent event) {
        if (!isFrozen(event.getPlayer())) return;
        event.setCanceled(true);
        event.getPlayer().sendSystemMessage(prefix().append(Lang.text("onlinechat.twofactor.frozen",
                remainingSeconds(event.getPlayer().getUUID())).withStyle(ChatFormatting.RED)));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onCommand(CommandEvent event) {
        ServerPlayer player = event.getParseResults().getContext().getSource().getPlayer();
        if (player == null || !isFrozen(player)) return;
        event.setCanceled(true);
        player.sendSystemMessage(prefix().append(Lang.text("onlinechat.twofactor.frozen",
                remainingSeconds(player.getUUID())).withStyle(ChatFormatting.RED)));
    }

    // PlayerInteractEvent is abstract and is never posted itself; NeoForge rejects a listener on it at
    // registration time ("Cannot register listeners for abstract class"), which crashes ServerStarting.
    // Register the concrete, server-side cancellable subclasses instead. EntityInteract also catches its
    // own subclass EntityInteractSpecific; RightClickEmpty/LeftClickEmpty are client-only and never fire here.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) { cancelInteract(event); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) { cancelInteract(event); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) { cancelInteract(event); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) { cancelInteract(event); }

    private void cancelInteract(PlayerInteractEvent event) {
        if (isFrozen(event.getEntity()) && event instanceof ICancellableEvent c) c.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttack(AttackEntityEvent event) {
        if (isFrozen(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBreak(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        if (isFrozen(event.getPlayer())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onToss(ItemTossEvent event) {
        Player player = event.getPlayer();
        if (!isFrozen(player)) return;
        event.setCanceled(true);
        // Cancelling only stops the entity from spawning; the stack was already taken out of the inventory.
        player.getInventory().placeItemBackInInventory(event.getEntity().getItem().copy());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPickup(ItemEntityPickupEvent.Pre event) {
        if (isFrozen(event.getPlayer())) event.setCanPickup(TriState.FALSE);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDamage(LivingIncomingDamageEvent event) {
        if (isFrozen(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onUseItem(LivingEntityUseItemEvent.Start event) {
        if (isFrozen(event.getEntity())) event.setCanceled(true);
    }

    // ─────────────────────────── Internals (server thread) ───────────────────────────

    private void release(Pending p, boolean announce) {
        if (byPlayer.remove(p.playerUuid) == null) return;
        byToken.remove(p.token);
        MinecraftServer srv = this.server;
        ServerPlayer player = srv == null ? null : srv.getPlayerList().getPlayer(p.playerUuid);
        if (player == null) return;
        unfreeze(player);
        if (announce) player.sendSystemMessage(prefix().append(Lang.text("onlinechat.twofactor.success").withStyle(ChatFormatting.GREEN)));
        OnlineChat.LOGGER.info("[OnlineChat] {} passed 2FA verification", p.playerName);
    }

    private void kick(Pending p, Component reason) {
        if (byPlayer.remove(p.playerUuid) == null) return;
        byToken.remove(p.token);
        MinecraftServer srv = this.server;
        ServerPlayer player = srv == null ? null : srv.getPlayerList().getPlayer(p.playerUuid);
        if (player == null) return;
        unfreeze(player);
        OnlineChat.LOGGER.info("[OnlineChat] Kicking {}: {}", p.playerName, reason.getString());
        player.connection.disconnect(prefix().append(reason));
    }

    private static void freeze(ServerPlayer player) {
        for (Holder<Attribute> attr : FROZEN_ATTRIBUTES) {
            AttributeInstance inst = player.getAttribute(attr);
            if (inst == null || inst.hasModifier(FREEZE_ID)) continue;
            // -100% of the final value -> exactly 0, whatever base value or other modifiers apply.
            inst.addTransientModifier(new AttributeModifier(FREEZE_ID, -1.0D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void unfreeze(ServerPlayer player) {
        for (Holder<Attribute> attr : FROZEN_ATTRIBUTES) {
            AttributeInstance inst = player.getAttribute(attr);
            if (inst != null) inst.removeModifier(FREEZE_ID);
        }
    }

    private static String newToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private String publicUrl() {
        String configured = ServerConfig.TWO_FACTOR_PUBLIC_URL.get();
        if (configured != null && !configured.isBlank()) {
            String u = configured.trim();
            return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        }
        MinecraftServer srv = this.server;
        String host = ServerConfig.HOST.get();
        if (host == null || host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host)) {
            host = srv != null && srv.getLocalIp() != null && !srv.getLocalIp().isBlank() ? srv.getLocalIp() : "localhost";
        }
        return "https://" + host + ":" + ServerConfig.PORT.get();
    }

    private int remainingSeconds(UUID playerUuid) {
        Pending p = byPlayer.get(playerUuid);
        return p == null ? 0 : (int) Math.max(0, Math.ceil((p.deadline - System.currentTimeMillis()) / 1000.0));
    }

    private static net.minecraft.network.chat.MutableComponent prefix() {
        return Lang.text("onlinechat.prefix").withStyle(ChatFormatting.GOLD);
    }
}
