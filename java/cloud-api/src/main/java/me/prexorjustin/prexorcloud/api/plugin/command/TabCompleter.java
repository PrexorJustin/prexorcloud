package me.prexorjustin.prexorcloud.api.plugin.command;

import java.util.List;

/**
 * Provides tab-completion suggestions for an {@link Arg}.
 *
 * <p>
 * {@code partial} is the token the sender has typed so far (may be empty).
 * Implementations should return strings that start with {@code partial}
 * (case-insensitive filtering is applied by the dispatcher after this call).
 */
@FunctionalInterface
public interface TabCompleter {

    List<String> complete(CommandContext ctx, String partial);
}
