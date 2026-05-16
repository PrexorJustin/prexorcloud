package me.prexorjustin.prexorcloud.proxy.velocity;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import me.prexorjustin.prexorcloud.api.plugin.command.Arg;
import me.prexorjustin.prexorcloud.api.plugin.command.CloudCommand;
import me.prexorjustin.prexorcloud.api.plugin.command.CommandContext;
import me.prexorjustin.prexorcloud.plugin.common.AbstractCommandRegistry;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

final class VelocityCommandRegistry extends AbstractCommandRegistry {

    private final CommandManager commandManager;

    VelocityCommandRegistry(CommandManager commandManager, ProxyServer server) {
        this.commandManager = commandManager;
        Arg.registerGlobalCompleter(
                "player",
                (ctx, partial) ->
                        server.getAllPlayers().stream().map(Player::getUsername).toList());
    }

    @Override
    protected void registerPlatformCommand(CloudCommand command) {
        SimpleCommand velocityCmd = new SimpleCommand() {

            @Override
            public void execute(Invocation invocation) {
                var sender = new VelocityCommandSender(invocation.source());
                var ctx = new CommandContext(sender, invocation.alias(), List.of(invocation.arguments()));
                command.execute(ctx);
            }

            @Override
            public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
                var sender = new VelocityCommandSender(invocation.source());
                var ctx = new CommandContext(sender, invocation.alias(), List.of(invocation.arguments()));
                return CompletableFuture.completedFuture(command.tabComplete(ctx));
            }

            @Override
            public boolean hasPermission(Invocation invocation) {
                String perm = command.permission();
                return perm.isEmpty() || invocation.source().hasPermission(perm);
            }
        };

        CommandMeta meta = commandManager.metaBuilder(command.name()).build();
        commandManager.register(meta, velocityCmd);
    }

    @Override
    protected void unregisterPlatformCommand(String name) {
        commandManager.unregister(name);
    }
}
