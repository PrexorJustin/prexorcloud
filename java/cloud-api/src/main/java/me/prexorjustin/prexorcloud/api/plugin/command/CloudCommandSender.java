package me.prexorjustin.prexorcloud.api.plugin.command;

/** Represents the sender of a cloud command — a player or the console. */
public interface CloudCommandSender {

    String name();

    boolean isPlayer();

    boolean isConsole();

    void sendMessage(String message);

    boolean hasPermission(String permission);
}
