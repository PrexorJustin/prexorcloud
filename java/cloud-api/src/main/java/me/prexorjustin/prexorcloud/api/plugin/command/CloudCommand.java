package me.prexorjustin.prexorcloud.api.plugin.command;

import java.util.List;

/**
 * A platform-agnostic command that can be registered with
 * {@link CloudCommandRegistry}.
 */
public interface CloudCommand {

    String name();

    /**
     * Permission required to execute this command. Empty string means no permission
     * required.
     */
    String permission();

    void execute(CommandContext ctx);

    default List<String> tabComplete(CommandContext ctx) {
        return List.of();
    }
}
