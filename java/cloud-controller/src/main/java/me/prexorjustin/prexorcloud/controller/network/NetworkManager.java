package me.prexorjustin.prexorcloud.controller.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.api.domain.NetworkComposition;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogStore;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory CRUD for {@link NetworkComposition} with cross-reference validation
 * against existing groups. Mirrors the {@link GroupManager} pattern: writes go
 * through {@link #create}/{@link #update}/{@link #delete}, the optional
 * {@link NetworkStore} is invoked by callers (e.g. REST routes) for persistence,
 * and {@link #reloadNetwork}/{@link #removeNetworkFromCache} are used by the
 * Redis event bridge for cross-controller cache invalidation.
 */
public final class NetworkManager {

    private static final Logger logger = LoggerFactory.getLogger(NetworkManager.class);
    private static final int MAX_NAME_LENGTH = 32;
    private static final int MAX_DESCRIPTION_LENGTH = 256;
    private static final int MAX_KICK_MESSAGE_LENGTH = 256;

    private final Map<String, NetworkComposition> networks = new ConcurrentHashMap<>();
    private final GroupManager groupManager;
    private final CatalogStore catalogStore;
    private volatile NetworkStore networkStore;

    public NetworkManager(GroupManager groupManager, CatalogStore catalogStore) {
        this.groupManager = groupManager;
        this.catalogStore = catalogStore;
    }

    public void setNetworkStore(NetworkStore networkStore) {
        this.networkStore = networkStore;
    }

    public void create(NetworkComposition network) {
        validate(network);
        if (networks.containsKey(network.name())) {
            throw new IllegalArgumentException("Network already exists: " + network.name());
        }
        networks.put(network.name(), network);
        logger.info(
                "Network created: {} (lobby={}, fallbacks={}, proxies={})",
                network.name(),
                network.lobbyGroup(),
                network.fallbackGroups(),
                network.proxyGroups());
    }

    public void update(NetworkComposition network) {
        validate(network);
        if (!networks.containsKey(network.name())) {
            throw new IllegalArgumentException("Network not found: " + network.name());
        }
        networks.put(network.name(), network);
        logger.info("Network updated: {}", network.name());
    }

    public void delete(String name) {
        if (networks.remove(name) != null) {
            logger.info("Network deleted: {}", name);
        }
    }

    public Optional<NetworkComposition> get(String name) {
        return Optional.ofNullable(networks.get(name));
    }

    public Collection<NetworkComposition> getAll() {
        return Collections.unmodifiableCollection(networks.values());
    }

    public boolean exists(String name) {
        return networks.containsKey(name);
    }

    /** Reload one network from the backing store; used by the Redis event bridge. */
    public void reloadNetwork(String name) {
        if (networkStore == null) return;
        try {
            for (var network : networkStore.loadAll()) {
                if (network.name().equals(name)) {
                    networks.put(name, network);
                    logger.debug("Reloaded network from store: {}", name);
                    return;
                }
            }
            networks.remove(name);
        } catch (Exception e) {
            logger.warn("Failed to reload network {}: {}", name, e.getMessage());
        }
    }

    /** Remove a network from the in-memory cache; used by the Redis event bridge. */
    public void removeNetworkFromCache(String name) {
        networks.remove(name);
        logger.debug("Removed network from cache: {}", name);
    }

    private void validate(NetworkComposition network) {
        if (network.name().length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Network name too long (max " + MAX_NAME_LENGTH + "): " + network.name());
        }
        if (!network.name().matches("[a-z0-9_][a-z0-9_-]*")) {
            throw new IllegalArgumentException(
                    "Invalid network name: " + network.name() + " (must match [a-z0-9_][a-z0-9_-]*)");
        }
        if (network.description().length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("description too long (max " + MAX_DESCRIPTION_LENGTH + ")");
        }
        if (network.kickMessage().length() > MAX_KICK_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("kickMessage too long (max " + MAX_KICK_MESSAGE_LENGTH + ")");
        }

        if (!groupManager.exists(network.lobbyGroup())) {
            throw new IllegalArgumentException("lobbyGroup not found: " + network.lobbyGroup());
        }

        var fallbackSeen = new HashSet<String>();
        for (String fallback : network.fallbackGroups()) {
            if (fallback == null || fallback.isBlank()) {
                throw new IllegalArgumentException("fallbackGroups must not contain blank values");
            }
            if (!groupManager.exists(fallback)) {
                throw new IllegalArgumentException("fallbackGroups entry not found: " + fallback);
            }
            if (fallback.equals(network.lobbyGroup())) {
                throw new IllegalArgumentException("fallbackGroups must not include lobbyGroup '" + network.lobbyGroup()
                        + "' (lobby is the implicit last-resort fallback)");
            }
            if (!fallbackSeen.add(fallback)) {
                throw new IllegalArgumentException("fallbackGroups contains duplicate: " + fallback);
            }
        }

        for (String member : network.memberGroups()) {
            if (member == null || member.isBlank()) {
                throw new IllegalArgumentException("memberGroups must not contain blank values");
            }
            if (!groupManager.exists(member)) {
                throw new IllegalArgumentException("memberGroups entry not found: " + member);
            }
        }
        // memberGroups is a set in spirit — reject duplicates so callers don't get surprised.
        if (network.memberGroups().size() != Set.copyOf(network.memberGroups()).size()) {
            throw new IllegalArgumentException("memberGroups contains duplicates");
        }

        var proxyPlatforms = collectProxyPlatforms();
        for (String proxy : network.proxyGroups()) {
            if (proxy == null || proxy.isBlank()) {
                throw new IllegalArgumentException("proxyGroups must not contain blank values");
            }
            var groupOpt = groupManager.get(proxy);
            if (groupOpt.isEmpty()) {
                throw new IllegalArgumentException("proxyGroups entry not found: " + proxy);
            }
            String platform = groupOpt.get().platform();
            if (platform != null && !platform.isBlank() && !proxyPlatforms.contains(platform.toUpperCase())) {
                throw new IllegalArgumentException(
                        "proxyGroups entry '" + proxy + "' is not a proxy platform (got " + platform + ")");
            }
        }
        if (network.proxyGroups().size() != Set.copyOf(network.proxyGroups()).size()) {
            throw new IllegalArgumentException("proxyGroups contains duplicates");
        }
    }

    private Set<String> collectProxyPlatforms() {
        var result = new HashSet<String>();
        try {
            for (var entry : catalogStore.getAll()) {
                if (entry.isProxy()) result.add(entry.platform().toUpperCase());
            }
        } catch (Exception e) {
            // Catalog reads are best-effort during validation; an empty set means we
            // can't enforce the proxy-platform check, which is safer than blocking writes.
            logger.debug("catalog read failed during network validation: {}", e.getMessage());
        }
        return result;
    }

    /** Snapshot used by REST handlers; defensively copies the value list. */
    public List<NetworkComposition> snapshot() {
        return new ArrayList<>(networks.values());
    }
}
