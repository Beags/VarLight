package me.florian.varlight.command.commands;

import me.florian.varlight.command.ArgumentIterator;
import me.florian.varlight.command.VarLightCommand;
import me.florian.varlight.command.VarLightSubCommand;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.util.ChatPaginator;

public class VarLightCommandHelp implements VarLightSubCommand {

    private final VarLightCommand baseCommand;

    public VarLightCommandHelp(VarLightCommand baseCommand) {
        this.baseCommand = baseCommand;
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getSyntax() {
        return " [command / page]";
    }

    @Override
    public String getDescription() {
        return "List available VarLight commands or look up a certain command";
    }

    @Override
    public boolean execute(CommandSender sender, ArgumentIterator args) {

        if (args.hasNext()) {

            final String argument = args.next();
            int page = -1;

            try {
                page = Integer.parseInt(argument);
            } catch (NumberFormatException e) {
                // Ignore
            }

            if (page >= 1) {
                showHelp(sender, page);
                return true;
            }

            VarLightSubCommand subCommand = baseCommand.getRegisteredCommands().get(argument);

            if (subCommand == null) {
                VarLightSubCommand.sendPrefixedMessage(sender, String.format("The subcommand \"/varlight %s\" does not exist", args.previous()));
                return true;
            }

            sender.sendMessage(subCommand.getCommandHelp());
            return true;
        }

        showHelp(sender);
        return true;
    }

    public void showHelp(CommandSender sender) {
        showHelp(sender, 1);
    }

    public void showHelp(CommandSender sender, int page) {
        ChatPaginator.ChatPage chatPage = ChatPaginator.paginate(getFullHelpRaw(), page);

        sender.sendMessage(ChatColor.GRAY + "-----------------------------------");
        sender.sendMessage(String.format("%sVarLight command help: %s[Page %d / %d]", ChatColor.GOLD, ChatColor.RESET, chatPage.getPageNumber(), chatPage.getTotalPages()));

        for (String line : chatPage.getLines()) {
            sender.sendMessage(line);
        }
    }

    public String getFullHelpRaw() {
        StringBuilder builder = new StringBuilder();

        for (VarLightSubCommand subCommand : baseCommand.getRegisteredCommands().values()) {
            String help = subCommand.getCommandHelp();

            if (help != null) {
                builder.append(help).append("\n");
            }
        }

        return builder.toString().trim();
    }


}