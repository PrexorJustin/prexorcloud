package me.prexorjustin.prexorcloud.server.shared.bukkit;

import java.util.List;

import me.prexorjustin.prexorcloud.api.plugin.command.Arg;
import me.prexorjustin.prexorcloud.api.plugin.command.CloudCommand;
import me.prexorjustin.prexorcloud.api.plugin.command.CommandContext;
import me.prexorjustin.prexorcloud.plugin.common.AbstractCommandRegistry;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bukkit command-map registry. Shared by Spigot/Paper/Folia — the underlying
 * command map APIs behave identically on all three.
 */
public final class BukkitCommandRegistry extends AbstractCommandRegistry {

    private final JavaPlugin plugin;

    public BukkitCommandRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
        Arg.registerGlobalCompleter(
                "player",
                (ctx, partial) ->
                        Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        Arg.registerPlayerConverter((raw, ctx) -> Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getName().equalsIgnoreCase(raw))
                .findFirst()
                .map(p -> (me.prexorjustin.prexorcloud.api.plugin.player.CloudPlayer) ctx.sender())
                .orElse(null));
    }

    @Override
    protected void registerPlatformCommand(CloudCommand command) {
        var bukkitCommand = new BukkitCloudCommand(command);
        Bukkit.getCommandMap().register(plugin.getName().toLowerCase(), bukkitCommand);
    }

    @Override
    protected void unregisterPlatformCommand(String name) {
        var knownCommands = Bukkit.getCommandMap().getKnownCommands();
        knownCommands.remove(name);
        knownCommands.remove(plugin.getName().toLowerCase() + ":" + name);
    }

    private static final class BukkitCloudCommand extends Command {

        private final CloudCommand cloudCommand;

        BukkitCloudCommand(CloudCommand cloudCommand) {
            super(cloudCommand.name(), "", "/" + cloudCommand.name(), List.of());
            this.cloudCommand = cloudCommand;
            String perm = cloudCommand.permission();
            if (!perm.isEmpty()) {
                setPermission(perm);
            }
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            var cloudSender = new BukkitCommandSender(sender);
            CommandContext ctx = new CommandContext(cloudSender, commandLabel, List.of(args));
            cloudCommand.execute(ctx);
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            var cloudSender = new BukkitCommandSender(sender);
            CommandContext ctx = new CommandContext(cloudSender, alias, List.of(args));
            return cloudCommand.tabComplete(ctx);
        }
    }
}
