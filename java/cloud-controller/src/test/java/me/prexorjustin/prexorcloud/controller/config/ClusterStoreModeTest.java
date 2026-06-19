package me.prexorjustin.prexorcloud.controller.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the Phase-4 {@code clusterStore} flag: lenient YAML parsing (operators write
 * {@code dual}/{@code DUAL}), the default-off behaviour, and the two predicates bootstrap gates on.
 */
final class ClusterStoreModeTest {

    @Test
    void parsesLenientlyAndDefaultsToRaft() {
        assertEquals(ClusterStoreMode.RAFT, ClusterStoreMode.fromString(null));
        assertEquals(ClusterStoreMode.RAFT, ClusterStoreMode.fromString(""));
        assertEquals(ClusterStoreMode.RAFT, ClusterStoreMode.fromString("   "));
        assertEquals(ClusterStoreMode.DUAL, ClusterStoreMode.fromString("dual"));
        assertEquals(ClusterStoreMode.DUAL, ClusterStoreMode.fromString("  Dual "));
        assertEquals(ClusterStoreMode.MONGO, ClusterStoreMode.fromString("MONGO"));
        assertEquals(ClusterStoreMode.RAFT, ClusterStoreMode.fromString("raft"));
    }

    @Test
    void mirrorsToMongoIsOffOnlyForRaft() {
        assertFalse(ClusterStoreMode.RAFT.mirrorsToMongo());
        assertTrue(ClusterStoreMode.DUAL.mirrorsToMongo());
        assertTrue(ClusterStoreMode.MONGO.mirrorsToMongo());
    }

    @Test
    void readsFromMongoOnlyForMongo() {
        assertFalse(ClusterStoreMode.RAFT.readsFromMongo());
        assertFalse(ClusterStoreMode.DUAL.readsFromMongo());
        assertTrue(ClusterStoreMode.MONGO.readsFromMongo());
    }

    @Test
    void controllerConfigDefaultsToRaftWhenOmitted() {
        // Legacy 18-arg convenience constructor (no clusterStore arg) → compact ctor defaults it.
        ControllerConfig cfg = new ControllerConfig(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
        assertEquals(ClusterStoreMode.RAFT, cfg.clusterStore());
    }
}
