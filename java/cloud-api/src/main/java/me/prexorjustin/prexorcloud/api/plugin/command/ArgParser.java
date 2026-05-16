package me.prexorjustin.prexorcloud.api.plugin.command;

/**
 * Parses a raw string token into a typed value {@code T}. Throw
 * {@link CommandException} to abort execution and send an error to the sender.
 *
 * @param <T>
 *            the target type
 */
@FunctionalInterface
public interface ArgParser<T> {

    T parse(String raw, CommandContext ctx) throws CommandException;
}
