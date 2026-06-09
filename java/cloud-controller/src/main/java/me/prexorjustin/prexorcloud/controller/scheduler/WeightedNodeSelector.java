package me.prexorjustin.prexorcloud.controller.scheduler;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.state.NodeState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Selects the best node using a weighted scoring system.
 * <p>
 * Scoring weights:
 * <ul>
 * <li>35% -- free memory ratio</li>
 * <li>25% -- CPU availability (1 - cpuUsage)</li>
 * <li>15% -- instance spread (fewer instances = higher score)</li>
 * <li>10% -- port availability</li>
 * <li>15% -- group spread across the configured spreadConstraint label
 *     (when set on the request); reduces to 0 when the candidate's label
 *     bucket is the most-loaded one for this group</li>
 * </ul>
 */
public final class WeightedNodeSelector implements NodeSelector {

    private static final Logger logger = LoggerFactory.getLogger(WeightedNodeSelector.class);

    @Override
    public Optional<NodeState> select(InstanceRequest request, List<NodeState> available) {
        int maxBucket = request.existingByBucket().values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        return available.stream()
                .filter(node -> isEligible(node, request))
                .max(Comparator.comparingDouble(node -> score(node, request, maxBucket)));
    }

    private boolean isEligible(NodeState node, InstanceRequest request) {
        if (node.status() != NodeState.NodeStatus.ONLINE) return false;
        if (!ResourceAccounting.project(node, request).fits()) return false;
        if (PortAllocator.allocate(request.portRangeStart(), request.portRangeEnd(), node.usedPorts())
                .isEmpty()) {
            return false;
        }

        // Node affinity: all required labels must match
        for (String constraint : request.nodeAffinity()) {
            if (!matchesLabel(node, constraint)) return false;
        }

        // Node anti-affinity: none of the excluded labels may match
        for (String constraint : request.nodeAntiAffinity()) {
            if (matchesLabel(node, constraint)) return false;
        }

        return true;
    }

    /**
     * Check if a node's labels match a constraint string. Format: "key=value" for
     * exact match, or just "key" for presence check.
     */
    private boolean matchesLabel(NodeState node, String constraint) {
        int eq = constraint.indexOf('=');
        if (eq > 0) {
            String key = constraint.substring(0, eq);
            String value = constraint.substring(eq + 1);
            return value.equals(node.labels().get(key));
        }
        return node.labels().containsKey(constraint);
    }

    private double score(NodeState node, InstanceRequest request, int maxBucket) {
        double memoryScore = (double) node.freeMemoryMb() / Math.max(node.totalMemoryMb(), 1);
        double cpuScore = 1.0 - node.cpuUsage();
        double instanceScore = 1.0 / (1.0 + node.instanceCount());
        int portRange = request.portRangeEnd() - request.portRangeStart() + 1;
        double portScore = (double) (portRange - node.usedPorts().size()) / portRange;
        double spreadScore = spreadScore(node, request, maxBucket);

        return 0.35 * memoryScore + 0.25 * cpuScore + 0.15 * instanceScore + 0.10 * portScore + 0.15 * spreadScore;
    }

    /**
     * Returns a value in [0, 1] preferring nodes whose {@code spreadConstraint}
     * label bucket holds fewer existing instances of the group. Returns 1.0 when
     * no spread constraint is set, when the bucket is empty, or when buckets are
     * already balanced. Falls back to 1.0 if the candidate has no value for the
     * label key (so unlabeled nodes are not penalised).
     */
    private double spreadScore(NodeState node, InstanceRequest request, int maxBucket) {
        String key = spreadKey(request.spreadConstraint());
        if (key.isEmpty() || maxBucket == 0) {
            return 1.0;
        }
        String bucket = node.labels().get(key);
        if (bucket == null) {
            return 1.0;
        }
        int bucketCount = request.existingByBucket().getOrDefault(bucket, 0);
        return 1.0 - ((double) bucketCount / maxBucket);
    }

    private static String spreadKey(String spreadConstraint) {
        if (spreadConstraint == null || spreadConstraint.isBlank()) {
            return "";
        }
        int eq = spreadConstraint.indexOf('=');
        return eq < 0 ? spreadConstraint : spreadConstraint.substring(0, eq);
    }
}
