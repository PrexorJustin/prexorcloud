package me.prexorjustin.prexorcloud.controller.deployment;

/**
 * A tracked deployment for a group (versioned rollout).
 *
 * <p>{@code groupSnapshot} is the full {@link me.prexorjustin.prexorcloud.controller.group.GroupConfig}
 * (serialized JSON) as it stood when this deployment rolled out — the source of truth a rollback restores
 * the group to. Empty on legacy records created before snapshots existed.
 */
public record DeploymentRecord(
        int id,
        String groupName,
        int revision,
        String trigger,
        String strategy,
        String state,
        String templateSnapshot,
        String configSnapshot,
        int totalInstances,
        int updatedInstances,
        String createdAt,
        String completedAt,
        Integer rollbackOf,
        String groupSnapshot) {

    /** Back-compat constructor for call sites that don't capture a group snapshot (defaults to empty). */
    public DeploymentRecord(
            int id,
            String groupName,
            int revision,
            String trigger,
            String strategy,
            String state,
            String templateSnapshot,
            String configSnapshot,
            int totalInstances,
            int updatedInstances,
            String createdAt,
            String completedAt,
            Integer rollbackOf) {
        this(
                id,
                groupName,
                revision,
                trigger,
                strategy,
                state,
                templateSnapshot,
                configSnapshot,
                totalInstances,
                updatedInstances,
                createdAt,
                completedAt,
                rollbackOf,
                "");
    }
}
