package net.mcless.dev.onlinechat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.mcless.dev.onlinechat.OnlineChat;
import net.mcless.dev.onlinechat.account.Account;
import net.mcless.dev.onlinechat.bridge.BindingManager;
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
 *   <li>{@code /onlinechat unbind}              — remove your binding (player only, op-only fallback)</li>
 *   <li>{@code /onlinechat reload}              — op-only, restart the web server with fresh config</li>
 * </ul>
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
                        .executes(OnlineChatCommand::reload)));
    }

    /** Sent by {@link BindingManager} when a web user requests a binding. */
    public static void sendBindPrompt(ServerPlayer player, String webUsername, String code) {
        MutableComponent header = Component.literal("[OnlineChat] ")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        MutableComponent body = Component.literal("Web user ")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("'" + webUsername + "'").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" wants to bind to your Minecraft account. ")
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("(Code: " + code + ")").withStyle(ChatFormatting.GRAY));

        MutableComponent yes = Component.literal(" [Yes] ")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/onlinechat bind confirm " + code))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to accept this binding"))));
        MutableComponent no = Component.literal(" [No] ")
                .withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/onlinechat bind deny " + code))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to reject this binding"))));

        player.sendSystemMessage(header.copy().append(body));
        player.sendSystemMessage(Component.literal("  ").append(yes).append(no));
    }

    private static int confirmBind(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("This command can only be used by players."));
            return 0;
        }
        String code = StringArgumentType.getString(ctx, "code");
        OnlineChat runtime = OnlineChat.instance();
        if (runtime == null) return 0;
        BindingManager bindings = runtime.getBindings();
        Optional<BindingManager.PendingBind> opt = bindings.confirm(player.getServer(), player, code);
        if (opt.isEmpty()) {
            player.sendSystemMessage(Component.literal("[OnlineChat] ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("That binding code is invalid or expired.").withStyle(ChatFormatting.RED)));
            return 0;
        }
        BindingManager.PendingBind pb = opt.get();
        player.sendSystemMessage(Component.literal("[OnlineChat] ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Your account is now bound to web user '" + pb.webUsername + "'.")
                        .withStyle(ChatFormatting.GREEN)));
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
            player.sendSystemMessage(Component.literal("[OnlineChat] ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("That binding code is invalid or expired.").withStyle(ChatFormatting.RED)));
            return 0;
        }
        BindingManager.PendingBind pb = opt.get();
        player.sendSystemMessage(Component.literal("[OnlineChat] ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Binding request from '" + pb.webUsername + "' denied.").withStyle(ChatFormatting.YELLOW)));
        runtime.notifyWebBindResult(pb, false, "denied");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("OnlineChat is running."), false);
            return 1;
        }
        OnlineChat runtime = OnlineChat.instance();
        if (runtime == null) return 0;
        Optional<Account> acc = runtime.getAccounts().byPlayerUuid(player.getUUID());
        if (acc.isEmpty()) {
            player.sendSystemMessage(Component.literal("[OnlineChat] ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("Your Minecraft account is not bound to any web account yet.")
                            .withStyle(ChatFormatting.YELLOW)));
        } else {
            Account a = acc.get();
            player.sendSystemMessage(Component.literal("[OnlineChat] ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("Bound to web account '" + a.getUsername() + "'.")
                            .withStyle(ChatFormatting.GREEN)));
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
            player.sendSystemMessage(Component.literal("[OnlineChat] Nothing to unbind.").withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        runtime.getAccounts().unbind(acc.get());
        player.sendSystemMessage(Component.literal("[OnlineChat] Binding removed.").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        OnlineChat runtime = OnlineChat.instance();
        if (runtime == null) return 0;
        runtime.reloadWebServer();
        ctx.getSource().sendSuccess(() -> Component.literal("[OnlineChat] Web server reloaded."), true);
        return 1;
    }
}
