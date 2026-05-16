package me.prexorjustin.prexorcloud.api.plugin.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a subcommand. Can be placed on a method (inline sub) or on a class
 * (standalone sub, for large command trees).
 *
 * <p>
 * Method-level (Pattern A — single class):
 *
 * <pre>{@code
 * &#64;Sub(value = "send", description = "Send a message")
 * public void send(CloudSender sender, @Arg("target") String target) { ... }
 * }</pre>
 *
 * <p>
 * Class-level (Pattern B — multi-class):
 *
 * <pre>
 * {@code
 * &#64;Sub(value = "start", description = "Start a server instance")
 * public class ServerStartCommand {
 *     &#64;Default
 *     public void execute(CloudSender sender, @Arg("group") String group) { ... }
 * }
 * }
 * </pre>
 *
 * <p>
 * A {@code @Sub} class may itself contain {@code @Sub} methods for 2-level deep
 * trees.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Sub {

    /** Subcommand name (e.g. "start"). */
    String value();

    /** Description shown in auto-generated help. */
    String description() default "";
}
