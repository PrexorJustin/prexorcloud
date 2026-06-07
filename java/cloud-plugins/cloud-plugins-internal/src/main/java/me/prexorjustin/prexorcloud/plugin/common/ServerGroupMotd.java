package me.prexorjustin.prexorcloud.plugin.common;

import java.util.List;

/**
 * MOTD configuration for a server group. Proxy-only concern; not part of the
 * {@link me.prexorjustin.prexorcloud.api.server.ServerGroup} interface.
 */
public record ServerGroupMotd(List<String> motds, String motdMode, int motdIntervalSeconds) {}
