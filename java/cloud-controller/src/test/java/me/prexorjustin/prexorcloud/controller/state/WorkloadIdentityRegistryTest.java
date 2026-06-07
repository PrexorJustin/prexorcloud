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
    void validateRequiresRunningInstance() {
        var registry = new WorkloadIdentityRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
        String token = registry.issuePluginToken("instance-1");

        var startingInstance = runningInstance("instance-1", InstanceState.STARTING);
        assertTrue(registry.validatePluginToken(token, id -> Optional.of(startingInstance))
                .isEmpty());

        var running = runningInstance("instance-1", InstanceState.RUNNING);
        assertEquals(Optional.of("instance-1"), registry.validatePluginToken(token, id -> Optional.of(running)));
    }

    @Test
    void expiredTokensAreRejected() {
        var clock = new MutableClock(NOW);
        var registry = new WorkloadIdentityRegistry(clock, Duration.ofMinutes(15));
        String token = registry.issuePluginToken("instance-1");

        clock.advance(Duration.ofMinutes(16));
        assertTrue(registry.validatePluginToken(
                        token, id -> Optional.of(runningInstance("instance-1", InstanceState.RUNNING)))
                .isEmpty());

        // expired entries are evicted on the read path
        assertTrue(registry.getPluginToken(token).isEmpty());
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
    void refreshRejectsExpiredToken() {
        var clock = new MutableClock(NOW);
        var registry = new WorkloadIdentityRegistry(clock, Duration.ofMinutes(15));
        String token = registry.issuePluginToken("instance-1");

        clock.advance(Duration.ofMinutes(16));
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
