package me.prexorjustin.prexorcloud.plugin.common;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.domain.GroupView;
import me.prexorjustin.prexorcloud.api.domain.InstanceState;
import me.prexorjustin.prexorcloud.api.domain.InstanceView;
import me.prexorjustin.prexorcloud.api.domain.NetworkComposition;
import me.prexorjustin.prexorcloud.plugin.common.dto.GroupDto;
import me.prexorjustin.prexorcloud.plugin.common.dto.InstanceDto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a controller-backed cache of instances and groups as unified domain
 * types. An SSE stream owns live replay and resync; the scheduled poll loop is
 * retained only as a fallback while the stream is down. Reads are lock-free via
 * volatile map references. Stale data is retained on refresh failure.
 */
public final class CloudStateCache {

    private static final Logger logger = LoggerFactory.getLogger(CloudStateCache.class);

    /**
     * Called when instances are added, removed, or change state. Used by proxy
     * plugins to sync the backend server map.
     */
    public interface InstanceChangeListener {

        void onInstancesChanged(
                Collection<InstanceView> current, Set<String> added, Set<String> removed, Set<String> becameRunning);
    }

    private final BaseControllerClient client;
    private final long pollIntervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final CloudStateStreamClient streamClient;
    private final List<InstanceChangeListener> listeners = new CopyOnWriteArrayList<>();

    private volatile Map<String, InstanceView> instances = Map.of();
    private volatile Map<String, GroupView> groups = Map.of();
    private volatile Map<String, ServerGroupMotd> groupMotds = Map.of();
    private volatile Map<String, NetworkComposition> networks = Map.of();

    public CloudStateCache(BaseControllerClient client, long pollIntervalSeconds) {
        this.client = client;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.streamClient = new CloudStateStreamClient(client, this);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cloud-state-cache");
            t.setDaemon(true);
            return t;
        });
    }

    public void addListener(InstanceChangeListener listener) {
        listeners.add(listener);
    }

    public void start() {
        refresh();
        streamClient.start();
        if (pollIntervalSeconds > 0) {
            scheduler.scheduleAtFixedRate(
                    this::refreshIfStreamInactive, pollIntervalSeconds, pollIntervalSeconds, TimeUnit.SECONDS);
        }
    }

    public void stop() {
        streamClient.stop();
        scheduler.shutdownNow();
    }

    public void refreshNow() {
        refresh();
    }

    private void refresh() {
        CompletableFuture<Void> instancesFuture = CompletableFuture.runAsync(this::refreshInstances);
        CompletableFuture<Void> groupsFuture = CompletableFuture.runAsync(this::refreshGroups);
        CompletableFuture<Void> networksFuture = CompletableFuture.runAsync(this::refreshNetworks);
        CompletableFuture.allOf(instancesFuture, groupsFuture, networksFuture).join();
    }

    private void refreshIfStreamInactive() {
        if (!streamClient.isStreaming()) {
            refresh();
        }
    }

    void refreshInstancesNow() {
        refreshInstances();
    }

    void refreshGroupsNow() {
        refreshGroups();
    }

    private void refreshInstances() {
        try {
            applyInstanceSnapshot(client.fetchInstances());
        } catch (Exception e) {
            logger.warn("Failed to refresh instance cache: {}", e.getMessage());
        }
    }

    void applyInstanceSnapshot(List<InstanceDto> dtos) {
        Map<String, InstanceView> newCache = new HashMap<>();
        for (InstanceDto dto : dtos) {
            newCache.put(dto.instanceId(), toInstanceView(dto));
        }

        Map<String, InstanceView> oldCache = instances;
        instances = Map.copyOf(newCache);
        notifyInstanceListeners(oldCache, newCache);
    }

    boolean applyInstanceStateDelta(String instanceId, String state) {
        InstanceView current = instances.get(instanceId);
        if (current == null) {
            return false;
        }
        InstanceView updated = new InstanceView(
                current.instanceId(),
                current.group(),
                current.nodeId(),
                current.nodeAddress(),
                parseState(state),
                current.port(),
                current.playerCount(),
                current.uptimeMs(),
                current.startedAt());
        return replaceInstance(current, updated);
    }

    boolean applyInstancePlayerCount(String instanceId, int playerCount) {
        InstanceView current = instances.get(instanceId);
        if (current == null) {
            return false;
        }
        InstanceView updated = new InstanceView(
                current.instanceId(),
                current.group(),
                current.nodeId(),
                current.nodeAddress(),
                current.state(),
                current.port(),
                Math.max(0, playerCount),
                current.uptimeMs(),
                current.startedAt());
        return replaceInstance(current, updated);
    }

    boolean applyInstancePlayerDelta(String instanceId, int delta) {
        InstanceView current = instances.get(instanceId);
        if (current == null) {
            return false;
        }
        return applyInstancePlayerCount(instanceId, current.playerCount() + delta);
    }

    private boolean replaceInstance(InstanceView current, InstanceView updated) {
        Map<String, InstanceView> oldCache = instances;
        if (!current.equals(oldCache.get(current.instanceId()))) {
            return false;
        }
        Map<String, InstanceView> newCache = new HashMap<>(oldCache);
        newCache.put(updated.instanceId(), updated);
        instances = Map.copyOf(newCache);
        notifyInstanceListeners(oldCache, newCache);
        return true;
    }

    private void notifyInstanceListeners(Map<String, InstanceView> oldCache, Map<String, InstanceView> newCache) {
        if (listeners.isEmpty()) {
            return;
        }

        Set<String> oldKeys = oldCache.keySet();
        Set<String> newKeys = newCache.keySet();

        Set<String> added = new HashSet<>(newKeys);
        added.removeAll(oldKeys);
        Set<String> removed = new HashSet<>(oldKeys);
        removed.removeAll(newKeys);

        Set<String> becameRunning = new HashSet<>();
        for (Map.Entry<String, InstanceView> entry : newCache.entrySet()) {
            if (entry.getValue().state() != InstanceState.RUNNING) continue;
            if (added.contains(entry.getKey())) continue;
            InstanceView old = oldCache.get(entry.getKey());
            if (old != null && old.state() != InstanceState.RUNNING) {
                becameRunning.add(entry.getKey());
            }
        }

        if (!added.isEmpty() || !removed.isEmpty() || !becameRunning.isEmpty()) {
            for (InstanceChangeListener listener : listeners) {
                try {
                    listener.onInstancesChanged(newCache.values(), added, removed, becameRunning);
                } catch (Exception e) {
                    logger.warn("Instance change listener error: {}", e.getMessage());
                }
            }
        }
    }

    private void refreshGroups() {
        try {
            applyGroupSnapshot(client.fetchGroups());
        } catch (Exception e) {
            logger.warn("Failed to refresh group cache: {}", e.getMessage());
        }
    }

    private void refreshNetworks() {
        try {
            applyNetworkSnapshot(client.fetchNetworks());
        } catch (Exception e) {
            logger.warn("Failed to refresh network cache: {}", e.getMessage());
        }
    }

    void applyNetworkSnapshot(List<NetworkComposition> snapshot) {
        Map<String, NetworkComposition> newNetworks = new HashMap<>();
        for (NetworkComposition network : snapshot) {
            newNetworks.put(network.name(), network);
        }
        networks = Map.copyOf(newNetworks);
    }

    void applyGroupSnapshot(List<GroupDto> dtos) {
        Map<String, GroupView> newGroups = new HashMap<>();
        Map<String, ServerGroupMotd> newMotds = new HashMap<>();
        for (GroupDto dto : dtos) {
            newGroups.put(dto.name(), toGroupView(dto));
            newMotds.put(
                    dto.name(),
                    new ServerGroupMotd(
                            dto.motds() != null ? dto.motds() : List.of(),
                            dto.motdMode() != null ? dto.motdMode() : "STATIC",
                            dto.motdIntervalSeconds() > 0 ? dto.motdIntervalSeconds() : 30));
        }
        groups = Map.copyOf(newGroups);
        groupMotds = Map.copyOf(newMotds);
    }

    void removeGroup(String groupName) {
        Map<String, GroupView> newGroups = new HashMap<>(groups);
        Map<String, ServerGroupMotd> newMotds = new HashMap<>(groupMotds);
        newGroups.remove(groupName);
        newMotds.remove(groupName);
        groups = Map.copyOf(newGroups);
        groupMotds = Map.copyOf(newMotds);
    }

    boolean applyGroupOnlineDelta(String groupName, int delta) {
        GroupView current = groups.get(groupName);
        if (current == null) {
            return false;
        }
        Map<String, GroupView> newGroups = new HashMap<>(groups);
        newGroups.put(
                groupName,
                new GroupView(
                        current.name(),
                        current.parent(),
                        current.platform(),
                        current.platformVersion(),
                        current.scalingMode(),
                        current.minInstances(),
                        current.maxInstances(),
                        current.maxPlayers(),
                        Math.max(0, current.onlineCount() + delta),
                        current.isStatic(),
                        current.maintenance(),
                        current.maintenanceMessage(),
                        current.maintenanceBypassUuids(),
                        current.isDefaultGroup(),
                        current.nodeAffinity(),
                        current.priority(),
                        current.memoryMb(),
                        current.cpuReservation(),
                        current.diskReservationMb(),
                        current.jvmArgs(),
                        current.env()));
        groups = Map.copyOf(newGroups);
        return true;
    }

    private static InstanceView toInstanceView(InstanceDto dto) {
        return new InstanceView(
                dto.instanceId(),
                dto.group(),
                dto.nodeId(),
                dto.nodeAddress() != null ? dto.nodeAddress() : dto.nodeId(),
                parseState(dto.state()),
                dto.port(),
                dto.playerCount(),
                dto.uptimeMs(),
                dto.startedAt() != null ? dto.startedAt() : Instant.EPOCH);
    }

    private static GroupView toGroupView(GroupDto dto) {
        // GroupView: name, parent, platform, platformVersion, scalingMode,
        // minInstances, maxInstances, maxPlayers, onlineCount,
        // isStatic, maintenance, maintenanceMessage, maintenanceBypassUuids,
        // isDefaultGroup, nodeAffinity, priority, memoryMb, cpuReservation,
        // diskReservationMb, jvmArgs, env
        return new GroupView(
                dto.name(),
                null,
                dto.platform(),
                null,
                "AUTO",
                dto.minInstances(),
                dto.maxInstances(),
                dto.maxPlayers(),
                dto.onlineCount(),
                dto.isStatic(),
                dto.isMaintenance(),
                dto.maintenanceMessage() != null ? dto.maintenanceMessage() : "",
                dto.maintenanceBypass() != null ? dto.maintenanceBypass() : List.of(),
                dto.defaultGroup(),
                dto.nodeAffinity() != null ? dto.nodeAffinity() : List.of(),
                0,
                dto.memoryMb(),
                dto.cpuReservation(),
                dto.diskReservationMb(),
                dto.jvmArgs() != null ? dto.jvmArgs() : List.of(),
                dto.env() != null ? dto.env() : Map.of());
    }

    private static InstanceState parseState(String state) {
        try {
            return InstanceState.valueOf(state);
        } catch (IllegalArgumentException e) {
            return InstanceState.RUNNING;
        }
    }

    public Optional<InstanceView> getInstance(String instanceId) {
        return Optional.ofNullable(instances.get(instanceId));
    }

    public Collection<InstanceView> getAllInstances() {
        return instances.values();
    }

    public Collection<InstanceView> getInstancesByGroup(String group) {
        return instances.values().stream().filter(i -> i.group().equals(group)).toList();
    }

    public Optional<GroupView> getGroup(String name) {
        return Optional.ofNullable(groups.get(name));
    }

    public Collection<GroupView> getAllGroups() {
        return groups.values();
    }

    public Optional<String> getDefaultGroupName() {
        return groups.values().stream()
                .filter(GroupView::isDefaultGroup)
                .map(GroupView::name)
                .findFirst();
    }

    public Optional<NetworkComposition> getNetwork(String name) {
        return Optional.ofNullable(networks.get(name));
    }

    public Collection<NetworkComposition> getAllNetworks() {
        return networks.values();
    }

    /**
     * Resolve the network composition for a proxy group, or empty if none applies.
     * Matches by {@code proxyGroups} first; an entry with empty {@code proxyGroups}
     * is treated as a wildcard and returned only when no group-scoped match exists.
     */
    public Optional<NetworkComposition> getNetworkForProxyGroup(String proxyGroup) {
        NetworkComposition wildcard = null;
        for (NetworkComposition network : networks.values()) {
            if (network.proxyGroups().isEmpty()) {
                if (wildcard == null) wildcard = network;
                continue;
            }
            if (network.proxyGroups().contains(proxyGroup)) {
                return Optional.of(network);
            }
        }
        return Optional.ofNullable(wildcard);
    }

    /**
     * Returns MOTD configuration for a group. Proxy-only concern; not part of
     * {@link GroupView}.
     */
    public Optional<ServerGroupMotd> getGroupMotd(String groupName) {
        return Optional.ofNullable(groupMotds.get(groupName));
    }
}
