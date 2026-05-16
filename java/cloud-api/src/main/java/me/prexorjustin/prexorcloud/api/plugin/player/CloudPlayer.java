package me.prexorjustin.prexorcloud.api.plugin.player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import me.prexorjustin.prexorcloud.api.domain.PlayerView;

/** Represents a player currently online on this instance. */
public interface CloudPlayer {

    UUID uniqueId();

    String name();

    String currentInstanceId();

    String currentGroup();

    PlayerView toView();

    void sendMessage(String message);

    /** Transfers this player to any available instance in {@code targetGroup}. */
    CompletableFuture<Boolean> transfer(String targetGroup);

    /** Transfers this player to a specific instance by ID. */
    CompletableFuture<Boolean> transferTo(String targetInstanceId);

    void kick(String reason);
}
