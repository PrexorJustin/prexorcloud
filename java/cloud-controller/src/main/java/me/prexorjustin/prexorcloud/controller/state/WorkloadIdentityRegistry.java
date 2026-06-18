package me.prexorjustin.prexorcloud.controller.state;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of short-lived workload credentials. Tokens are opaque sliding-
 * window bearers scoped to a single instance; clients rotate into a fresh
 * token via the refresh endpoint before expiry. Revocation removes the entry
 * immediately and instance terminal state triggers bulk revocation.
 *
 * <p><strong>Sequence-window dev fallback:</strong> when constructed without
 * a {@link SequenceWindowStore}, replay protection runs against a local
 * {@code ConcurrentHashMap} only. This is fine for single-node development
 * but is <em>not</em> valid for HA — a captured {@code (token, sequence)} pair
 * could be replayed against a different controller. Production (per ADR 4)
 * requires Valkey, which provides a shared {@code SequenceWindowStore}.
 */
public final class WorkloadIdentityRegistry {

    private static final Logger logger = LoggerFactory.getLogger(WorkloadIdentityRegistry.class);

    private static final Duration DEFAULT_TOKEN_TTL = RedisKeys.defaultPluginTokenTtl();
    private static final String TOKEN_PREFIX = "ptk_";
    private static final int RANDOM_BYTES = 32;

    public record PluginTokenEntry(String tokenId, String instanceId, Instant issuedAt, Instant expiresAt) {}

    public interface SequenceWindowStore {
        boolean acceptSequence(String instanceId, long sequence, Duration ttl);

        void clearSequence(String instanceId);
    }

    private final Clock clock;
    private final Duration tokenTtl;
    // A token that has expired may still bootstrap exactly one refresh within this
    // grace window past its expiry. Normal API calls stay strict (expired = 401);
    // only the refresh path is lenient. Without this, the plugin can never recover:
    // refresh is reactive-on-401, but by the time a request 401s the token is
    // already expired, and refresh authenticates with that same expired token.
    private final Duration refreshGraceWindow;
    private final SecureRandom random;
    private final SequenceWindowStore sequenceWindowStore;
    private final Map<String, PluginTokenEntry> pluginTokens = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAcceptedSequenceByInstance = new ConcurrentHashMap<>();

    public WorkloadIdentityRegistry() {
        this(Clock.systemUTC(), DEFAULT_TOKEN_TTL);
    }

    public WorkloadIdentityRegistry(SequenceWindowStore sequenceWindowStore) {
        this(Clock.systemUTC(), DEFAULT_TOKEN_TTL, sequenceWindowStore);
    }

    public WorkloadIdentityRegistry(Clock clock, Duration tokenTtl) {
        this(clock, tokenTtl, null);
    }

    WorkloadIdentityRegistry(Clock clock, Duration tokenTtl, SequenceWindowStore sequenceWindowStore) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenTtl = Objects.requireNonNull(tokenTtl, "tokenTtl");
        // Grace == TTL: a token is refreshable up to one TTL past expiry, bounding
        // a stale token's total refresh lifetime to 2×TTL. Covers HA failover gaps
        // and the reactive-refresh-after-expiry case.
        this.refreshGraceWindow = tokenTtl;
        this.sequenceWindowStore = sequenceWindowStore;
        this.random = new SecureRandom();
        if (sequenceWindowStore == null) {
            logger.warn("WorkloadIdentityRegistry constructed without SequenceWindowStore — "
                    + "workload-token replay protection runs against a local map only. This is "
                    + "fine for development; HA / production must wire a Valkey-backed store "
                    + "(per ADR 4) so the sequence window is shared across controllers.");
        }
    }

    /** Issue a new bearer token bound to the instance. Returns the opaque token string. */
    public String issuePluginToken(String instanceId) {
        String token = generateTokenString();
        putEntry(token, instanceId, clock.instant());
        return token;
    }

    /**
     * Register an externally-supplied token string (test fixtures and bulk
     * import paths). Production code should prefer {@link #issuePluginToken}.
     */
    public void registerPluginToken(String token, String instanceId) {
        putEntry(token, instanceId, clock.instant());
    }

    /** Hydrate from a persisted snapshot on startup. Clears existing state. */
    public void hydrate(Map<String, PluginTokenEntry> snapshot) {
        pluginTokens.clear();
        lastAcceptedSequenceByInstance.clear();
        pluginTokens.putAll(snapshot);
    }

    /**
     * Import a token with an explicit issued-at timestamp. The expiry is
     * derived from the registry TTL. Used for tests and legacy hydration
     * paths that only carry creation time.
     */
    public void importPluginToken(String token, String instanceId, Instant issuedAt) {
        putEntry(token, instanceId, issuedAt);
    }

    /**
     * Validate a bearer token. Returns the associated instance id, or empty if
     * the token is unknown, expired, or the backing instance is not running.
     * Expired entries are evicted on the read path.
     */
    public Optional<String> validatePluginToken(String token, Function<String, Optional<InstanceInfo>> instanceLookup) {
        var entry = pluginTokens.get(token);
        if (!isEntryUsable(token, entry, instanceLookup)) {
            return Optional.empty();
        }
        return Optional.of(entry.instanceId());
    }

    public Optional<String> validatePluginToken(
            String token, long sequence, Function<String, Optional<InstanceInfo>> instanceLookup) {
        var entry = pluginTokens.get(token);
        if (!isEntryUsable(token, entry, instanceLookup)) {
            return Optional.empty();
        }
        if (!validateSequence(entry.instanceId(), sequence)) {
            return Optional.empty();
        }
        return Optional.of(entry.instanceId());
    }

    /**
     * Exchange a current token for a new one. The old token is atomically
     * invalidated on success so a captured token cannot refresh twice.
     * Returns the new token or empty if the old one was invalid.
     */
    public Optional<RefreshResult> refreshPluginToken(
            String currentToken, Function<String, Optional<InstanceInfo>> instanceLookup) {
        var existing = pluginTokens.get(currentToken);
        if (!isEntryRefreshable(currentToken, existing, instanceLookup)) {
            return Optional.empty();
        }
        if (!pluginTokens.remove(currentToken, existing)) {
            return Optional.empty();
        }
        String newToken = generateTokenString();
        var newEntry = putEntry(newToken, existing.instanceId(), clock.instant());
        return Optional.of(new RefreshResult(newToken, newEntry));
    }

    public Optional<RefreshResult> refreshPluginToken(
            String currentToken, long sequence, Function<String, Optional<InstanceInfo>> instanceLookup) {
        var existing = pluginTokens.get(currentToken);
        if (!isEntryRefreshable(currentToken, existing, instanceLookup)) {
            return Optional.empty();
        }
        if (!validateSequence(existing.instanceId(), sequence)) {
            return Optional.empty();
        }
        if (!pluginTokens.remove(currentToken, existing)) {
            return Optional.empty();
        }
        String newToken = generateTokenString();
        var newEntry = putEntry(newToken, existing.instanceId(), clock.instant());
        return Optional.of(new RefreshResult(newToken, newEntry));
    }

    /** Revoke a specific bearer token. No-op if unknown. */
    public void revokeToken(String token) {
        var removed = pluginTokens.remove(token);
        if (removed != null) {
            clearSequenceIfUnused(removed.instanceId());
        }
    }

    /** Revoke every token tied to the instance. */
    public void unregisterPluginTokens(String instanceId) {
        pluginTokens.entrySet().removeIf(e -> e.getValue().instanceId().equals(instanceId));
        lastAcceptedSequenceByInstance.remove(instanceId);
        // Clear the (Valkey-backed) replay window too. Instance ids are reused
        // across restarts/rescale; leaving a stale high watermark would reject
        // the restarted plugin's sequence (which restarts at 1) until the TTL
        // expired -- a self-inflicted permanent 401 on every sequenced call.
        if (sequenceWindowStore != null) {
            sequenceWindowStore.clearSequence(instanceId);
        }
    }

    public Map<String, PluginTokenEntry> pluginTokens() {
        return Map.copyOf(pluginTokens);
    }

    public List<PluginTokenSnapshot> tokenSnapshots() {
        return pluginTokens.values().stream()
                .map(entry -> new PluginTokenSnapshot(
                        entry.tokenId(), entry.instanceId(), entry.issuedAt(), entry.expiresAt()))
                .sorted(java.util.Comparator.comparing(PluginTokenSnapshot::instanceId)
                        .thenComparing(PluginTokenSnapshot::tokenId))
                .toList();
    }

    public boolean revokeTokenId(String tokenId) {
        Objects.requireNonNull(tokenId, "tokenId");
        for (var entry : pluginTokens.entrySet()) {
            if (entry.getValue().tokenId().equals(tokenId)) {
                if (pluginTokens.remove(entry.getKey(), entry.getValue())) {
                    clearSequenceIfUnused(entry.getValue().instanceId());
                    return true;
                }
            }
        }
        return false;
    }

    public Optional<PluginTokenEntry> getPluginToken(String token) {
        return Optional.ofNullable(pluginTokens.get(token));
    }

    public Duration tokenTtl() {
        return tokenTtl;
    }

    private PluginTokenEntry putEntry(String token, String instanceId, Instant issuedAt) {
        var entry = new PluginTokenEntry(UUID.randomUUID().toString(), instanceId, issuedAt, issuedAt.plus(tokenTtl));
        pluginTokens.put(token, entry);
        return entry;
    }

    /**
     * Usability for normal API calls: strict on expiry. An expired-but-within-grace
     * token returns {@code false} here (so normal calls 401) but is deliberately
     * NOT evicted, so the reactive refresh that the 401 triggers can still find it.
     * Only a token expired beyond the refresh grace is evicted.
     */
    private boolean isEntryUsable(
            String token, PluginTokenEntry entry, Function<String, Optional<InstanceInfo>> instanceLookup) {
        if (entry == null) {
            return false;
        }
        Instant now = clock.instant();
        if (now.isAfter(entry.expiresAt().plus(refreshGraceWindow))) {
            pluginTokens.remove(token, entry);
            clearSequenceIfUnused(entry.instanceId());
            return false;
        }
        if (now.isAfter(entry.expiresAt())) {
            // expired but within grace: invalid for normal calls, retained for refresh
            return false;
        }
        return instanceLive(entry, instanceLookup);
    }

    /**
     * Usability for the refresh path: accepts a token that is expired but still
     * within {@link #refreshGraceWindow}, so the plugin can bootstrap a fresh
     * token from a just-expired one (reactive-on-401 refresh, or after an HA
     * failover gap). Tokens past the grace window are evicted and rejected.
     */
    private boolean isEntryRefreshable(
            String token, PluginTokenEntry entry, Function<String, Optional<InstanceInfo>> instanceLookup) {
        if (entry == null) {
            return false;
        }
        if (clock.instant().isAfter(entry.expiresAt().plus(refreshGraceWindow))) {
            pluginTokens.remove(token, entry);
            clearSequenceIfUnused(entry.instanceId());
            return false;
        }
        return instanceLive(entry, instanceLookup);
    }

    /**
     * The plugin authenticates throughout its lifetime, including the startup
     * handshake (token refresh, cache fetch, SSE, POST /ready) while the instance
     * is still STARTING -- it is precisely the /ready call that promotes the
     * instance to RUNNING. Gating on RUNNING alone would deadlock startup. Accept
     * the live states; reject pre-launch (SCHEDULED/PREPARING) and terminal
     * (STOPPING/STOPPED/CRASHED).
     */
    private boolean instanceLive(PluginTokenEntry entry, Function<String, Optional<InstanceInfo>> instanceLookup) {
        var instance = instanceLookup.apply(entry.instanceId());
        if (instance.isEmpty()) {
            return false;
        }
        InstanceInfo resolvedInstance = instance.get();
        if (!entry.instanceId().equals(resolvedInstance.id())) {
            return false;
        }
        InstanceState state = resolvedInstance.state();
        return state == InstanceState.STARTING || state == InstanceState.RUNNING || state == InstanceState.DRAINING;
    }

    private boolean validateSequence(String instanceId, long sequence) {
        if (sequence <= 0) {
            return false;
        }
        if (sequenceWindowStore != null) {
            return sequenceWindowStore.acceptSequence(instanceId, sequence, tokenTtl);
        }
        var accepted = new java.util.concurrent.atomic.AtomicBoolean(false);
        lastAcceptedSequenceByInstance.compute(instanceId, (ignored, current) -> {
            if (current == null || sequence > current) {
                accepted.set(true);
                return sequence;
            }
            return current;
        });
        return accepted.get();
    }

    private void clearSequenceIfUnused(String instanceId) {
        boolean hasRemainingToken = pluginTokens.values().stream()
                .anyMatch(entry -> entry.instanceId().equals(instanceId));
        if (!hasRemainingToken) {
            lastAcceptedSequenceByInstance.remove(instanceId);
            if (sequenceWindowStore != null) {
                sequenceWindowStore.clearSequence(instanceId);
            }
        }
    }

    private String generateTokenString() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return TOKEN_PREFIX + HexFormat.of().formatHex(bytes);
    }

    public record RefreshResult(String token, PluginTokenEntry entry) {}

    public record PluginTokenSnapshot(String tokenId, String instanceId, Instant issuedAt, Instant expiresAt) {}
}
