package net.mcless.dev.onlinechat;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.mcless.dev.onlinechat.account.AccountManager;
import net.mcless.dev.onlinechat.account.TokenService;
import net.mcless.dev.onlinechat.bridge.BindingManager;
import net.mcless.dev.onlinechat.bridge.ChatBridge;
import net.mcless.dev.onlinechat.bridge.WebSessionManager;
import net.mcless.dev.onlinechat.command.OnlineChatCommand;
import net.mcless.dev.onlinechat.config.CommonConfig;
import net.mcless.dev.onlinechat.config.ServerConfig;
import net.mcless.dev.onlinechat.web.WebServer;
import net.minecraft.network.chat.Component;
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
    private WebServer webServer;
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

        Path accountsPath = resolvePath(runDirectory, ServerConfig.ACCOUNTS_FILE.get());
        Path secretPath = accountsPath.resolveSibling("token.secret");

        this.accounts = new AccountManager(accountsPath);
        this.accounts.load();

        this.tokens = new TokenService(accounts, secretPath);
        this.tokens.init();

        this.sessions = new WebSessionManager();
        this.bridge = new ChatBridge(sessions, accounts);
        this.bridge.setServer(server);
        this.bindings = new BindingManager(accounts, bridge);

        NeoForge.EVENT_BUS.register(bridge);

        this.webServer = new WebServer(runDirectory, accounts, tokens, bindings, bridge, sessions);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (webServer != null) webServer.start();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        if (bridge != null) {
            NeoForge.EVENT_BUS.unregister(bridge);
            bridge.setServer(null);
        }
        if (accounts != null) accounts.save();
        this.accounts = null;
        this.tokens = null;
        this.sessions = null;
        this.bridge = null;
        this.bindings = null;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        OnlineChatCommand.register(event.getDispatcher());
    }

    /** Called by {@code /onlinechat reload}. */
    public void reloadWebServer() {
        if (webServer == null) return;
        webServer.stop();
        webServer.start();
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
                bridge.emitWebSystem(acc.getUsername() + " is now bound to Minecraft player " + acc.getBoundPlayerName());
                if (bridge.server() != null) {
                    bridge.emitGameSystem(Component.literal("[OnlineChat] Web user '" + acc.getUsername()
                            + "' bound to " + acc.getBoundPlayerName() + ".").withStyle(net.minecraft.ChatFormatting.GOLD));
                }
            });
        }
    }

    public AccountManager getAccounts() { return accounts; }
    public TokenService getTokens() { return tokens; }
    public BindingManager getBindings() { return bindings; }
    public ChatBridge getBridge() { return bridge; }
    public WebSessionManager getSessions() { return sessions; }
    public WebServer getWebServer() { return webServer; }
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
