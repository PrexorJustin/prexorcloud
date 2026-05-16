package me.prexorjustin.prexorcloud.api.plugin.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as the tab-completion provider for a {@link Sub} method or the
 * root {@link Default} handler, for use with the annotation-based command path.
 *
 * <p>
 * The annotated method must return {@code List<String>} and accept a single
 * {@link CommandContext} parameter.
 *
 * <p>
 * {@link #value()} must match the name of the corresponding {@link Sub}. Omit
 * the value (or use {@code ""}) to provide completions for the root
 * {@link Default} handler.
 *
 * <pre>{@code
 * &#64;Sub(value = "send", description = "Send a message")
 * public void send(CommandContext ctx) { ... }
 *
 * &#64;SubCompleter("send")
 * public List<String> completeSend(CommandContext ctx) {
 *     return plugin.getOnlinePlayerNames();
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SubCompleter {

    /**
     * The {@link Sub} name this method provides completions for. Empty string
     * targets the root {@link Default} handler.
     */
    String value() default "";
}
