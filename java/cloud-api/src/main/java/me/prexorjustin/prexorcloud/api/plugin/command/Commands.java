package me.prexorjustin.prexorcloud.api.plugin.command;

/**
 * Entry point for the command builder API.
 *
 * <h2>Builder-based commands (unlimited depth)</h2>
 *
 * <pre>{@code
 * private static final Arg<CloudPlayer> TARGET = Arg.player("target");
 * private static final Arg<String> REASON = Arg.string("reason").greedy().optional("No reason");
 *
 * ctx.commands().register(
 *         Commands.literal("cloud", "cl").permission("cloud.admin").then(Commands.literal("player").then(Commands
 *                 .literal("kick").arg(TARGET).arg(REASON).executes(ctx -> ctx.get(TARGET).kick(ctx.get(REASON))))));
 * }</pre>
 *
 * <h2>Annotation-based POJOs nested in a builder tree</h2>
 *
 * <pre>{@code
 * Commands.literal("cloud").then(Commands.node(new MemberCommand(svc)));
 * }</pre>
 *
 * <h2>Pure annotation-based (no builder at all)</h2>
 *
 * <pre>{@code
 * ctx.commands().register(new MessageCommand(plugin));
 * }</pre>
 */
public final class Commands {

    private Commands() {}

    /**
     * Creates a new {@link LiteralBuilder} for a root or child literal command
     * node.
     *
     * @param name
     *            the primary command name (e.g. {@code "kick"})
     * @param aliases
     *            optional alternate names (e.g. {@code "k"})
     */
    public static LiteralBuilder literal(String name, String... aliases) {
        return new LiteralBuilder(name, aliases);
    }

    /**
     * Compiles a {@code @Command} or {@code @Sub}-annotated POJO into a
     * {@link LiteralBuilder}, allowing annotation-based classes to be slotted at
     * any depth in a builder tree.
     *
     * <pre>{@code
     * Commands.literal("cloud").then(Commands.literal("server").then(Commands.node(new GroupCommand(svc)))); // @Sub
     *                                                                                                        // annotated
     *                                                                                                        // class,
     *                                                                                                        // depth 2
     * }</pre>
     *
     * <p>
     * The POJO is compiled by {@code AnnotationCompiler} — the same path used by
     * {@link CloudCommandRegistry#register(Object)}.
     */
    public static LiteralBuilder node(Object annotatedPojo) {
        // Compilation is deferred to avoid a direct dependency on AnnotationCompiler
        // (which lives in cloud-plugins/internal). The registry resolves this when
        // build() is called — see AbstractCommandRegistry.resolveNodeBuilder().
        return new DeferredAnnotationBuilder(annotatedPojo);
    }
}
