package me.prexorjustin.prexorcloud.controller.scheduler;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.state.NodeState;

/**
 * Strategy for selecting a node to host a new instance.
 */
public interface NodeSelector {

    /**
     * Select the best node for the given instance request.
     *
     * @param request
     *            the instance placement request
     * @param available
     *            list of currently available nodes
     * @return the selected node, or empty if no node is eligible
     */
    Optional<NodeState> select(InstanceRequest request, List<NodeState> available);

    /**
     * Explain, for diagnostics, why each non-selectable node is ineligible — turns an opaque
     * "no eligible node" placement failure into an actionable per-node reason. Maps {@code nodeId} to a
     * short human-readable reason; eligible nodes are omitted. Defaults to empty so this stays a
     * functional interface and stub selectors need not implement it.
     */
    default Map<String, String> explainIneligibility(InstanceRequest request, List<NodeState> available) {
        return Map.of();
    }
}
