package me.prexorjustin.prexorcloud.api.plugin.command.tree;

import java.util.List;

import me.prexorjustin.prexorcloud.api.plugin.command.Arg;
import me.prexorjustin.prexorcloud.api.plugin.command.CommandHandler;

/**
 * A {@link CommandNode} that consumes one (or all remaining, if greedy) tokens
 * and parses them into a typed value via
 * {@link Arg#parse(String, me.prexorjustin.prexorcloud.api.plugin.command.CommandContext)}.
 *
 * <p>
 * A command node may have at most one {@code ArgumentNode} child, and it must
 * come after all {@link LiteralNode} children. If the arg is greedy, it must be
 * a leaf (no further children).
 *
 * @param <T>
 *            the Java type the argument parses to
 */
public final class ArgumentNode<T> extends CommandNode {

    private final Arg<T> arg;

    public ArgumentNode(
            Arg<T> arg,
            CommandHandler executor,
            boolean requiresPlayer,
            String requiresPlayerMsg,
            boolean requiresConsole,
            String requiresConsoleMsg,
            List<CommandNode> children,
            int helpPageSize) {
        super(
                arg.name,
                "",
                "",
                executor,
                requiresPlayer,
                requiresPlayerMsg,
                requiresConsole,
                requiresConsoleMsg,
                children,
                helpPageSize);
        this.arg = arg;
    }

    /**
     * The typed argument descriptor powering parsing, completion, and context
     * extraction.
     */
    public Arg<T> arg() {
        return arg;
    }
}
