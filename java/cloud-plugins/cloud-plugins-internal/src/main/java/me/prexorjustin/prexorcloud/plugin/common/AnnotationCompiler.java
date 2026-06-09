package me.prexorjustin.prexorcloud.plugin.common;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import me.prexorjustin.prexorcloud.api.plugin.command.Arg;
import me.prexorjustin.prexorcloud.api.plugin.command.Command;
import me.prexorjustin.prexorcloud.api.plugin.command.CommandContext;
import me.prexorjustin.prexorcloud.api.plugin.command.CommandException;
import me.prexorjustin.prexorcloud.api.plugin.command.Commands;
import me.prexorjustin.prexorcloud.api.plugin.command.Default;
import me.prexorjustin.prexorcloud.api.plugin.command.LiteralBuilder;
import me.prexorjustin.prexorcloud.api.plugin.command.Param;
import me.prexorjustin.prexorcloud.api.plugin.command.Permission;
import me.prexorjustin.prexorcloud.api.plugin.command.RequireConsole;
import me.prexorjustin.prexorcloud.api.plugin.command.RequirePlayer;
import me.prexorjustin.prexorcloud.api.plugin.command.Sub;
import me.prexorjustin.prexorcloud.api.plugin.command.tree.LiteralNode;
import me.prexorjustin.prexorcloud.api.plugin.player.CloudPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compiles {@link Command}-annotated or {@link Sub}-annotated POJOs into
 * immutable {@link LiteralNode} trees — the same structure produced by the
 * builder API.
 *
 * <p>
 * ALL reflection in the command system lives here. {@link CommandDispatcher}
 * and {@link AbstractCommandRegistry} are reflection-free.
 */
public final class AnnotationCompiler {

    private static final Logger logger = LoggerFactory.getLogger(AnnotationCompiler.class);

    private AnnotationCompiler() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Compiles a {@link Command}-annotated POJO into a {@link LiteralNode} tree.
     *
     * @throws IllegalArgumentException
     *             if the class is not annotated with {@link Command}
     */
    public static LiteralNode compile(Object pojo) {
        Class<?> cls = pojo.getClass();
        Command cmdAnn = cls.getAnnotation(Command.class);
        if (cmdAnn == null) throw new IllegalArgumentException(cls.getName() + " is not annotated with @Command");

        LiteralBuilder root = Commands.literal(cmdAnn.name(), cmdAnn.aliases()).description(cmdAnn.description());

        applyClassAnnotations(root, cls, "");
        scanMethods(root, pojo, cls, "");

        return root.build();
    }

    /**
     * Compiles a {@link Sub}-annotated POJO into a {@link LiteralNode} tree. Used
     * when a {@code @Sub} class is registered as a child node.
     */
    public static LiteralNode compileSub(Object pojo) {
        Class<?> cls = pojo.getClass();
        Sub subAnn = cls.getAnnotation(Sub.class);
        if (subAnn == null) throw new IllegalArgumentException(cls.getName() + " is not annotated with @Sub");

        LiteralBuilder builder = Commands.literal(subAnn.value()).description(subAnn.description());

        applyClassAnnotations(builder, cls, "");
        scanMethods(builder, pojo, cls, "");

        return builder.build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void applyClassAnnotations(LiteralBuilder builder, Class<?> cls, String parentPerm) {
        Permission permAnn = cls.getAnnotation(Permission.class);
        if (permAnn != null) builder.permission(permAnn.value());
        else if (!parentPerm.isEmpty()) builder.permission(parentPerm);

        RequirePlayer rp = cls.getAnnotation(RequirePlayer.class);
        if (rp != null) builder.requirePlayer(rp.message());

        RequireConsole rc = cls.getAnnotation(RequireConsole.class);
        if (rc != null) builder.requireConsole(rc.message());
    }

    private static void scanMethods(LiteralBuilder target, Object pojo, Class<?> cls, String parentPerm) {
        for (Method m : cls.getDeclaredMethods()) {
            m.setAccessible(true);

            if (m.isAnnotationPresent(Default.class)) {
                if (!isValidHandler(m, cls)) continue;
                List<Arg<?>> args = buildArgDescriptors(m);
                for (Arg<?> arg : args) target.arg(arg);
                target.executes(ctx -> invoke(m, pojo, ctx, args));
                continue;
            }

            Sub subAnn = m.getAnnotation(Sub.class);
            if (subAnn != null && isValidHandler(m, cls)) {
                String subPerm = resolvePermission(m, parentPerm);
                LiteralBuilder sub = Commands.literal(subAnn.value()).description(subAnn.description());
                if (!subPerm.isEmpty()) sub.permission(subPerm);

                List<Arg<?>> args = buildArgDescriptors(m);
                for (Arg<?> arg : args) sub.arg(arg);
                sub.executes(ctx -> invoke(m, pojo, ctx, args));
                target.then(sub);
            }
        }
    }

    /**
     * Reads {@link Param}-annotated parameters (skipping the first
     * {@link CommandContext} param) and builds the corresponding {@link Arg}
     * descriptors.
     */
    private static List<Arg<?>> buildArgDescriptors(Method m) {
        Parameter[] params = m.getParameters();
        List<Arg<?>> args = new ArrayList<>();
        for (int i = 1; i < params.length; i++) {
            Param paramAnn = params[i].getAnnotation(Param.class);
            if (paramAnn == null) continue;
            Arg<?> arg = buildArg(paramAnn, params[i].getType());
            args.add(arg);
        }
        return args;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Arg<?> buildArg(Param ann, Class<?> type) {
        Arg<?> base;
        if (type == int.class || type == Integer.class) base = Arg.integer(ann.value());
        else if (type == long.class || type == Long.class) base = Arg.longArg(ann.value());
        else if (type == boolean.class || type == Boolean.class) base = Arg.bool(ann.value());
        else if (CloudPlayer.class.isAssignableFrom(type)) base = Arg.player(ann.value());
        else base = Arg.string(ann.value());

        if (ann.greedy()) base = ((Arg) base).greedy();
        if (ann.optional()) base = ((Arg) base).optional();

        return base;
    }

    /**
     * Invokes {@code m} on {@code holder}, resolving typed values from
     * {@code ctx.get(arg)}.
     */
    private static void invoke(Method m, Object holder, CommandContext ctx, List<Arg<?>> argDescriptors) {
        Parameter[] params = m.getParameters();
        Object[] values = new Object[params.length];
        values[0] = ctx;

        int argIndex = 0;
        for (int i = 1; i < params.length; i++) {
            if (params[i].isAnnotationPresent(Param.class) && argIndex < argDescriptors.size()) {
                values[i] = ctx.get(argDescriptors.get(argIndex++));
            } else {
                values[i] = null;
            }
        }

        try {
            m.invoke(holder, values);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CommandException ce) throw ce;
            throw new RuntimeException(
                    "Error in command handler " + holder.getClass().getSimpleName() + "#" + m.getName() + ": "
                            + cause.getMessage(),
                    cause);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot access handler " + m.getName(), e);
        }
    }

    private static boolean isValidHandler(Method m, Class<?> cls) {
        Parameter[] params = m.getParameters();
        if (params.length == 0 || !params[0].getType().equals(CommandContext.class)) {
            logger.warn(
                    "Handler {}.{} must have CommandContext as first parameter — skipped.",
                    cls.getSimpleName(),
                    m.getName());
            return false;
        }
        for (int i = 1; i < params.length; i++) {
            if (!params[i].isAnnotationPresent(Param.class)) {
                logger.warn(
                        "Handler {}.{}: parameter '{}' is not annotated with @Param — skipped.",
                        cls.getSimpleName(),
                        m.getName(),
                        params[i].getName());
                return false;
            }
        }
        return true;
    }

    private static String resolvePermission(Method m, String parentPerm) {
        Permission permAnn = m.getAnnotation(Permission.class);
        return permAnn != null ? permAnn.value() : parentPerm;
    }
}
