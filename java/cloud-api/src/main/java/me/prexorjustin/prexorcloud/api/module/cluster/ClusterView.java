package me.prexorjustin.prexorcloud.api.module.cluster;

import java.util.Collection;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.domain.GroupView;
import me.prexorjustin.prexorcloud.api.domain.InstanceView;
import me.prexorjustin.prexorcloud.api.domain.NodeView;
import me.prexorjustin.prexorcloud.api.domain.PlayerView;

/**
 * Read-only snapshot of the entire cluster state, updated in real time by the
 * controller cache.
 */
public interface ClusterView {

    Collection<GroupView> groups();

    Optional<GroupView> group(String name);

    Collection<InstanceView> instances();

    Collection<InstanceView> instancesOfGroup(String group);

    Optional<InstanceView> instance(String instanceId);

    Collection<NodeView> nodes();

    Optional<NodeView> node(String nodeId);

    Collection<PlayerView> onlinePlayers();

    int totalOnlineCount();

    // ── Legacy convenience methods used by modules ──────────────────────

    default int nodeCount() {
        return nodes().size();
    }

    default int instanceCount() {
        return instances().size();
    }

    default int playerCount() {
        return totalOnlineCount();
    }

    default java.util.Optional<PlayerView> getPlayer(java.util.UUID uuid) {
        return onlinePlayers().stream().filter(p -> p.uuid().equals(uuid)).findFirst();
    }
}
