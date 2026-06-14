package me.prexorjustin.prexorcloud.controller.auth;

/**
 * All permission constants. Roles map to sets of these permissions.
 */
public final class Permission {

    private Permission() {}

    // Nodes
    public static final String NODES_VIEW = "nodes.view";
    public static final String NODES_DRAIN = "nodes.drain";
    public static final String NODES_REVOKE_CERT = "nodes.revoke-cert";
    // Immediate (non-graceful) daemon shutdown — more destructive than NODES_DRAIN, so ADMIN-only by
    // default (operators can still drain, which stops the node gracefully).
    public static final String NODES_SHUTDOWN = "nodes.shutdown";

    // Groups
    public static final String GROUPS_VIEW = "groups.view";
    public static final String GROUPS_CREATE = "groups.create";
    public static final String GROUPS_UPDATE = "groups.update";
    public static final String GROUPS_DELETE = "groups.delete";
    public static final String GROUPS_START = "groups.start";

    // Instances
    public static final String INSTANCES_VIEW = "instances.view";
    public static final String INSTANCES_STOP = "instances.stop";
    public static final String INSTANCES_COMMAND = "instances.command";
    public static final String INSTANCES_DELETE = "instances.delete";
    public static final String INSTANCES_CONSOLE = "instances.console";

    // Players
    public static final String PLAYERS_VIEW = "players.view";
    public static final String PLAYERS_TRANSFER = "players.transfer";

    // Networks
    public static final String NETWORKS_VIEW = "networks.view";
    public static final String NETWORKS_CREATE = "networks.create";
    public static final String NETWORKS_UPDATE = "networks.update";
    public static final String NETWORKS_DELETE = "networks.delete";

    // Templates
    public static final String TEMPLATES_VIEW = "templates.view";
    public static final String TEMPLATES_CREATE = "templates.create";
    public static final String TEMPLATES_UPDATE = "templates.update";
    public static final String TEMPLATES_DELETE = "templates.delete";

    // Crashes
    public static final String CRASHES_VIEW = "crashes.view";

    // Paste-share workflow. Decoupled from CRASHES_VIEW / SYSTEM_LOGS_VIEW so an
    // org can grant view-only access (which alone shouldn't push artifacts off
    // the cluster). SHARE_INVOKE covers POST .../share endpoints; SHARE_REVOKE
    // covers POST /api/v1/shares/{id}/revoke and GET /api/v1/shares* listing.
    public static final String SHARE_INVOKE = "share.invoke";
    public static final String SHARE_REVOKE = "share.revoke";

    // Tokens
    public static final String TOKENS_VIEW = "tokens.view";
    public static final String TOKENS_CREATE = "tokens.create";
    public static final String TOKENS_REVOKE = "tokens.revoke";

    // Users
    public static final String USERS_VIEW = "users.view";
    public static final String USERS_CREATE = "users.create";
    public static final String USERS_UPDATE = "users.update";
    public static final String USERS_DELETE = "users.delete";

    // Roles
    public static final String ROLES_VIEW = "roles.view";
    public static final String ROLES_MANAGE = "roles.manage";

    // Modules
    public static final String MODULES_VIEW = "modules.view";
    public static final String MODULES_MANAGE = "modules.manage";

    // Catalog
    public static final String CATALOG_VIEW = "catalog.view";
    public static final String CATALOG_MANAGE = "catalog.manage";

    // Audit
    public static final String AUDIT_VIEW = "audit.view";

    // System
    public static final String SYSTEM_SETTINGS = "system.settings";
    public static final String SYSTEM_LOGS_VIEW = "system.logs.view";
    // Remotely shut down the controller process this request lands on. ADMIN-only.
    public static final String SYSTEM_SHUTDOWN = "system.shutdown";

    // Metrics
    public static final String METRICS_VIEW = "metrics.view";

    // Events
    public static final String EVENTS_STREAM = "events.stream";
    public static final String EVENTS_VIEW = "events.view";

    // Backups
    public static final String BACKUPS_VIEW = "backups.view";
    public static final String BACKUPS_MANAGE = "backups.manage";
    public static final String BACKUPS_RESTORE = "backups.restore";

    // Cluster control plane (Phase 6+). Read membership, config history, leases.
    public static final String CLUSTER_VIEW = "cluster.view";
    // Patch and rollback cluster_config versions. Default ADMIN includes this —
    // adjusting CORS / rate limits / lockout policy is core operator work.
    public static final String CLUSTER_CONFIG_WRITE = "cluster.config.write";
    // Issue/revoke join tokens, force-eject members, rotate the seed secret, reveal
    // masked config fields. NOT bundled into default ADMIN: a join-token creator can
    // add controllers to the cluster — that should be a conscious grant.
    public static final String CLUSTER_MANAGE = "cluster.manage";
}
