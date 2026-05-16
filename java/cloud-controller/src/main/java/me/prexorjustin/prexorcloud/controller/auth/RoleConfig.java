package me.prexorjustin.prexorcloud.controller.auth;

import java.util.List;

/**
 * YAML-serializable role definition.
 */
public record RoleConfig(String name, List<String> permissions, boolean builtIn) {

    public RoleConfig {
        if (name == null) name = "";
        if (permissions == null) permissions = List.of();
    }
}
