package me.prexorjustin.prexorcloud.api.plugin.command;

/**
 * Thrown inside {@link PrexorCloudCommand#handle(CommandContext)} to abort
 * execution and send an error message to the command sender.
 *
 * <p>
 * This is intentional control flow, not an error. The stack trace is suppressed
 * for performance. {@link PrexorCloudCommand#execute(CommandContext)} catches
 * it automatically and sends {@code §c<message>} to the sender.
 *
 * <p>
 * Do not throw this directly — use the helper methods on
 * {@link PrexorCloudCommand}:
 * <ul>
 * <li>{@link PrexorCloudCommand#notifyNull(Object, String)}</li>
 * <li>{@link PrexorCloudCommand#notifyFalse(boolean, String)}</li>
 * <li>{@link PrexorCloudCommand#notifyMissing(java.util.Optional, String)}</li>
 * <li>{@link PrexorCloudCommand#notify(String)}</li>
 * </ul>
 */
public final class CommandException extends RuntimeException {

    private final String commandMessage;

    public CommandException(String commandMessage) {
        super(commandMessage, null, true, false);
        this.commandMessage = commandMessage;
    }

    /** The message to send to the command sender. */
    public String commandMessage() {
        return commandMessage;
    }
}
