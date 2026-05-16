package me.prexorjustin.prexorcloud.controller.deployment;

/**
 * A tracked deployment for a group (versioned rollout).
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
        Integer rollbackOf) {}
