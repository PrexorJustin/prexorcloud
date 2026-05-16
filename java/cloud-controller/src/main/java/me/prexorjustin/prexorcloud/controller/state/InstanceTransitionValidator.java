package me.prexorjustin.prexorcloud.controller.state;

import java.util.Map;
import java.util.Set;

import me.prexorjustin.prexorcloud.protocol.InstanceState;

/**
 * Centralizes the controller's accepted instance lifecycle transitions.
 */
public final class InstanceTransitionValidator {

    private static final Map<InstanceState, Set<InstanceState>> ALLOWED_TRANSITIONS = Map.of(
            InstanceState.SCHEDULED,
            Set.of(
                    InstanceState.PREPARING,
                    InstanceState.STARTING,
                    InstanceState.RUNNING,
                    InstanceState.STOPPING,
                    InstanceState.STOPPED,
                    InstanceState.CRASHED),
            InstanceState.PREPARING,
            Set.of(
                    InstanceState.SCHEDULED,
                    InstanceState.STARTING,
                    InstanceState.RUNNING,
                    InstanceState.STOPPING,
                    InstanceState.STOPPED,
                    InstanceState.CRASHED),
            InstanceState.STARTING,
            Set.of(
                    InstanceState.SCHEDULED,
                    InstanceState.RUNNING,
                    InstanceState.STOPPING,
                    InstanceState.STOPPED,
                    InstanceState.CRASHED),
            InstanceState.RUNNING,
            Set.of(InstanceState.DRAINING, InstanceState.STOPPING, InstanceState.STOPPED, InstanceState.CRASHED),
            InstanceState.DRAINING,
            Set.of(InstanceState.RUNNING, InstanceState.STOPPING, InstanceState.STOPPED, InstanceState.CRASHED),
            InstanceState.STOPPING,
            Set.of(InstanceState.STOPPED, InstanceState.CRASHED),
            InstanceState.STOPPED,
            Set.of(),
            InstanceState.CRASHED,
            Set.of(),
            InstanceState.INSTANCE_STATE_UNSPECIFIED,
            Set.of(InstanceState.SCHEDULED, InstanceState.PREPARING, InstanceState.STARTING));

    private InstanceTransitionValidator() {}

    public static boolean isValid(InstanceState current, InstanceState next) {
        if (current == next) return true;
        return ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(next);
    }

    public static boolean isTerminal(InstanceState state) {
        return state == InstanceState.STOPPED || state == InstanceState.CRASHED;
    }
}
