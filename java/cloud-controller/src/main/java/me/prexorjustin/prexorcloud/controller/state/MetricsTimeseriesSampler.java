package me.prexorjustin.prexorcloud.controller.state;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic snapshot of {@link ClusterState} into {@link MetricsTimeseries}. Scheduled
 * by the controller bootstrap; each tick samples overview counts plus per-instance and
 * per-node metrics, and prunes buffers for resources that have disappeared since the
 * previous tick.
 */
public final class MetricsTimeseriesSampler implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(MetricsTimeseriesSampler.class);

    private final ClusterState clusterState;
    private final MetricsTimeseries timeseries;
    private final AtomicLong ticks = new AtomicLong();

    public MetricsTimeseriesSampler(ClusterState clusterState, MetricsTimeseries timeseries) {
        this.clusterState = clusterState;
        this.timeseries = timeseries;
    }

    @Override
    public void run() {
        try {
            sample();
        } catch (RuntimeException e) {
            logger.warn("MetricsTimeseries sample tick failed", e);
        }
    }

    void sample() {
        long now = System.currentTimeMillis();

        int onlineNodes = 0;
        Set<String> liveNodeIds = new HashSet<>();
        for (NodeState node : clusterState.getAllNodes()) {
            liveNodeIds.add(node.nodeId());
            if (node.status() == NodeState.NodeStatus.ONLINE) onlineNodes++;
            timeseries.recordNode(now, node);
        }

        Set<String> liveInstanceIds = new HashSet<>();
        for (InstanceInfo instance : clusterState.getAllInstances()) {
            liveInstanceIds.add(instance.id());
            clusterState
                    .getInstanceMetrics(instance.id())
                    .ifPresent(m -> timeseries.recordInstance(now, instance.id(), m));
        }

        timeseries.recordOverview(now, clusterState.playerCount(), clusterState.instanceCount(), onlineNodes);

        timeseries.retainInstances(liveInstanceIds);
        timeseries.retainNodes(liveNodeIds);
        ticks.incrementAndGet();
    }

    public long ticks() {
        return ticks.get();
    }
}
