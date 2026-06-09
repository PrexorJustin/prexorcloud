package me.prexorjustin.prexorcloud.api.plugin.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the default handler method within a {@link Command} or {@link Sub}
 * class. Invoked when no subcommand argument matches.
 *
 * <p>
 * If absent, the platform adapter auto-generates help text from the registered
 * subcommands and their {@link Sub#description()}.
 *
 * <pre>{@code
 *
 * @Default
 * public void help(CloudSender sender) {
 *     sender.sendMessage("§eUsage: /server <start|stop|list>");
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Default {}
