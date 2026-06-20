package me.prexorjustin.prexorcloud.daemon.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the best-effort on-disk cache of cluster-advertised controllers: round-tripping, tolerance of
 * a missing/corrupt file, and dropping loopback/unparseable entries on load.
 */
final class LearnedControllersStoreTest {

    @Test
    void roundTripsEndpoints(@TempDir Path dir) {
        var store = new LearnedControllersStore(dir.resolve("known-controllers.json"));
        var endpoints = List.of(new ControllerEndpoint("ctrl-1", 9090), new ControllerEndpoint("ctrl-2", 9091));
        store.save(endpoints);
        assertEquals(endpoints, store.load());
    }

    @Test
    void missingFileLoadsEmpty(@TempDir Path dir) {
        assertTrue(
                new LearnedControllersStore(dir.resolve("absent.json")).load().isEmpty());
    }

    @Test
    void corruptFileLoadsEmpty(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("known-controllers.json");
        Files.writeString(p, "{ not valid json");
        assertTrue(new LearnedControllersStore(p).load().isEmpty());
    }

    @Test
    void dropsLoopbackAndUnparseableOnLoad(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("known-controllers.json");
        Files.writeString(p, "[\"ctrl-1:9090\", \"127.0.0.1:9090\", \"bad:bad\"]");
        assertEquals(List.of(new ControllerEndpoint("ctrl-1", 9090)), new LearnedControllersStore(p).load());
    }

    @Test
    void overwritesOnRepeatedSave(@TempDir Path dir) {
        var store = new LearnedControllersStore(dir.resolve("known-controllers.json"));
        store.save(List.of(new ControllerEndpoint("ctrl-1", 9090)));
        store.save(List.of(new ControllerEndpoint("ctrl-2", 9090)));
        assertEquals(List.of(new ControllerEndpoint("ctrl-2", 9090)), store.load());
    }
}
