package me.prexorjustin.prexorcloud.controller.auth;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Role definitions mapping role names to permission sets. Supports both
 * built-in roles and custom roles persisted in YAML.
 * <p>
 * Call {@link #initialize(RoleStore)} during startup to enable dynamic role
 * resolution. Before initialization (or if the config loader is unavailable),
 * the built-in defaults are used.
 */
public final class Role {

    private static final Logger logger = LoggerFactory.getLogger(Role.class);

    private Role() {}

    public static final String ADMIN = "ADMIN";
    public static final String OPERATOR = "OPERATOR";
    public static final String VIEWER = "VIEWER";
    /**
     * Machine principal issued to a daemon's host CLI when a join token is redeemed.
     * Lets the CLI on that VPS read cluster state and manage instances on its own
     * node, without admin-level write access. Why: the operator should be auto-logged-in
     * on the host they just installed, but a leaked host shouldn't grant cluster-wide
     * write access.
     */
    public static final String DAEMON_HOST = "DAEMON_HOST";

    private static volatile RoleStore configLoader;
    private static final Map<String, Set<String>> permissionCache = new ConcurrentHashMap<>();

    // Derived reflectively from Permission.class so new constants are picked up
    // automatically. Why: a hand-mirrored copy silently drifted before — INSTANCES_DELETE
    // existed in Permission but was missing here, denying ADMIN that permission.
    private static final Set<String> ALL_PERMISSIONS = collectAllPermissions();

    private static Set<String> collectAllPermissions() {
        Set<String> permissions = new HashSet<>();
        for (Field field : Permission.class.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isPublic(mods)
                    && Modifier.isStatic(mods)
                    && Modifier.isFinal(mods)
                    && field.getType() == String.class) {
                try {
                    permissions.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot read Permission." + field.getName(), e);
                }
            }
        }
        return Set.copyOf(permissions);
    }

    private static final Set<String> OPERATOR_PERMISSIONS = Set.of(
            Permission.NODES_VIEW,
            Permission.NODES_DRAIN,
            Permission.GROUPS_VIEW,
            Permission.GROUPS_CREATE,
            Permission.GROUPS_UPDATE,
            Permission.GROUPS_START,
            Permission.NETWORKS_VIEW,
            Permission.NETWORKS_CREATE,
            Permission.NETWORKS_UPDATE,
            Permission.INSTANCES_VIEW,
            Permission.INSTANCES_STOP,
            Permission.INSTANCES_COMMAND,
            Permission.INSTANCES_CONSOLE,
            Permission.PLAYERS_VIEW,
            Permission.PLAYERS_TRANSFER,
            Permission.TEMPLATES_VIEW,
            Permission.TEMPLATES_CREATE,
            Permission.TEMPLATES_UPDATE,
            Permission.CRASHES_VIEW,
            Permission.TOKENS_VIEW,
            Permission.MODULES_VIEW,
            Permission.CATALOG_VIEW,
            Permission.METRICS_VIEW,
            Permission.EVENTS_STREAM,
            Permission.EVENTS_VIEW,
            Permission.SHARE_INVOKE,
            Permission.SHARE_REVOKE);

    private static final Set<String> VIEWER_PERMISSIONS = Set.of(
            Permission.NODES_VIEW,
            Permission.GROUPS_VIEW,
            Permission.NETWORKS_VIEW,
            Permission.INSTANCES_VIEW,
            Permission.INSTANCES_CONSOLE,
            Permission.PLAYERS_VIEW,
            Permission.TEMPLATES_VIEW,
            Permission.CRASHES_VIEW,
            Permission.MODULES_VIEW,
            Permission.CATALOG_VIEW,
            Permission.METRICS_VIEW,
            Permission.EVENTS_STREAM,
            Permission.EVENTS_VIEW);

    private static final Set<String> DAEMON_HOST_PERMISSIONS = Set.of(
            Permission.NODES_VIEW,
            Permission.GROUPS_VIEW,
            Permission.NETWORKS_VIEW,
            Permission.INSTANCES_VIEW,
            Permission.INSTANCES_STOP,
            Permission.INSTANCES_COMMAND,
            Permission.INSTANCES_CONSOLE,
            Permission.PLAYERS_VIEW,
            Permission.TEMPLATES_VIEW,
            Permission.CRASHES_VIEW,
            Permission.MODULES_VIEW,
            Permission.METRICS_VIEW,
            Permission.EVENTS_STREAM,
            Permission.EVENTS_VIEW);

    /**
     * Initialize with a config loader for dynamic role resolution.
     */
    public static void initialize(RoleStore loader) {
        configLoader = loader;
        permissionCache.clear();
    }

    /**
     * Clear the permission cache. Call after role config changes.
     */
    public static void clearCache() {
        permissionCache.clear();
    }

    /**
     * Get the permissions for a role name. Checks the YAML config first for dynamic
     * roles, falls back to built-in defaults. Results are cached to avoid repeated
     * IO.
     */
    public static Set<String> permissionsFor(String role) {
        return permissionCache.computeIfAbsent(role, Role::resolvePermissions);
    }

    private static Set<String> resolvePermissions(String role) {
        // Try dynamic lookup first
        if (configLoader != null) {
            try {
                var roleConfig = configLoader.get(role);
                if (roleConfig.isPresent()) {
                    return Set.copyOf(roleConfig.get().permissions());
                }
            } catch (IOException e) {
                logger.warn("Failed to load role '{}' from config, falling back to defaults: {}", role, e.getMessage());
            }
        }

        // Fall back to built-in defaults
        return switch (role) {
            case ADMIN -> ALL_PERMISSIONS;
            case OPERATOR -> OPERATOR_PERMISSIONS;
            case VIEWER -> VIEWER_PERMISSIONS;
            case DAEMON_HOST -> DAEMON_HOST_PERMISSIONS;
            default -> Set.of();
        };
    }

    /**
     * Check if a role has a specific permission.
     */
    public static boolean hasPermission(String role, String permission) {
        return permissionsFor(role).contains(permission);
    }

    /**
     * Validate a role name. Returns true for built-in roles and any custom role in
     * the config.
     */
    public static boolean isValid(String role) {
        if (ADMIN.equals(role) || OPERATOR.equals(role) || VIEWER.equals(role) || DAEMON_HOST.equals(role)) {
            return true;
        }
        if (configLoader != null) {
            try {
                return configLoader.get(role).isPresent();
            } catch (IOException e) {
                logger.warn("Failed to check role validity for '{}': {}", role, e.getMessage());
            }
        }
        return false;
    }
}
