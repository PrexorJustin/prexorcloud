package me.prexorjustin.prexorcloud.controller.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Role")
class RoleTest {

    @AfterEach
    void resetRoleState() {
        Role.initialize(null);
        Role.clearCache();
    }

    /**
     * Permissions intentionally withheld from the built-in ADMIN role. Must stay in
     * sync with {@code Role.EXCLUDED_FROM_DEFAULT_ADMIN}. Adding a permission here
     * is a deliberate security decision — see the field's comment in Role.java.
     */
    private static final Set<String> EXCLUDED_FROM_DEFAULT_ADMIN = Set.of(Permission.CLUSTER_MANAGE);

    @Test
    @DisplayName("ADMIN role grants every Permission constant except the deliberately excluded set")
    void adminCoversEveryPermissionConstant() throws IllegalAccessException {
        Set<String> declared = new HashSet<>();
        for (Field field : Permission.class.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isPublic(mods)
                    && Modifier.isStatic(mods)
                    && Modifier.isFinal(mods)
                    && field.getType() == String.class) {
                declared.add((String) field.get(null));
            }
        }
        Set<String> expected = new HashSet<>(declared);
        expected.removeAll(EXCLUDED_FROM_DEFAULT_ADMIN);

        Set<String> adminPermissions = Role.permissionsFor(Role.ADMIN);
        assertEquals(
                expected,
                adminPermissions,
                "ADMIN must include every Permission constant except EXCLUDED_FROM_DEFAULT_ADMIN — drift means "
                        + "a permission was added to Permission but not granted to ADMIN.");
    }

    @Test
    @DisplayName("ADMIN can delete instances (regression: INSTANCES_DELETE drift)")
    void adminCanDeleteInstances() {
        assertTrue(Role.hasPermission(Role.ADMIN, Permission.INSTANCES_DELETE));
    }

    @Test
    @DisplayName("ADMIN does NOT receive CLUSTER_MANAGE by default")
    void adminDoesNotGetClusterManageByDefault() {
        assertTrue(
                !Role.hasPermission(Role.ADMIN, Permission.CLUSTER_MANAGE),
                "CLUSTER_MANAGE must be explicitly granted via a custom role — it allows issuing join tokens,"
                        + " force-ejecting members, and revealing masked cluster_config fields.");
    }

    @Test
    @DisplayName("ADMIN DOES receive CLUSTER_VIEW and CLUSTER_CONFIG_WRITE by default (core operator work)")
    void adminGetsClusterViewAndWriteByDefault() {
        assertTrue(
                Role.hasPermission(Role.ADMIN, Permission.CLUSTER_VIEW),
                "CLUSTER_VIEW is core ADMIN — reading cluster status, members, config history is not sensitive.");
        assertTrue(
                Role.hasPermission(Role.ADMIN, Permission.CLUSTER_CONFIG_WRITE),
                "CLUSTER_CONFIG_WRITE is core ADMIN — patching CORS / rate limits / lockout policy is normal.");
    }
}
