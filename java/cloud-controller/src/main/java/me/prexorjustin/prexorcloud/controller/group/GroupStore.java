package me.prexorjustin.prexorcloud.controller.group;

import java.io.IOException;
import java.util.List;

/**
 * Persistence interface for group configurations.
 */
public interface GroupStore {

    List<GroupConfig> loadAll() throws IOException;

    void save(GroupConfig config) throws IOException;

    void delete(String name) throws IOException;
}
