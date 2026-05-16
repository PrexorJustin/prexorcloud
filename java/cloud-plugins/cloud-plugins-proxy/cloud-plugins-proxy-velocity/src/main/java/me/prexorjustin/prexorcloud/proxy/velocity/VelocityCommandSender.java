package me.prexorjustin.prexorcloud.proxy.velocity;

import me.prexorjustin.prexorcloud.api.plugin.command.CloudCommandSender;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

final class VelocityCommandSender implements CloudCommandSender {

    private final CommandSource source;

    VelocityCommandSender(CommandSource source) {
        this.source = source;
    }

    @Override
    public String name() {
        return source instanceof Player p ? p.getUsername() : "CONSOLE";
    }

    @Override
    public boolean isPlayer() {
        return source instanceof Player;
    }

    @Override
    public boolean isConsole() {
        return source instanceof ConsoleCommandSource;
    }

    @Override
    public void sendMessage(String message) {
        source.sendMessage(Component.text(message));
    }

    @Override
    public boolean hasPermission(String permission) {
        return source.hasPermission(permission);
    }
}
