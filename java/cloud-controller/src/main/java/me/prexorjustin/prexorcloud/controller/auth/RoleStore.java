package me.prexorjustin.prexorcloud.controller.auth;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Persistence interface for role definitions.
 */
public interface RoleStore {

    List<RoleConfig> loadAll() throws IOException;

    Optional<RoleConfig> get(String name) throws IOException;

    void save(RoleConfig role) throws IOException;

    void delete(String name) throws IOException;
}
