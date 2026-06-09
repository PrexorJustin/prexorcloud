package me.prexorjustin.prexorcloud.api.plugin.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts a {@link Command}-annotated class to players only. Console callers
 * receive {@link #message()} and execution is aborted.
 *
 * <pre>
 * {@code
 * &#64;Command(name = "message", aliases = {"msg"})
 * &#64;Permission("module.message")
 * &#64;RequirePlayer
 * public class MessageCommand { ... }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePlayer {

    String message() default "§cThis command can only be used by players.";
}
