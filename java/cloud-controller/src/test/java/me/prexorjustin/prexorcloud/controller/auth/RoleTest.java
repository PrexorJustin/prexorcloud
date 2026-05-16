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

    @Test
    @DisplayName("ADMIN role grants every Permission constant")
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

        Set<String> adminPermissions = Role.permissionsFor(Role.ADMIN);
        assertEquals(
                declared,
                adminPermissions,
                "ADMIN must include every Permission constant — drift means a permission was added to Permission "
                        + "but not granted to ADMIN.");
    }

    @Test
    @DisplayName("ADMIN can delete instances (regression: INSTANCES_DELETE drift)")
    void adminCanDeleteInstances() {
        assertTrue(Role.hasPermission(Role.ADMIN, Permission.INSTANCES_DELETE));
    }
}
