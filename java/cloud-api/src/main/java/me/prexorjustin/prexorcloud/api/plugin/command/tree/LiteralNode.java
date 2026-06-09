package me.prexorjustin.prexorcloud.api.plugin.command.tree;

import java.util.List;

import me.prexorjustin.prexorcloud.api.plugin.command.CommandHandler;

/**
 * A {@link CommandNode} that matches a fixed keyword (e.g. {@code "kick"},
 * {@code "server"}). May have aliases that also trigger this node.
 */
public final class LiteralNode extends CommandNode {

    private final List<String> aliases;

    public LiteralNode(
            String name,
            List<String> aliases,
            String permission,
            String description,
            CommandHandler executor,
            boolean requiresPlayer,
            String requiresPlayerMsg,
            boolean requiresConsole,
            String requiresConsoleMsg,
            List<CommandNode> children,
            int helpPageSize) {
        super(
                name,
                permission,
                description,
                executor,
                requiresPlayer,
                requiresPlayerMsg,
                requiresConsole,
                requiresConsoleMsg,
                children,
                helpPageSize);
        this.aliases = List.copyOf(aliases);
    }

    /** Alternate names that match this node (lowercase). */
    public List<String> aliases() {
        return aliases;
    }

    /**
     * Returns true if {@code token} matches this node's name or any alias
     * (case-insensitive).
     */
    public boolean matches(String token) {
        if (name().equalsIgnoreCase(token)) return true;
        for (String alias : aliases) {
            if (alias.equalsIgnoreCase(token)) return true;
        }
        return false;
    }
}
