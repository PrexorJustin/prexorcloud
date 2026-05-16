package me.prexorjustin.prexorcloud.api.plugin.command.tree;

import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.plugin.command.CommandHandler;

/**
 * An immutable node in the command tree. Produced by
 * {@link me.prexorjustin.prexorcloud.api.plugin.command.LiteralBuilder#build()}.
 * The {@link me.prexorjustin.prexorcloud.plugin.common.CommandDispatcher} walks
 * this tree recursively.
 *
 * <p>
 * The sealed hierarchy has exactly two subtypes:
 * <ul>
 * <li>{@link LiteralNode} — matches a fixed keyword (e.g. {@code "kick"})</li>
 * <li>{@link ArgumentNode} — matches any token and parses it to a typed
 * value</li>
 * </ul>
 */
public abstract sealed class CommandNode permits LiteralNode, ArgumentNode {

    private final String name;
    private final String permission;
    private final String description;
    private final CommandHandler executor; // null = auto-generate help page
    private final boolean requiresPlayer;
    private final String requiresPlayerMsg;
    private final boolean requiresConsole;
    private final String requiresConsoleMsg;
    private final List<CommandNode> children; // literals come before argument nodes
    private final int helpPageSize;

    CommandNode(
            String name,
            String permission,
            String description,
            CommandHandler executor,
            boolean requiresPlayer,
            String requiresPlayerMsg,
            boolean requiresConsole,
            String requiresConsoleMsg,
            List<CommandNode> children,
            int helpPageSize) {
        this.name = name;
        this.permission = permission;
        this.description = description;
        this.executor = executor;
        this.requiresPlayer = requiresPlayer;
        this.requiresPlayerMsg = requiresPlayerMsg;
        this.requiresConsole = requiresConsole;
        this.requiresConsoleMsg = requiresConsoleMsg;
        this.children = List.copyOf(children);
        this.helpPageSize = helpPageSize;
    }

    public String name() {
        return name;
    }

    public String permission() {
        return permission;
    }

    public String description() {
        return description;
    }

    /**
     * Handler to invoke when this exact path is matched. {@code null} = show
     * auto-help.
     */
    public CommandHandler executor() {
        return executor;
    }

    public boolean requiresPlayer() {
        return requiresPlayer;
    }

    public String requiresPlayerMsg() {
        return requiresPlayerMsg;
    }

    public boolean requiresConsole() {
        return requiresConsole;
    }

    public String requiresConsoleMsg() {
        return requiresConsoleMsg;
    }

    /** All children: literals first, then the optional argument node. */
    public List<CommandNode> children() {
        return children;
    }

    public int helpPageSize() {
        return helpPageSize;
    }

    // ── Convenience views used by the dispatcher ──────────────────────────────

    public List<LiteralNode> literalChildren() {
        return children.stream()
                .filter(c -> c instanceof LiteralNode)
                .map(c -> (LiteralNode) c)
                .toList();
    }

    public Optional<ArgumentNode<?>> argumentChild() {
        return children.stream()
                .filter(c -> c instanceof ArgumentNode<?>)
                .<ArgumentNode<?>>map(c -> (ArgumentNode<?>) c)
                .findFirst();
    }
}
