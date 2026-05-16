package me.prexorjustin.prexorcloud.plugin.common;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.api.plugin.command.CloudCommand;
import me.prexorjustin.prexorcloud.api.plugin.command.CloudCommandRegistry;
import me.prexorjustin.prexorcloud.api.plugin.command.Command;
import me.prexorjustin.prexorcloud.api.plugin.command.CommandContext;
import me.prexorjustin.prexorcloud.api.plugin.command.DeferredAnnotationBuilder;
import me.prexorjustin.prexorcloud.api.plugin.command.LiteralBuilder;
import me.prexorjustin.prexorcloud.api.plugin.command.Sub;
import me.prexorjustin.prexorcloud.api.plugin.command.tree.LiteralNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform-agnostic command registry backed by a recursive
 * {@link CommandDispatcher}.
 *
 * <h3>Registration paths</h3>
 * <ol>
 * <li><strong>Builder path</strong> — {@link #register(LiteralBuilder)}:
 * unlimited depth, type-safe
 * {@link me.prexorjustin.prexorcloud.api.plugin.command.Arg Arg&lt;T&gt;},
 * reflection-free execution.</li>
 * <li><strong>Annotation path</strong> — {@link #register(Object)}: POJO
 * annotated with {@link Command}; compiled by {@link AnnotationCompiler} to the
 * same node tree.</li>
 * <li><strong>Programmatic path</strong> — {@link #register(CloudCommand)}: raw
 * {@link CloudCommand} for special-case or platform-native integration.</li>
 * </ol>
 *
 * <p>
 * Both the builder and annotation paths run through the same
 * {@link CommandDispatcher} instance. There is only one execution engine.
 */
public abstract class AbstractCommandRegistry implements CloudCommandRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCommandRegistry.class);

    private final Map<String, CloudCommand> commands = new ConcurrentHashMap<>();
    private final CommandDispatcher dispatcher = new CommandDispatcher();

    // ── Builder path ──────────────────────────────────────────────────────────

    @Override
    public void register(LiteralBuilder builder) {
        LiteralNode root = resolve(builder);
        storeAndRegister(wrap(root));

        // Register aliases as forwarding entries
        for (String alias : root.aliases()) {
            storeAndRegister(forwardingCommand(alias, root.permission(), wrap(root)));
        }
    }

    // ── Annotation path ───────────────────────────────────────────────────────

    @Override
    public void register(Object pojo) {
        Class<?> cls = pojo.getClass();
        LiteralNode node;
        if (cls.isAnnotationPresent(Command.class)) {
            node = AnnotationCompiler.compile(pojo);
        } else if (cls.isAnnotationPresent(Sub.class)) {
            node = AnnotationCompiler.compileSub(pojo);
        } else {
            throw new IllegalArgumentException(cls.getName() + " must be annotated with @Command or @Sub");
        }

        storeAndRegister(wrap(node));
        for (String alias : node.aliases()) {
            storeAndRegister(forwardingCommand(alias, node.permission(), wrap(node)));
        }
    }

    // ── Programmatic path ─────────────────────────────────────────────────────

    @Override
    public void register(CloudCommand command) {
        storeAndRegister(command);
    }

    // ── Unregister ────────────────────────────────────────────────────────────

    @Override
    public void unregister(String name) {
        String key = name.toLowerCase();
        CloudCommand removed = commands.remove(key);
        if (removed != null) {
            unregisterPlatformCommand(key);
        } else {
            logger.warn("Tried to unregister unknown command '{}'.", key);
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Optional<CloudCommand> getCommand(String name) {
        return Optional.ofNullable(commands.get(name.toLowerCase()));
    }

    public java.util.Collection<CloudCommand> getAll() {
        return commands.values();
    }

    // ── Platform hooks (implemented by each platform adapter) ─────────────────

    protected abstract void registerPlatformCommand(CloudCommand command);

    protected abstract void unregisterPlatformCommand(String name);

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Resolves a {@link LiteralBuilder}, expanding any
     * {@link DeferredAnnotationBuilder} nodes recursively before calling
     * {@link LiteralBuilder#build()}.
     */
    private LiteralNode resolve(LiteralBuilder builder) {
        if (builder instanceof DeferredAnnotationBuilder deferred) {
            Object pojo = deferred.annotatedPojo();
            Class<?> cls = pojo.getClass();
            if (cls.isAnnotationPresent(Command.class)) return AnnotationCompiler.compile(pojo);
            if (cls.isAnnotationPresent(Sub.class)) return AnnotationCompiler.compileSub(pojo);
            throw new IllegalArgumentException(cls.getName() + " passed to Commands.node() must be @Command or @Sub");
        }
        // Standard builder — build() recursively seals all children
        return builder.build();
    }

    private CloudCommand wrap(LiteralNode node) {
        return new CloudCommand() {

            @Override
            public String name() {
                return node.name();
            }

            @Override
            public String permission() {
                return node.permission();
            }

            @Override
            public void execute(CommandContext ctx) {
                dispatcher.dispatch(node, ctx);
            }

            @Override
            public List<String> tabComplete(CommandContext ctx) {
                return dispatcher.tabComplete(node, ctx);
            }
        };
    }

    private void storeAndRegister(CloudCommand cmd) {
        String key = cmd.name().toLowerCase();
        if (commands.containsKey(key)) {
            logger.warn("Command '{}' is already registered — overwriting.", key);
        }
        commands.put(key, cmd);
        registerPlatformCommand(cmd);
    }

    private static CloudCommand forwardingCommand(String alias, String permission, CloudCommand delegate) {
        return new CloudCommand() {

            @Override
            public String name() {
                return alias;
            }

            @Override
            public String permission() {
                return permission;
            }

            @Override
            public void execute(CommandContext ctx) {
                delegate.execute(ctx);
            }

            @Override
            public List<String> tabComplete(CommandContext ctx) {
                return delegate.tabComplete(ctx);
            }
        };
    }
}
