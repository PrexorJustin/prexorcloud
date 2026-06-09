package me.prexorjustin.prexorcloud.api.plugin.player;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Provides access to players currently online on this server instance. */
public interface PlayerManager {

    Optional<CloudPlayer> getPlayer(UUID uniqueId);

    Optional<CloudPlayer> getPlayer(String name);

    Collection<CloudPlayer> onlinePlayers();

    int onlineCount();
}
