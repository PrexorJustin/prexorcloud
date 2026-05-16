package me.prexorjustin.prexorcloud.api.plugin.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import me.prexorjustin.prexorcloud.api.plugin.command.tree.ArgumentNode;
import me.prexorjustin.prexorcloud.api.plugin.command.tree.CommandNode;
import me.prexorjustin.prexorcloud.api.plugin.command.tree.LiteralNode;

/**
 * Fluent builder for a {@link LiteralNode} command tree. Obtain via
 * {@link Commands#literal(String, String...)}.
 *
 * <pre>{@code
 * Commands.literal("kick").permission("cloud.kick").requirePlayer().arg(TARGET).arg(REASON)
 *         .executes(ctx -> ctx.get(TARGET).kick(ctx.get(REASON)));
 * }</pre>
 *
 * <p>
 * Nesting is unlimited — call {@link #then(LiteralBuilder)} at any depth:
 *
 * <pre>{@code
 * Commands.literal("cloud")
 *     .then(Commands.literal("server")
 *         .then(Commands.literal("group")
 *             .then(Commands.literal("create").arg(NAME).executes(ctx -> ...))));
 * }</pre>
 *
 * <p>
 * Annotated POJOs can be slotted at any depth via
 * {@link Commands#node(Object)}:
 *
 * <pre>{@code
 * Commands.literal("cloud").then(Commands.node(new MemberCommand(svc)));
 * }</pre>
 */
public class LiteralBuilder {

    private final String name;
    private final List<String> aliases = new ArrayList<>();
    private String permission = "";
    private String description = "";
    private boolean requiresPlayer;
    private String requiresPlayerMsg = "§cThis command can only be used by players.";
    private boolean requiresConsole;
    private String requiresConsoleMsg = "§cThis command can only be used from the console.";
    private CommandHandler executor;
    private final List<Arg<?>> args = new ArrayList<>();
    private final List<LiteralBuilder> literalChildren = new ArrayList<>();
    private int helpPageSize = 8;

    LiteralBuilder(String name, String[] aliases) {
        this.name = name;
        this.aliases.addAll(Arrays.asList(aliases));
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    public LiteralBuilder permission(String permission) {
        this.permission = permission;
        return this;
    }

    public LiteralBuilder alias(String... aliases) {
        this.aliases.addAll(Arrays.asList(aliases));
        return this;
    }

    public LiteralBuilder description(String description) {
        this.description = description;
        return this;
    }

    // ── Sender guards ─────────────────────────────────────────────────────────

    public LiteralBuilder requirePlayer() {
        this.requiresPlayer = true;
        return this;
    }

    public LiteralBuilder requirePlayer(String failMessage) {
        this.requiresPlayer = true;
        this.requiresPlayerMsg = failMessage;
        return this;
    }

    public LiteralBuilder requireConsole() {
        this.requiresConsole = true;
        return this;
    }

    public LiteralBuilder requireConsole(String failMessage) {
        this.requiresConsole = true;
        this.requiresConsoleMsg = failMessage;
        return this;
    }

    // ── Arguments ─────────────────────────────────────────────────────────────

    /**
     * Declares a typed argument for this node. Arguments are consumed in
     * declaration order. Validated at {@link #build()} time:
     * <ul>
     * <li>Required arg may not follow an optional arg.</li>
     * <li>Greedy arg must be last.</li>
     * <li>Only one argument chain is allowed (no sibling args after a greedy
     * one).</li>
     * </ul>
     */
    public <T> LiteralBuilder arg(Arg<T> arg) {
        args.add(arg);
        return this;
    }

    // ── Handler ───────────────────────────────────────────────────────────────

    /**
     * Sets the executor invoked when this exact path is matched. If not set, the
     * dispatcher auto-generates a help page listing direct literal children.
     */
    public LiteralBuilder executes(CommandHandler handler) {
        this.executor = handler;
        return this;
    }

    // ── Children ──────────────────────────────────────────────────────────────

    /** Adds a child literal node. Depth is unlimited. */
    public LiteralBuilder then(LiteralBuilder child) {
        literalChildren.add(child);
        return this;
    }

    // ── Help ──────────────────────────────────────────────────────────────────

    public LiteralBuilder helpPageSize(int size) {
        this.helpPageSize = size;
        return this;
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    /**
     * Seals this builder and produces an immutable {@link LiteralNode} tree. Called
     * automatically by the registry; plugin authors rarely need to call this
     * directly.
     *
     * @throws IllegalStateException
     *             if argument ordering constraints are violated
     */
    public LiteralNode build() {
        validateArgs();

        // Build children: literal children first, then the arg chain (if any)
        List<CommandNode> children = new ArrayList<>();

        // Recursively build literal children
        for (LiteralBuilder child : literalChildren) {
            children.add(child.build());
        }

        // If there are args, build a chain of ArgumentNodes
        // Each ArgumentNode wraps the next arg, with the final one holding the executor
        if (!args.isEmpty()) {
            children.add(buildArgChain(0, executor));
        }

        CommandHandler nodeExecutor = args.isEmpty() ? executor : null;

        return new LiteralNode(
                name,
                aliases,
                permission,
                description,
                nodeExecutor,
                requiresPlayer,
                requiresPlayerMsg,
                requiresConsole,
                requiresConsoleMsg,
                children,
                helpPageSize);
    }

    /**
     * Recursively builds a chain of {@link ArgumentNode}s for the declared args.
     * The last node in the chain carries the executor.
     */
    private ArgumentNode<?> buildArgChain(int index, CommandHandler terminalExecutor) {
        Arg<?> arg = args.get(index);
        boolean isLast = index == args.size() - 1;
        CommandHandler exec = isLast ? terminalExecutor : null;
        List<CommandNode> childNodes = new ArrayList<>();
        if (!isLast) {
            childNodes.add(buildArgChain(index + 1, terminalExecutor));
        }
        return buildArgumentNode(arg, exec, childNodes);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentNode<T> buildArgumentNode(Arg<T> arg, CommandHandler exec, List<CommandNode> children) {
        return new ArgumentNode<>(arg, exec, false, null, false, null, children, 8);
    }

    private void validateArgs() {
        boolean seenOptional = false;
        for (int i = 0; i < args.size(); i++) {
            Arg<?> arg = args.get(i);
            if (arg.isGreedy() && i != args.size() - 1) {
                throw new IllegalStateException("Command '" + name + "': greedy arg '" + arg.name + "' must be last.");
            }
            if (arg.isRequired() && seenOptional) {
                throw new IllegalStateException(
                        "Command '" + name + "': required arg '" + arg.name + "' may not follow an optional arg.");
            }
            if (!arg.isRequired()) seenOptional = true;
        }
    }

    // ── Package-private accessors used by AnnotationCompiler ─────────────────

    String getName() {
        return name;
    }

    List<String> getAliases() {
        return aliases;
    }

    String getPermission() {
        return permission;
    }

    String getDescription() {
        return description;
    }

    boolean isRequiresPlayer() {
        return requiresPlayer;
    }

    String getRequiresPlayerMsg() {
        return requiresPlayerMsg;
    }

    boolean isRequiresConsole() {
        return requiresConsole;
    }

    String getRequiresConsoleMsg() {
        return requiresConsoleMsg;
    }
}
