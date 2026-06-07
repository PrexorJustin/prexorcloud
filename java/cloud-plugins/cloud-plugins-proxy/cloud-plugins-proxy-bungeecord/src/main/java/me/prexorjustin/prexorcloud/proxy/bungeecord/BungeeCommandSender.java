package me.prexorjustin.prexorcloud.proxy.bungeecord;

import me.prexorjustin.prexorcloud.api.plugin.command.CloudCommandSender;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;

final class BungeeCommandSender implements CloudCommandSender {

    private final CommandSender sender;

    BungeeCommandSender(CommandSender sender) {
        this.sender = sender;
    }

    @Override
    public String name() {
        return sender instanceof ProxiedPlayer p ? p.getName() : "CONSOLE";
    }

    @Override
    public boolean isPlayer() {
        return sender instanceof ProxiedPlayer;
    }

    @Override
    public boolean isConsole() {
        return !(sender instanceof ProxiedPlayer);
    }

    @Override
    public void sendMessage(String message) {
        sender.sendMessage(new TextComponent(message));
    }

    @Override
    public boolean hasPermission(String permission) {
        return sender.hasPermission(permission);
    }
}
