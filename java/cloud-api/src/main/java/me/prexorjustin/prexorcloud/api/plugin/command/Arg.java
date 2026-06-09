package me.prexorjustin.prexorcloud.api.plugin.command;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.api.plugin.player.CloudPlayer;

/**
 * Typed argument descriptor — declaration, parser, completer, and typed
 * accessor key in one object.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Declare as constants (static final fields)
 * private static final Arg<CloudPlayer> TARGET = Arg.player("target");
 * private static final Arg<String> REASON = Arg.string("reason").greedy().optional("No reason");
 *
 * // Use in the builder
 * Commands.literal("kick").arg(TARGET).arg(REASON).executes(ctx -> ctx.get(TARGET).kick(ctx.get(REASON)));
 * }</pre>
 *
 * <p>
 * The same {@code Arg<T>} instance used in {@code .arg()} is the key passed to
 * {@link CommandContext#get(Arg)} — no string lookup, no cast, no
 * {@code Optional} unwrapping.
 *
 * @param <T>
 *            the Java type this argument parses to
 */
public final class Arg<T> {

    // ── Global completer registry ─────────────────────────────────────────────
    // Platform adapters call Arg.registerGlobalCompleter("player", ...) at startup.

    private static final Map<String, TabCompleter> GLOBAL_COMPLETERS = new ConcurrentHashMap<>();

    /**
     * Registers a global tab-completer for a named arg kind (e.g. {@code "player"},
     * {@code "group"}, {@code "instance"}). Called by platform adapters at boot.
     */
    public static void registerGlobalCompleter(String kind, TabCompleter completer) {
        GLOBAL_COMPLETERS.put(kind, completer);
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    /**
     * Display name used in usage hints, help pages, and as the key in resolved
     * maps.
     */
    public final String name;

    /**
     * Kind tag used to look up global completers (e.g. {@code "player"}). May be
     * {@code null}.
     */
    final String kind;

    final ArgParser<T> parser;
    final TabCompleter completer; // null = fall back to global completer for kind
    final boolean required;
    final boolean greedy;
    final T defaultValue;

    private Arg(
            String name,
            String kind,
            ArgParser<T> parser,
            TabCompleter completer,
            boolean required,
            boolean greedy,
            T defaultValue) {
        this.name = name;
        this.kind = kind;
        this.parser = parser;
        this.completer = completer;
        this.required = required;
        this.greedy = greedy;
        this.defaultValue = defaultValue;
    }

    // ── Static factories ──────────────────────────────────────────────────────

    public static Arg<String> string(String name) {
        return new Arg<>(name, null, (raw, ctx) -> raw, null, true, false, null);
    }

    public static Arg<Integer> integer(String name) {
        return new Arg<>(
                name,
                null,
                (raw, ctx) -> {
                    try {
                        return Integer.parseInt(raw);
                    } catch (NumberFormatException e) {
                        throw new CommandException("'" + raw + "' is not a valid integer.");
                    }
                },
                null,
                true,
                false,
                null);
    }

    public static Arg<Long> longArg(String name) {
        return new Arg<>(
                name,
                null,
                (raw, ctx) -> {
                    try {
                        return Long.parseLong(raw);
                    } catch (NumberFormatException e) {
                        throw new CommandException("'" + raw + "' is not a valid number.");
                    }
                },
                null,
                true,
                false,
                null);
    }

    public static Arg<Boolean> bool(String name) {
        return new Arg<>(
                name,
                null,
                (raw, ctx) -> switch (raw.toLowerCase()) {
                    case "true", "yes", "on", "1" -> true;
                    case "false", "no", "off", "0" -> false;
                    default -> throw new CommandException("'" + raw + "' is not true or false.");
                },
                (ctx, partial) -> List.of("true", "false"),
                true,
                false,
                null);
    }

    /**
     * Online player name — completer is provided by the platform adapter at boot.
     */
    public static Arg<CloudPlayer> player(String name) {
        return new Arg<>(
                name,
                "player",
                (raw, ctx) -> {
                    @SuppressWarnings("unchecked")
                    var converter = (ArgParser<CloudPlayer>) PLAYER_CONVERTER;
                    if (converter == null) return null;
                    return converter.parse(raw, ctx);
                },
                null,
                true,
                false,
                null);
    }

    /**
     * Group name — completer registered globally; parses as plain {@code String}.
     */
    public static Arg<String> group(String name) {
        return new Arg<>(name, "group", (raw, ctx) -> raw, null, true, false, null);
    }

    /**
     * Instance ID — completer registered globally; parses as plain {@code String}.
     */
    public static Arg<String> instance(String name) {
        return new Arg<>(name, "instance", (raw, ctx) -> raw, null, true, false, null);
    }

    /**
     * Fixed set of choices — tab-completes to exactly those values; parses as
     * {@code String}. Fails parsing if the raw value is not one of the choices.
     */
    public static Arg<String> choices(String name, String... choices) {
        var set = List.of(choices);
        return new Arg<>(
                name,
                null,
                (raw, ctx) -> {
                    if (!set.contains(raw.toLowerCase()))
                        throw new CommandException("'" + raw + "' must be one of: " + String.join(", ", choices));
                    return raw.toLowerCase();
                },
                (ctx, partial) -> set,
                true,
                false,
                null);
    }

    /**
     * Escape hatch — fully custom type with your own parser. No tab completion by
     * default.
     */
    public static <T> Arg<T> of(String name, ArgParser<T> parser) {
        return new Arg<>(name, null, parser, null, true, false, null);
    }

    /** Escape hatch — fully custom type with parser and completer. */
    public static <T> Arg<T> of(String name, ArgParser<T> parser, TabCompleter completer) {
        return new Arg<>(name, null, parser, completer, true, false, null);
    }

    // ── Fluent configuration (immutable — always returns a NEW Arg<T>) ─────────

    /** Makes this argument optional with a {@code null} default value. */
    public Arg<T> optional() {
        return new Arg<>(name, kind, parser, completer, false, greedy, null);
    }

    /** Makes this argument optional with the given default value. */
    public Arg<T> optional(T defaultValue) {
        return new Arg<>(name, kind, parser, completer, false, greedy, defaultValue);
    }

    /**
     * Makes this argument greedy — it consumes all remaining tokens joined by
     * spaces. Must be the last argument declared on the node.
     */
    public Arg<T> greedy() {
        return new Arg<>(name, kind, parser, completer, required, true, defaultValue);
    }

    /** Overrides the tab-completer for this specific instance. */
    public Arg<T> completer(TabCompleter fn) {
        return new Arg<>(name, kind, parser, fn, required, greedy, defaultValue);
    }

    // ── Accessors used by the dispatcher ─────────────────────────────────────

    public boolean isRequired() {
        return required;
    }

    public boolean isGreedy() {
        return greedy;
    }

    public T defaultValue() {
        return defaultValue;
    }

    /**
     * Parses {@code raw} into {@code T}. Throws {@link CommandException} on
     * failure.
     */
    public T parse(String raw, CommandContext ctx) {
        return parser.parse(raw, ctx);
    }

    /** Returns tab-completion suggestions for {@code partial}. */
    public List<String> complete(CommandContext ctx, String partial) {
        if (completer != null) return completer.complete(ctx, partial);
        if (kind != null) {
            TabCompleter global = GLOBAL_COMPLETERS.get(kind);
            if (global != null) return global.complete(ctx, partial);
        }
        return List.of();
    }

    // ── CloudPlayer converter (set by platform adapters) ─────────────────────

    private static volatile ArgParser<?> PLAYER_CONVERTER;

    /**
     * Registers the platform-specific converter for {@link CloudPlayer} arguments.
     * Called once by each platform adapter at startup.
     *
     * <pre>{@code
     * Arg.registerPlayerConverter((raw, ctx) -> playerManager.getPlayer(raw).orElse(null));
     * }</pre>
     */
    public static void registerPlayerConverter(ArgParser<CloudPlayer> converter) {
        PLAYER_CONVERTER = converter;
    }
}
