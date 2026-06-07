package me.prexorjustin.prexorcloud.api.plugin.command;

/**
 * Registry for plugin commands. Commands are forwarded to the platform's native
 * command system (Brigadier on Paper, equivalent on Velocity/Bungee).
 *
 * <h2>Builder path — unlimited depth, type-safe args (recommended)</h2>
 *
 * <pre>{@code
 * private static final Arg<CloudPlayer> TARGET = Arg.player("target");
 * private static final Arg<String> REASON = Arg.string("reason").greedy().optional("No reason");
 *
 * ctx.commands().register(Commands.literal("cloud").permission("cloud.admin").then(Commands.literal("player").then(
 *         Commands.literal("kick").arg(TARGET).arg(REASON).executes(ctx -> ctx.get(TARGET).kick(ctx.get(REASON))))));
 * }</pre>
 *
 * <h2>Annotation path — POJO-based, zero boilerplate</h2>
 *
 * <pre>{@code
 * ctx.commands().register(new MessageCommand(plugin));
 * }</pre>
 *
 * <h2>Mixed — annotated POJOs nested anywhere in a builder tree</h2>
 *
 * <pre>{@code
 * ctx.commands().register(Commands.literal("cloud").then(Commands.node(new MemberCommand(svc))) // @Sub annotated class
 * );
 * }</pre>
 */
public interface CloudCommandRegistry {

    // ── Builder path ──────────────────────────────────────────────────────────

    /**
     * Registers a {@link LiteralBuilder} tree. Supports unlimited nesting via
     * {@link LiteralBuilder#then(LiteralBuilder)}. {@link Commands#node(Object)}
     * can be used to slot annotation-based classes at any depth.
     */
    void register(LiteralBuilder builder);

    // ── Annotation path ───────────────────────────────────────────────────────

    /**
     * Registers a {@link Command}-annotated POJO. Compiled by
     * {@code AnnotationCompiler} to the same node tree as the builder path. Use
     * {@link Commands#node(Object)} to nest annotation-based classes inside a
     * builder tree instead.
     */
    void register(Object pojo);

    // ── Programmatic path ─────────────────────────────────────────────────────

    /**
     * Registers a raw {@link CloudCommand}. Use for platform-native integrations or
     * one-off programmatic commands.
     */
    void register(CloudCommand command);

    // ── Unregister ────────────────────────────────────────────────────────────

    void unregister(String name);
}
