package me.shawlaf.varlight.command.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import me.shawlaf.varlight.VarLightPlugin;
import me.shawlaf.varlight.command.VarLightCommand;
import me.shawlaf.varlight.command.VarLightSubCommand;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.util.ChatPaginator;
import org.jetbrains.annotations.NotNull;

public class VarLightCommandHelp extends VarLightSubCommand {

    private VarLightCommand rootCommand;

    public VarLightCommandHelp(VarLightPlugin varLightPlugin, VarLightCommand rootCommand) {
        super(varLightPlugin, "help");

        this.rootCommand = rootCommand;
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Displays all VarLight sub commands";
    }

    @NotNull
    @Override
    public String getSyntax() {
        return "[command|page]";
    }

    @Override
    protected LiteralArgumentBuilder<CommandSender> build(LiteralArgumentBuilder<CommandSender> literalArgumentBuilder) {

        for (VarLightSubCommand subCommand : rootCommand.getSubCommands()) {
            if (subCommand.getUsageString().isEmpty()) {
                continue;
            }

            literalArgumentBuilder.then(
                    LiteralArgumentBuilder.<CommandSender>literal(subCommand.getName())
                            .requires(sender -> sender.hasPermission(subCommand.getRequiredPermission()))
                            .executes(context -> {
                                context.getSource().sendMessage(subCommand.getUsageString());
                                return 0;
                            })
            );
        }

        literalArgumentBuilder.then(
                RequiredArgumentBuilder.<CommandSender, Integer>argument("page", IntegerArgumentType.integer())
                        .executes(context -> {
                            int page = Math.max(1, context.getArgument("page", int.class));

                            showHelp(context.getSource(), page);
                            return 0;
                        })
        );

        literalArgumentBuilder.executes(
                context -> {
                    showHelp(context.getSource());
                    return 0;
                }
        );

        return literalArgumentBuilder;
    }

    private String getFullHelpRaw(CommandSender commandSender) {
        StringBuilder builder = new StringBuilder();

        for (VarLightSubCommand subCommand : rootCommand.getSubCommands()) {
            String help = subCommand.getUsageString();

            if (!help.isEmpty() && commandSender.hasPermission(subCommand.getRequiredPermission())) {
                builder.append(help).append('\n');
            }
        }

        return builder.toString().trim();
    }

    public void showHelp(CommandSender sender) {
        showHelp(sender, 1);
    }

    public void showHelp(CommandSender sender, int page) {
        ChatPaginator.ChatPage chatPage = ChatPaginator.paginate(getFullHelpRaw(sender), page);

        sender.sendMessage(ChatColor.GRAY + "-----------------------------------");
        sender.sendMessage(String.format("%sVarLight command help: %s[Page %d / %d]", ChatColor.GOLD, ChatColor.RESET, chatPage.getPageNumber(), chatPage.getTotalPages()));

        for (String line : chatPage.getLines()) {
            sender.sendMessage(line);
        }
    }
}
