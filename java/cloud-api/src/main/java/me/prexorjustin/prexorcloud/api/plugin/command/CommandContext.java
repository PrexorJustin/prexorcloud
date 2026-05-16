package me.prexorjustin.prexorcloud.api.plugin.command;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Holds the context for a command execution: who sent it, the label used, the
 * raw token list, and — once the dispatcher has matched the command tree — the
 * resolved typed arguments.
 *
 * <h2>Typed argument access (builder path)</h2>
 *
 * <pre>{@code
 * private static final Arg<CloudPlayer> TARGET = Arg.player("target");
 *
 * .executes(ctx -> {
 *     CloudPlayer player = ctx.get(TARGET);  // typed, no cast, no Optional
 * })
 * }</pre>
 *
 * <h2>Validation helpers</h2>
 *
 * <pre>{@code
 * ctx.failIf(player.equalsIgnoreCase(ctx.sender().name()), "Can't target yourself!");
 * ctx.require(value, "Value must not be null");
 * }</pre>
 */
public final class CommandContext {

    private final CloudCommandSender sender;
    private final String label;
    private final List<String> args;

    /**
     * Populated by
     * {@link me.prexorjustin.prexorcloud.plugin.common.CommandDispatcher}
     * immediately before the handler is called. {@code null} until then.
     */
    private Map<Arg<?>, Object> resolved;

    /**
     * Constructor used by all four platform adapters — signature is unchanged.
     * {@code resolved} is populated later by the dispatcher.
     */
    public CommandContext(CloudCommandSender sender, String label, List<String> args) {
        this.sender = sender;
        this.label = label;
        this.args = List.copyOf(args);
    }

    // ── Standard accessors ────────────────────────────────────────────────────

    public CloudCommandSender sender() {
        return sender;
    }

    public String label() {
        return label;
    }

    public List<String> args() {
        return args;
    }

    // ── Index-based accessors (annotation path / backwards compat) ────────────

    /** Returns the argument at {@code index}, or empty if out of bounds. */
    public Optional<String> arg(int index) {
        return index < args.size() ? Optional.of(args.get(index)) : Optional.empty();
    }

    /** Returns the argument at {@code index} or throws if missing. */
    public String requireArg(int index) {
        if (index >= args.size()) throw new IllegalArgumentException("Missing argument at index " + index);
        return args.get(index);
    }

    /** Joins all arguments from {@code fromIndex} onwards with a space. */
    public String joinArgs(int fromIndex) {
        if (fromIndex >= args.size()) return "";
        return String.join(" ", args.subList(fromIndex, args.size()));
    }

    public int argInt(int index, int defaultValue) {
        return arg(index)
                .map(s -> {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    public long argLong(int index, long defaultValue) {
        return arg(index)
                .map(s -> {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    public boolean argBoolean(int index, boolean defaultValue) {
        return arg(index)
                .map(s -> switch (s.toLowerCase()) {
                    case "true", "yes", "on", "1" -> Boolean.TRUE;
                    case "false", "no", "off", "0" -> Boolean.FALSE;
                    default -> defaultValue;
                })
                .orElse(defaultValue);
    }

    // ── Typed accessors (builder path) ────────────────────────────────────────

    /**
     * Returns the typed value for {@code arg}, resolved before the handler was
     * called. Returns {@code arg.defaultValue()} if the arg was optional and
     * absent.
     *
     * @throws IllegalStateException
     *             if called before arg resolution (only happens with
     *             annotation-path commands that don't use the dispatcher)
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Arg<T> arg) {
        if (resolved == null)
            throw new IllegalStateException("ctx.get() called before argument resolution. "
                    + "Ensure the command was registered via the builder path or AnnotationCompiler.");
        Object value = resolved.get(arg);
        if (value == null) return arg.defaultValue();
        return (T) value;
    }

    /**
     * Returns the typed value for {@code arg}, or {@code fallback} if absent or not
     * resolved.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(Arg<T> arg, T fallback) {
        if (resolved == null) return fallback;
        Object value = resolved.get(arg);
        return value != null ? (T) value : fallback;
    }

    // ── Validation helpers ────────────────────────────────────────────────────

    public void fail(String message) {
        throw new CommandException(message);
    }

    public void failIf(boolean condition, String message) {
        if (condition) throw new CommandException(message);
    }

    public void failUnless(boolean condition, String message) {
        if (!condition) throw new CommandException(message);
    }

    public <T> T require(T value, String message) {
        if (value == null) throw new CommandException(message);
        return value;
    }

    // ── Package-private: called only by CommandDispatcher ─────────────────────

    public void setResolved(Map<Arg<?>, Object> resolved) {
        if (this.resolved != null) throw new IllegalStateException("CommandContext resolved map already set.");
        this.resolved = Map.copyOf(resolved);
    }
}
