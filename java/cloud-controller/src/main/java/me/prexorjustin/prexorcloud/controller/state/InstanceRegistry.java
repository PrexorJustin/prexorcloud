package me.prexorjustin.prexorcloud.controller.state;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.protocol.InstanceState;

public final class InstanceRegistry {

    private final Map<String, InstanceInfo> instances = new ConcurrentHashMap<>();
    private final Map<String, InstanceMetrics> instanceMetrics = new ConcurrentHashMap<>();
    private final Map<String, ProxyMetrics> proxyMetrics = new ConcurrentHashMap<>();

    public void hydrate(Map<String, InstanceInfo> snapshot) {
        instances.clear();
        instanceMetrics.clear();
        proxyMetrics.clear();
        instances.putAll(snapshot);
    }

    public void add(InstanceInfo instance) {
        instances.put(instance.id(), instance);
    }

    public void remove(String instanceId) {
        instances.remove(instanceId);
        instanceMetrics.remove(instanceId);
        proxyMetrics.remove(instanceId);
    }

    public Optional<InstanceInfo> get(String instanceId) {
        return Optional.ofNullable(instances.get(instanceId));
    }

    public Collection<InstanceInfo> getAll() {
        return Collections.unmodifiableCollection(instances.values());
    }

    public List<InstanceInfo> getByGroup(String group) {
        return instances.values().stream()
                .filter(instance -> instance.group().equals(group))
                .toList();
    }

    public List<InstanceInfo> getByNode(String nodeId) {
        return instances.values().stream()
                .filter(instance -> instance.nodeId().equals(nodeId))
                .toList();
    }

    public InstanceInfo updateState(String instanceId, InstanceState newState) {
        return instances.computeIfPresent(instanceId, (ignored, existing) -> existing.withState(newState));
    }

    public InstanceInfo updateStatus(String instanceId, InstanceState state, int playerCount, long uptimeMs) {
        return instances.computeIfPresent(
                instanceId, (ignored, existing) -> existing.withStatus(state, playerCount, uptimeMs));
    }

    public void updateTps(String instanceId, double tps1m) {
        instances.computeIfPresent(instanceId, (ignored, existing) -> existing.withTps(tps1m));
    }

    public void updateWarm(String instanceId, boolean warm) {
        instances.computeIfPresent(instanceId, (ignored, existing) -> existing.withWarm(warm));
    }

    public void updateMetrics(InstanceMetrics metrics) {
        instanceMetrics.put(metrics.instanceId(), metrics);
    }

    public Optional<InstanceMetrics> getMetrics(String instanceId) {
        return Optional.ofNullable(instanceMetrics.get(instanceId));
    }

    public void updateProxyMetrics(ProxyMetrics metrics) {
        proxyMetrics.put(metrics.instanceId(), metrics);
    }

    public Optional<ProxyMetrics> getProxyMetrics(String instanceId) {
        return Optional.ofNullable(proxyMetrics.get(instanceId));
    }

    public int count() {
        return instances.size();
    }
}
