package me.prexorjustin.prexorcloud.api.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only data snapshot of a connected player. Used by module developers and
 * as the data projection for
 * {@link me.prexorjustin.prexorcloud.api.plugin.player.CloudPlayer}.
 *
 * <p>
 * {@code proxyInstanceId} and {@code connectedAt} may be {@code null} when
 * accessed from within a game server plugin (not available server-side).
 * </p>
 *
 * @param uuid
 *            player's UUID
 * @param name
 *            player's current display name
 * @param instanceId
 *            the game-server instance the player is on
 * @param group
 *            the group the player's current instance belongs to
 * @param proxyInstanceId
 *            the proxy instance the player connected through ({@code null} on
 *            server side)
 * @param connectedAt
 *            when the player joined the network ({@code null} on server side)
 */
public record PlayerView(
        UUID uuid, String name, String instanceId, String group, String proxyInstanceId, Instant connectedAt) {}
