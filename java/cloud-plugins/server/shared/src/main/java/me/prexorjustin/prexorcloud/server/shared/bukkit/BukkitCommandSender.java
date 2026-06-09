package me.prexorjustin.prexorcloud.server.shared.bukkit;

import me.prexorjustin.prexorcloud.api.plugin.command.CloudCommandSender;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

/**
 * Wraps a Bukkit {@link CommandSender} as a {@link CloudCommandSender}.
 */
public final class BukkitCommandSender implements CloudCommandSender {

    private final CommandSender sender;

    public BukkitCommandSender(CommandSender sender) {
        this.sender = sender;
    }

    @Override
    public String name() {
        return sender.getName();
    }

    @Override
    public boolean isPlayer() {
        return sender instanceof Player;
    }

    @Override
    public boolean isConsole() {
        return sender instanceof ConsoleCommandSender;
    }

    @Override
    public void sendMessage(String message) {
        sender.sendMessage(message);
    }

    @Override
    public boolean hasPermission(String permission) {
        return sender.hasPermission(permission);
    }
}
