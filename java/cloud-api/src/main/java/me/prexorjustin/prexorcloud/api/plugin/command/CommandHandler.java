package me.prexorjustin.prexorcloud.api.plugin.command;

/**
 * Executes a command node. Throw {@link CommandException} (or call
 * {@link CommandContext#fail}, {@link CommandContext#failIf}, etc.) to abort
 * and send an error message to the sender.
 */
@FunctionalInterface
public interface CommandHandler {

    void execute(CommandContext ctx);
}
