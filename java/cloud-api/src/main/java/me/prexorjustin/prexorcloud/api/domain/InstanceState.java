package me.prexorjustin.prexorcloud.api.domain;

/**
 * Lifecycle states of a server instance.
 */
public enum InstanceState {
    SCHEDULED,
    PREPARING,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    CRASHED,
    DRAINING;

    /** Returns {@code true} if the instance is accepting or serving players. */
    public boolean isActive() {
        return this == RUNNING || this == DRAINING;
    }

    /** Returns {@code true} if the instance has reached a final state. */
    public boolean isTerminal() {
        return this == STOPPED || this == CRASHED;
    }

    /** Returns {@code true} if the instance is in a transitional state. */
    public boolean isTransitional() {
        return !isActive() && !isTerminal();
    }
}
