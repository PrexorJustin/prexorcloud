package me.prexorjustin.prexorcloud.controller.scheduler;

import java.util.List;
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
}
