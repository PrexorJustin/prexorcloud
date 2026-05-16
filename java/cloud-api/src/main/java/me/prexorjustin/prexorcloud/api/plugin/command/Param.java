package me.prexorjustin.prexorcloud.api.plugin.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a method parameter to a command argument by name, for use with the
 * annotation-based command path ({@link Command}, {@link Sub},
 * {@link Default}). The registry infers the argument type from the Java
 * parameter type automatically.
 *
 * <pre>
 * {@code
 *
 * &#64;Default
 * public void handle(CommandContext ctx, &#64;Param("player") CloudPlayer target,
 *         @Param(value = "reason", greedy = true) String reason) {
 *     target.kick(reason);
 * }
 * }
 * </pre>
 *
 * <p>
 * For the builder path, use {@link Arg} instead — it provides compile-time type
 * safety without annotations or reflection.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Param {

    /** Argument name — used for usage hints and tab-completion registration. */
    String value();

    /**
     * Marks this parameter as optional (not required). If the argument is absent,
     * {@code null} (or the type's zero value for primitives) is injected.
     */
    boolean optional() default false;

    /**
     * Marks this parameter as greedy — consumes all remaining tokens joined by a
     * space. Must be the last {@code @Param} on the method.
     */
    boolean greedy() default false;
}
