package me.prexorjustin.prexorcloud.controller.network;

import java.io.IOException;
import java.util.List;

import me.prexorjustin.prexorcloud.api.domain.NetworkComposition;

/** Persistence interface for {@link NetworkComposition} entries. */
public interface NetworkStore {

    List<NetworkComposition> loadAll() throws IOException;

    void save(NetworkComposition network) throws IOException;

    void delete(String name) throws IOException;
}
