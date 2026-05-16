package me.prexorjustin.prexorcloud.api.plugin.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts a {@link Command}-annotated class to console only. Player callers
 * receive {@link #message()} and execution is aborted.
 *
 * <pre>
 * {@code
 * &#64;Command(name = "shutdown")
 * &#64;Permission("cloud.admin.shutdown")
 * &#64;RequireConsole
 * public class ShutdownCommand { ... }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireConsole {

    String message() default "§cThis command can only be used from the console.";
}
