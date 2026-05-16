package me.prexorjustin.prexorcloud.api.plugin.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission required to execute a command or subcommand. Can be
 * placed on a {@link Command} class, a {@link Sub} class, or a {@link Sub}
 * method.
 *
 * <p>
 * Inheritance: if a {@code @Sub} method or class has no {@code @Permission}, it
 * inherits the parent {@code @Command}'s permission.
 *
 * <pre>{@code
 * &#64;Command(name = "server")
 * &#64;Permission("cloud.server")           // base permission
 * public class ServerCommand {
 *
 *     &#64;Sub("list")                      // inherits "cloud.server"
 *     public void list(CloudSender sender) { ... }
 *
 *     &#64;Sub("delete")
 *     &#64;Permission("cloud.server.delete") // overrides
 *     public void delete(CloudSender sender, @Arg("name") String name) { ... }
 * }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Permission {

    /** Permission node (e.g. "cloud.server.start"). */
    String value();
}
