package me.prexorjustin.prexorcloud.proxy.bungeecord;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.api.plugin.command.Arg;
import me.prexorjustin.prexorcloud.api.plugin.command.CloudCommand;
import me.prexorjustin.prexorcloud.api.plugin.command.CommandContext;
import me.prexorjustin.prexorcloud.plugin.common.AbstractCommandRegistry;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.TabExecutor;

final class BungeeCommandRegistry extends AbstractCommandRegistry {

    private final Plugin plugin;

    /**
     * Holds the native BungeeCord command objects so they can be unregistered by
     * name.
     */
    private final Map<String, Command> nativeCommands = new ConcurrentHashMap<>();

    BungeeCommandRegistry(Plugin plugin) {
        this.plugin = plugin;
        Arg.registerGlobalCompleter(
                "player",
                (ctx, partial) -> plugin.getProxy().getPlayers().stream()
                        .map(ProxiedPlayer::getName)
                        .toList());
    }

    @Override
    protected void registerPlatformCommand(CloudCommand command) {
        var bungeeCmd = new BungeeCloudCommand(command);
        nativeCommands.put(command.name().toLowerCase(), bungeeCmd);
        plugin.getProxy().getPluginManager().registerCommand(plugin, bungeeCmd);
    }

    @Override
    protected void unregisterPlatformCommand(String name) {
        Command cmd = nativeCommands.remove(name);
        if (cmd != null) {
            plugin.getProxy().getPluginManager().unregisterCommand(cmd);
        }
    }

    private static final class BungeeCloudCommand extends Command implements TabExecutor {

        private final CloudCommand command;

        BungeeCloudCommand(CloudCommand command) {
            super(command.name(), command.permission().isEmpty() ? null : command.permission());
            this.command = command;
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            var cloudSender = new BungeeCommandSender(sender);
            var ctx = new CommandContext(cloudSender, getName(), List.of(args));
            command.execute(ctx);
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            var cloudSender = new BungeeCommandSender(sender);
            var ctx = new CommandContext(cloudSender, getName(), List.of(args));
            return command.tabComplete(ctx);
        }
    }
}
