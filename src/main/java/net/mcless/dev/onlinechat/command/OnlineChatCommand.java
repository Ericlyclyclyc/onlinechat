package net.mcless.dev.onlinechat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.mcless.dev.onlinechat.OnlineChat;
import net.mcless.dev.onlinechat.account.Account;
import net.mcless.dev.onlinechat.auth.TwoFactorGuard;
import net.mcless.dev.onlinechat.bridge.BindingManager;
import net.mcless.dev.onlinechat.config.ServerConfig;
import net.mcless.dev.onlinechat.i18n.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * /onlinechat command tree:
 * <ul>
 *   <li>{@code /onlinechat bind confirm <code>} — run automatically when a player clicks [Yes]</li>
 *   <li>{@code /onlinechat bind deny <code>}    — run automatically when a player clicks [No]</li>
 *   <li>{@code /onlinechat status}              — show current binding status (player only)</li>
 *   <li>{@code /onlinechat unbind}              — remove your binding (player only)</li>
 *   <li>{@code /onlinechat reload}              — op-only, reload language + restart the web server</li>
 *   <li>{@code /onlinechat account setpassword <user> <password>} — op-only, reset a web account password</li>
 *   <li>{@code /onlinechat account delete <user>} — op-only, delete a web account (unbinds its player)</li>
 * </ul>
 * All text is rendered server-side through {@link Lang} so vanilla clients see it in the configured language.
 */
public class OnlineChatCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("onlinechat")
                .requires(src -> src.hasPermission(0))
                .then(Commands.literal("bind")
                        .then(Commands.literal("confirm")
                                .then(Commands.argument("code", StringArgumentType.word())
                                        .executes(OnlineChatCommand::confirmBind)))
                        .then(Commands.literal("deny")
                                .then(Commands.argument("code", StringArgumentType.word())
                                        .executes(OnlineChatCommand::denyBind))))
                .then(Commands.literal("status").executes(OnlineChatCommand::status))
                .then(Commands.literal("unbind").executes(OnlineChatCommand::unbind))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .executes(OnlineChatCommand::reload))
                .then(Commands.literal("account")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("setpassword")
                                .then(Commands.argument("username", StringArgumentType.word())
                                        .then(Commands.argument("password", StringArgumentType.greedyString())
                                                .executes(OnlineChatCommand::accountSetPassword))))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("username", StringArgumentType.word())
                                        .executes(OnlineChatCommand::accountDelete)))));
    }

    /** Sent by {@link BindingManager} when a web user requests a binding. */
    public static void sendBindPrompt(ServerPlayer player, String webUsername, String code) {
        MutableComponent body = Lang.component("onlinechat.command.bind.prompt",
                net.minecraft.network.chat.Style.EMPTY.withColor(ChatFormatting.YELLOW),
                Component.literal("'" + webUsername + "'").withStyle(ChatFormatting.AQUA),
                Lang.text("onlinechat.command.bind.code", code).withStyle(ChatFormatting.GRAY));

        MutableComponent yes = Component.literal(" ").append(Lang.text("onlinechat.command.bind.yes")).append(" ")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/onlinechat bind confirm " + code))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Lang.text("onlinechat.command.bind.yes.hover"))));
        MutableComponent no = Component.literal(" ").append(Lang.text("onlinechat.command.bind.no")).append(" ")
                .withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/onlinechat bind deny " + code))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Lang.text("onlinechat.command.bind.no.hover"))));

        player.sendSystemMessage(prefix().withStyle(ChatFormatting.BOLD).append(body));
        player.sendSystemMessage(Component.literal("  ").append(yes).append(no));
    }

    private static int confirmBind(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Lang.text("onlinechat.command.playerOnly"));
            return 0;
        }
        String code = StringArgumentType.getString(ctx, "code");
        OnlineChat runtime = OnlineChat.instance();
        if (runtime == null) return 0;
        BindingManager bindings = runtime.getBindings();
        Optional<BindingManager.PendingBind> opt = bindings.confirm(player.getServer(), player, code);
        if (opt.isEmpty()) {
            player.sendSystemMessage(prefix().append(Lang.text("onlinechat.command.bind.expired").withStyle(ChatFormatting.RED)));
            return 0;
        }
        BindingManager.PendingBind pb = opt.get();
        player.sendSystemMessage(prefix().append(Lang.text("onlinechat.command.bind.confirmed", pb.webUsername).withStyle(ChatFormatting.GREEN)));
        runtime.notifyWebBindResult(pb, true, null);
        return 1;
    }

    private static int denyBind(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        String code = StringArgumentType.getString(ctx, "code");
        OnlineChat runtime = OnlineChat.instance();
        if (runtime == null) return 0;
        Optional<BindingManager.PendingBind> opt = runtime.getBindings().deny(player, code);
        if (opt.isEmpty()) {
            player.sendSystemMessage(prefix().append(Lang.text("onlinechat.command.bind.expired").withStyle(ChatFormatting.RED)));
            return 0;
        }
        BindingManager.PendingBind pb = opt.get();
        player.sendSystemMessage(prefix().append(Lang.text("onlinechat.command.bind.denied", pb.webUsername).withStyle(ChatFormatting.YELLOW)));
        runtime.notifyWebBindResult(pb, false, "denied");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendSuccess(() -> Lang.text("onlinechat.command.status.running"), false);
            return 1;
        }
        OnlineChat runtime = OnlineChat.instance();
        if (runtime == null) return 0;
        Optional<Account> acc = runtime.getAccounts().byPlayerUuid(player.getUUID());
        if (acc.isEmpty()) {
            player.sendSystemMessage(prefix().append(Lang.text("onlinechat.command.status.unbound").withStyle(ChatFormatting.YELLOW)));
        } else {
            Account a = acc.get();
            player.sendSystemMessage(prefix().append(Lang.text("onlinechat.command.status.bound", a.getUsername()).withStyle(ChatFormatting.GREEN)));
            if (TwoFactorGuard.featureEnabled()) {
                String key = a.isTwoFactorEnabled() ? "onlinechat.command.status.twoFactorOn" : "onlinechat.command.status.twoFactorOff";
                player.sendSystemMessage(prefix().append(Lang.text(key).withStyle(ChatFormatting.GRAY)));
            }
        }
        return 1;
    }

    private static int unbind(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        OnlineChat runtime = OnlineChat.instance();
        if (runtime == null) return 0;
        Optional<Account> acc = runtime.getAccounts().byPlayerUuid(player.getUUID());
        if (acc.isEmpty()) {
            player.sendSystemMessage(prefix().append(Lang.text("onlinechat.command.unbind.nothing").withStyle(ChatFormatting.YELLOW)));
            return 0;
        }
        runtime.unbindAccount(acc.get());
        player.sendSystemMessage(prefix().append(Lang.text("onlinechat.command.unbind.ok").withStyle(ChatFormatting.GREEN)));
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        OnlineChat runtime = OnlineChat.instance();
        if (runtime == null) return 0;
        runtime.reloadWebServer();
        ctx.getSource().sendSuccess(() -> prefix().append(Lang.text("onlinechat.command.reload.ok")), true);
        return 1;
    }

    // ─────────────────────────── Admin: web account management ───────────────────────────

    private static int accountSetPassword(CommandContext<CommandSourceStack> ctx) {
        OnlineChat runtime = OnlineChat.instance();
        if (runtime == null) return 0;
        String username = StringArgumentType.getString(ctx, "username");
        String password = StringArgumentType.getString(ctx, "password").trim();
        Optional<Account> acc = runtime.getAccounts().byUsername(username);
        if (acc.isEmpty()) {
            ctx.getSource().sendFailure(prefix().append(Lang.text("onlinechat.command.account.notFound", username)));
            return 0;
        }
        int min = ServerConfig.MIN_PASSWORD_LENGTH.get();
        if (password.length() < min) {
            ctx.getSource().sendFailure(prefix().append(Lang.text("onlinechat.command.account.passwordShort", min)));
            return 0;
        }
        // PBKDF2 takes a noticeable moment; keep it off the server tick thread.
        Account target = acc.get();
        CommandSourceStack source = ctx.getSource();
        Thread worker = new Thread(() -> {
            runtime.changePassword(target, password);
            var srv = source.getServer();
            srv.execute(() -> source.sendSuccess(
                    () -> prefix().append(Lang.text("onlinechat.command.account.passwordSet", target.getUsername())
                            .withStyle(ChatFormatting.GREEN)), true));
        }, "OnlineChat-setpassword");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static int accountDelete(CommandContext<CommandSourceStack> ctx) {
        OnlineChat runtime = OnlineChat.instance();
        if (runtime == null) return 0;
        String username = StringArgumentType.getString(ctx, "username");
        Optional<Account> acc = runtime.getAccounts().byUsername(username);
        if (acc.isEmpty()) {
            ctx.getSource().sendFailure(prefix().append(Lang.text("onlinechat.command.account.notFound", username)));
            return 0;
        }
        Account target = acc.get();
        String boundName = target.getBoundPlayerName();
        runtime.deleteAccount(target);
        String suffix = boundName == null ? "" : Lang.tr("onlinechat.command.account.deleted.unbound", boundName);
        ctx.getSource().sendSuccess(() -> prefix().append(
                Lang.text("onlinechat.command.account.deleted", target.getUsername(), suffix).withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private static MutableComponent prefix() {
        return Lang.text("onlinechat.prefix").withStyle(ChatFormatting.GOLD);
    }
}
