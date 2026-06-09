package me.prexorjustin.prexorcloud.api.plugin.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a root command.
 *
 * <p>
 * Usage:
 *
 * <pre>{@code
 * @Command(name = "server", aliases = {"sv"}, description = "Manage server instances")
 * @Permission("cloud.server")
 * public class ServerCommand {
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Command {

    /** Primary command name (e.g. "server"). */
    String name();

    /** Optional aliases (e.g. {"sv", "servers"}). */
    String[] aliases() default {};

    /** Description shown in auto-generated help. */
    String description() default "";
}
