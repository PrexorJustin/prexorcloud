package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.Test;

class WorkloadIdentityRegistryTest {

    private static final Instant NOW = Instant.parse("2026-04-14T12:00:00Z");

    @Test
    void validateAcceptsLiveInstanceStates() {
        var registry = new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
        String token = registry.issuePluginToken("instance-1");

        // STARTING must be accepted: the plugin authenticates during boot to run
        // the readiness handshake (POST /api/plugin/ready) that itself promotes
        // the instance to RUNNING. Gating on RUNNING alone deadlocks startup.
        for (var live : new InstanceState[] {InstanceState.STARTING, InstanceState.RUNNING, InstanceState.DRAINING}) {
            assertEquals(
                    Optional.of("instance-1"),
                    registry.validatePluginToken(token, id -> Optional.of(runningInstance("instance-1", live))),
                    "token should be usable while instance is " + live);
        }

        // Pre-launch and terminal states have no live plugin -- reject.
        for (var dead : new InstanceState[] {
            InstanceState.SCHEDULED,
            InstanceState.PREPARING,
            InstanceState.STOPPING,
            InstanceState.STOPPED,
            InstanceState.CRASHED
        }) {
            assertTrue(
                    registry.validatePluginToken(token, id -> Optional.of(runningInstance("instance-1", dead)))
                            .isEmpty(),
                    "token should be rejected while instance is " + dead);
        }
    }

    @Test
    void expiredTokensAreRejectedForNormalCallsAndEvictedPastGrace() {
        var clock = new MutableClock(NOW);
        var registry = new WorkloadIdentityRegistry(clock, Duration.ofMinutes(15));
        String token = registry.issuePluginToken("instance-1");

        // Expired but within the refresh grace (grace == TTL == 15m): normal calls
        // are rejected, but the entry is RETAINED so the reactive refresh can use it.
        clock.advance(Duration.ofMinutes(16));
        assertTrue(registry.validatePluginToken(
                        token, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING)))
                .isEmpty());
        assertTrue(registry.getPluginToken(token).isPresent(), "within-grace token must be retained for refresh");

        // Past the grace window (expiresAt 15m + grace 15m = 30m): evicted on read.
        clock.advance(Duration.ofMinutes(15));
        assertTrue(registry.validatePluginToken(
                        token, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING)))
                .isEmpty());
        assertTrue(registry.getPluginToken(token).isEmpty(), "past-grace token must be evicted");
    }

    @Test
    void justExpiredTokenCanStillRefreshWithinGrace() {
        var clock = new MutableClock(NOW);
        var registry = new WorkloadIdentityRegistry(clock, Duration.ofMinutes(15));
        String token = registry.issuePluginToken("instance-1");

        // Token has lapsed (reactive-on-401 case) but is within the grace window.
        clock.advance(Duration.ofMinutes(16));
        var refreshed = registry.refreshPluginToken(
                token, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING)));
        assertTrue(refreshed.isPresent(), "a just-expired token must bootstrap a fresh one within grace");

        // The fresh token authenticates normal calls again.
        assertEquals(
                Optional.of("instance-1"),
                registry.validatePluginToken(
                        refreshed.get().token(),
                        id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING))));

        // A token expired beyond the grace window cannot refresh.
        String stale = registry.issuePluginToken("instance-2");
        clock.advance(Duration.ofMinutes(31));
        assertTrue(
                registry.refreshPluginToken(
                                stale, id -> Optional.of(runningInstance("instance-2", InstanceState.RUNNING)))
                        .isEmpty(),
                "a token past the grace window must not refresh");
    }

    @Test
    void refreshProducesNewTokenAndInvalidatesOld() {
        var registry = new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
        String original = registry.issuePluginToken("instance-1");

        var refreshed = registry.refreshPluginToken(
                original, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING)));

        assertTrue(refreshed.isPresent());
        String newToken = refreshed.get().token();
        assertNotEquals(original, newToken);

        // old token no longer validates
        assertTrue(registry.validatePluginToken(
                        original, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING)))
                .isEmpty());

        // new token does
        assertEquals(
                Optional.of("instance-1"),
                registry.validatePluginToken(
                        newToken, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING))));
    }

    @Test
    void refreshRejectsTokenExpiredBeyondGrace() {
        var clock = new MutableClock(NOW);
        var registry = new WorkloadIdentityRegistry(clock, Duration.ofMinutes(15));
        String token = registry.issuePluginToken("instance-1");

        // Within grace (expiresAt 15m + grace 15m), a lapsed token can still refresh
        // (see justExpiredTokenCanStillRefreshWithinGrace). Only past the grace window
        // is refresh rejected.
        clock.advance(Duration.ofMinutes(31));
        assertTrue(registry.refreshPluginToken(
                        token, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING)))
                .isEmpty());
    }

    @Test
    void refreshRejectsUnknownToken() {
        var registry = new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
        assertTrue(registry.refreshPluginToken(
                        "ptk_does_not_exist", id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING)))
                .isEmpty());
    }

    @Test
    void refreshRejectsStoppedInstance() {
        var registry = new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
        String token = registry.issuePluginToken("instance-1");

        assertTrue(registry.refreshPluginToken(
                        token, id -> Optional.of(runningInstance("instance-1", InstanceState.STOPPING)))
                .isEmpty());
    }

    @Test
    void revokeTokenRemovesEntry() {
        var registry = new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
        String token = registry.issuePluginToken("instance-1");

        registry.revokeToken(token);

        assertTrue(registry.getPluginToken(token).isEmpty());
        assertTrue(registry.validatePluginToken(
                        token, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING)))
                .isEmpty());
    }

    @Test
    void revokeTokenIdRemovesMatchingEntry() {
        var registry = new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
        String token = registry.issuePluginToken("instance-1");
        String tokenId = registry.getPluginToken(token).orElseThrow().tokenId();

        assertTrue(registry.revokeTokenId(tokenId));
        assertTrue(registry.getPluginToken(token).isEmpty());
        assertFalse(registry.revokeTokenId(tokenId));
    }

    @Test
    void unregisterRemovesAllTokensForInstance() {
        var registry = new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
        String a1 = registry.issuePluginToken("instance-1");
        String a2 = registry.issuePluginToken("instance-1");
        String b = registry.issuePluginToken("instance-2");

        registry.unregisterPluginTokens("instance-1");

        assertTrue(registry.getPluginToken(a1).isEmpty());
        assertTrue(registry.getPluginToken(a2).isEmpty());
        assertFalse(registry.getPluginToken(b).isEmpty());
    }

    @Test
    void issuedTokensHaveExpectedShape() {
        var registry = new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
        String token = registry.issuePluginToken("instance-1");

        assertTrue(token.startsWith("ptk_"));
        var entry = registry.getPluginToken(token).orElseThrow();
        assertEquals("instance-1", entry.instanceId());
        assertEquals(NOW, entry.issuedAt());
        assertEquals(NOW.plus(Duration.ofMinutes(15)), entry.expiresAt());
    }

    @Test
    void hydrateReplacesExistingState() {
        var registry = new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
        registry.issuePluginToken("instance-1");

        var entry = new WorkloadIdentityRegistry.PluginTokenEntry(
                "tid-imported", "instance-imported", NOW, NOW.plus(Duration.ofMinutes(15)));
        registry.hydrate(java.util.Map.of("ptk_imported", entry));

        assertEquals(1, registry.pluginTokens().size());
        assertEquals(
                Optional.of("instance-imported"),
                registry.validatePluginToken(
                        "ptk_imported",
                        id -> Optional.of(runningInstance("instance-imported", InstanceState.RUNNING))));
    }

    @Test
    void validationRejectsCrossInstanceLookupMismatch() {
        var registry = new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
        String token = registry.issuePluginToken("instance-1");

        assertTrue(registry.validatePluginToken(
                        token, id -> Optional.of(runningInstance("instance-2", InstanceState.RUNNING)))
                .isEmpty());
        assertTrue(registry.refreshPluginToken(
                        token, id -> Optional.of(runningInstance("instance-2", InstanceState.RUNNING)))
                .isEmpty());
    }

    @Test
    void replayedSequenceIsRejectedPerInstance() {
        var registry = new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
        String token = registry.issuePluginToken("instance-1");

        assertEquals(
                Optional.of("instance-1"),
                registry.validatePluginToken(
                        token, 1L, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING))));
        assertTrue(registry.validatePluginToken(
                        token, 1L, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING)))
                .isEmpty());
        assertEquals(
                Optional.of("instance-1"),
                registry.validatePluginToken(
                        token, 2L, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING))));
    }

    @Test
    void refreshRejectsReplayedSequence() {
        var registry = new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
        String token = registry.issuePluginToken("instance-1");

        assertTrue(registry.refreshPluginToken(
                        token, 1L, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING)))
                .isPresent());
        assertTrue(registry.refreshPluginToken(
                        token, 1L, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING)))
                .isEmpty());
    }

    @Test
    void externalSequenceWindowRejectsReplayAfterRegistryRestart() {
        var sequenceStore = new FakeSequenceWindowStore();
        var registry =
                new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15), sequenceStore);
        String token = registry.issuePluginToken("instance-1");

        assertEquals(
                Optional.of("instance-1"),
                registry.validatePluginToken(
                        token, 7L, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING))));

        var restarted =
                new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15), sequenceStore);
        restarted.hydrate(registry.pluginTokens());

        assertTrue(restarted
                .validatePluginToken(token, 7L, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING)))
                .isEmpty());
        assertEquals(
                Optional.of("instance-1"),
                restarted.validatePluginToken(
                        token, 8L, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING))));
    }

    @Test
    void unregisterClearsSequenceWindowSoRestartedInstanceStartsFresh() {
        var sequenceStore = new FakeSequenceWindowStore();
        var registry =
                new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15), sequenceStore);

        String token = registry.issuePluginToken("instance-1");
        // Drive the replay window up while the instance runs.
        assertEquals(
                Optional.of("instance-1"),
                registry.validatePluginToken(
                        token, 5L, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING))));

        // Instance terminates -> tokens + replay window must be cleared.
        registry.unregisterPluginTokens("instance-1");

        // A restarted instance reuses the id and its plugin restarts its sequence
        // at 1. Without clearing the window this would be rejected as a replay.
        String restartToken = registry.issuePluginToken("instance-1");
        assertEquals(
                Optional.of("instance-1"),
                registry.validatePluginToken(
                        restartToken, 1L, id -> Optional.of(runningInstance("instance-1", InstanceState.STARTING))));
    }

    private static InstanceInfo runningInstance(String id, InstanceState state) {
        return new InstanceInfo(id, "proxy", "node-1", state, 25565, 0, 0, NOW);
    }

    private static final class FakeSequenceWindowStore implements WorkloadIdentityRegistry.SequenceWindowStore {
        private final Map<String, Long> sequences = new HashMap<>();

        @Override
        public boolean acceptSequence(String instanceId, long sequence, Duration ttl) {
            Long current = sequences.get(instanceId);
            if (current != null && sequence <= current) {
                return false;
            }
            sequences.put(instanceId, sequence);
            return true;
        }

        @Override
        public void clearSequence(String instanceId) {
            sequences.remove(instanceId);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
