package net.mcless.dev.onlinechat;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.mcless.dev.onlinechat.account.Account;
import net.mcless.dev.onlinechat.account.AccountManager;
import net.mcless.dev.onlinechat.account.TokenService;
import net.mcless.dev.onlinechat.auth.TwoFactorGuard;
import net.mcless.dev.onlinechat.bridge.BindingManager;
import net.mcless.dev.onlinechat.bridge.ChatBridge;
import net.mcless.dev.onlinechat.bridge.MessageStore;
import net.mcless.dev.onlinechat.bridge.WebSessionManager;
import net.mcless.dev.onlinechat.command.OnlineChatCommand;
import net.mcless.dev.onlinechat.config.CommonConfig;
import net.mcless.dev.onlinechat.config.ServerConfig;
import net.mcless.dev.onlinechat.i18n.Lang;
import net.mcless.dev.onlinechat.web.WebAssets;
import net.mcless.dev.onlinechat.web.WebServer;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * OnlineChat — an embedded HTTPS + WebSocket chat platform for Minecraft 1.21.1 (NeoForge).
 * <p>
 * Responsibilities of this class:
 * <ul>
 *   <li>Register the split config specs (common + server)</li>
 *   <li>Own the runtime singletons (accounts, tokens, bindings, bridge, sessions, web server)</li>
 *   <li>Start/stop the web server alongside the Minecraft server</li>
 *   <li>Register the {@code /onlinechat} command</li>
 * </ul>
 */
@Mod(OnlineChat.MODID)
public class OnlineChat {
    public static final String MODID = "onlinechat";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static OnlineChat INSTANCE;
    public static OnlineChat instance() { return INSTANCE; }

    private AccountManager accounts;
    private TokenService tokens;
    private WebSessionManager sessions;
    private ChatBridge bridge;
    private BindingManager bindings;
    private MessageStore messages;
    private WebServer webServer;
    private WebAssets webAssets;
    private TwoFactorGuard twoFactor;
    private Path runDirectory;

    public OnlineChat(IEventBus modEventBus, ModContainer modContainer) {
        INSTANCE = this;

        modContainer.registerConfig(ModConfig.Type.COMMON, CommonConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);

        NeoForge.EVENT_BUS.register(this);

        LOGGER.info("[OnlineChat] Mod constructed.");
    }

    // ─────────────────────────── Server lifecycle ───────────────────────────

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        this.runDirectory = detectRunDirectory(server);

        Lang.load(ServerConfig.LANGUAGE.get());

        Path accountsPath = resolvePath(runDirectory, ServerConfig.ACCOUNTS_FILE.get());
        Path secretPath = accountsPath.resolveSibling("token.secret");

        this.accounts = new AccountManager(accountsPath);
        this.accounts.load();

        this.tokens = new TokenService(accounts, secretPath);
        this.tokens.init();

        Path chatLogPath = resolvePath(runDirectory, ServerConfig.CHAT_LOG_FILE.get());
        this.messages = new MessageStore(chatLogPath, ServerConfig.CHAT_HISTORY_SIZE.get());
        this.messages.load();

        this.sessions = new WebSessionManager();
        this.bridge = new ChatBridge(sessions, accounts, messages);
        this.bridge.setServer(server);
        this.bindings = new BindingManager(accounts, bridge);

        this.twoFactor = new TwoFactorGuard(accounts);
        this.twoFactor.setServer(server);

        NeoForge.EVENT_BUS.register(bridge);
        NeoForge.EVENT_BUS.register(twoFactor);

        this.webAssets = new WebAssets(resolvePath(runDirectory, ServerConfig.WEB_DIR.get()));
        this.webAssets.extractIfNeeded();

        this.webServer = new WebServer(runDirectory, accounts, tokens, bindings, bridge, sessions, twoFactor, webAssets);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (webServer != null) webServer.start();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // Tell every connected browser the server is going down (so it can pop a "server closed" dialog)
        // before we tear down the sockets and event loops below.
        if (bridge != null) bridge.broadcastShutdown();
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        if (bridge != null) {
            NeoForge.EVENT_BUS.unregister(bridge);
            bridge.setServer(null);
        }
        if (twoFactor != null) {
            NeoForge.EVENT_BUS.unregister(twoFactor);
            twoFactor.setServer(null);
        }
        if (accounts != null) accounts.save();
        this.accounts = null;
        this.tokens = null;
        this.sessions = null;
        this.bridge = null;
        this.bindings = null;
        this.messages = null;
        this.twoFactor = null;
        this.webAssets = null;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        OnlineChatCommand.register(event.getDispatcher());
    }

    /** Called by {@code /onlinechat reload}. */
    public void reloadWebServer() {
        Lang.load(ServerConfig.LANGUAGE.get());
        if (webServer == null) return;
        webServer.stop();
        webServer.start();
    }

    // ─────────────────────────── Account operations shared by web API and commands ───────────────────────────

    /**
     * Changes the password and signs the account out everywhere: the fresh {@code lastLoginAt} written by
     * {@link AccountManager#setPassword} invalidates every outstanding token and live sockets get a
     * {@code force_logout} frame. Runs PBKDF2, so call it off the Netty event loop.
     */
    public void changePassword(Account account, String newPassword) {
        accounts.setPassword(account, newPassword);
        if (bridge != null) bridge.forceLogout(account.getUsername(), "password_changed");
    }

    /** Removes the player binding (and with it 2FA), lifting any hold currently placed on that player. */
    public void unbindAccount(Account account) {
        java.util.UUID boundUuid = account.getBoundPlayerUuid();
        accounts.unbind(account);
        if (twoFactor != null && boundUuid != null) twoFactor.releaseFor(boundUuid);
        if (sessions != null) {
            for (WebSessionManager.Session s : sessions.byUsername(account.getUsername())) s.boundPlayerUuid = null;
        }
    }

    /**
     * Deletes a web account and everything attached to it: player binding (which also lifts any 2FA hold
     * on that player), pending bind requests, live WebSocket sessions and their tokens.
     */
    public boolean deleteAccount(Account account) {
        if (account == null || accounts == null) return false;
        if (bindings != null) bindings.cancelFor(account.getUsername());
        if (account.isBound()) unbindAccount(account);
        boolean removed = accounts.delete(account);
        if (bridge != null) bridge.forceLogout(account.getUsername(), "account_deleted");
        if (sessions != null) {
            for (WebSessionManager.Session s : sessions.byUsername(account.getUsername())) {
                if (s.channel != null && s.channel.isActive()) s.channel.close();
            }
        }
        if (removed) LOGGER.info("[OnlineChat] Web account '{}' deleted", account.getUsername());
        return removed;
    }

    /** Push a bind outcome to the requesting web user. */
    public void notifyWebBindResult(BindingManager.PendingBind pb, boolean success, String reason) {
        if (bridge == null || pb == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("type", success ? "bind_ok" : "bind_denied");
        o.addProperty("code", pb.code);
        o.addProperty("mcName", pb.playerName);
        if (!success && reason != null) o.addProperty("reason", reason);
        bridge.broadcastToUsername(pb.webUsername, o);
        // Refresh the session's bound UUID so subsequent chat messages use the MC name.
        if (success && accounts != null) {
            accounts.byUsername(pb.webUsername).ifPresent(acc -> {
                for (WebSessionManager.Session s : sessions.byUsername(acc.getUsername())) {
                    s.boundPlayerUuid = acc.getBoundPlayerUuid();
                }
                bridge.emitWebSystem(Lang.tr("onlinechat.bridge.bound", acc.getUsername(), acc.getBoundPlayerName()));
                if (bridge.server() != null) {
                    bridge.emitGameSystem(Lang.text("onlinechat.prefix")
                            .append(Lang.text("onlinechat.command.bind.announce", acc.getUsername(), acc.getBoundPlayerName()))
                            .withStyle(ChatFormatting.GOLD));
                }
            });
        }
    }

    public AccountManager getAccounts() { return accounts; }
    public TokenService getTokens() { return tokens; }
    public BindingManager getBindings() { return bindings; }
    public ChatBridge getBridge() { return bridge; }
    public MessageStore getMessages() { return messages; }
    public WebSessionManager getSessions() { return sessions; }
    public WebServer getWebServer() { return webServer; }
    public WebAssets getWebAssets() { return webAssets; }
    public TwoFactorGuard getTwoFactor() { return twoFactor; }
    public Path getRunDirectory() { return runDirectory; }

    private static Path detectRunDirectory(MinecraftServer server) {
        // The working directory is where ./ssl, ./config and (for a dedicated server) the world folders live.
        return Paths.get("").toAbsolutePath().normalize();
    }

    private static Path resolvePath(Path runDirectory, String configured) {
        Path p = Paths.get(configured);
        if (p.isAbsolute()) return p.normalize();
        return runDirectory.resolve(configured).normalize();
    }
}
