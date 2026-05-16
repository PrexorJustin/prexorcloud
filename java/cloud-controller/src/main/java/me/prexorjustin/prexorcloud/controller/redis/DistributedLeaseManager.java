package me.prexorjustin.prexorcloud.controller.redis;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis-based distributed lease manager for partitioning work across controllers.
 * Uses {@code SET key value NX EX ttl} for atomic lease acquisition.
 */
public final class DistributedLeaseManager {

    private static final Logger logger = LoggerFactory.getLogger(DistributedLeaseManager.class);
    private static final String KEY_PREFIX = RedisKeys.LEASE_PREFIX;
    private static final String TOKEN_KEY_PREFIX = RedisKeys.LEASE_TOKEN_PREFIX;
    private static final String VALUE_SEPARATOR = "|";

    public record Lease(String resource, long token) {}

    /**
     * Read-only snapshot of one lease key for diagnostics. {@code holder} is the
     * controller id currently holding the lease; {@code ttlSeconds} is the
     * remaining lease TTL as reported by Redis ({@code -1} if no TTL,
     * {@code -2} if expired between SCAN and the TTL probe).
     */
    public record LeaseSnapshot(String resource, String holder, long token, long ttlSeconds) {}

    @FunctionalInterface
    public interface LeaseChangeListener {
        void leaseAcquired(Lease lease);
    }

    private record LeaseHolder(String controllerId, long token) {}

    private final RedisCommands<String, String> commands;
    private final String controllerId;
    private final long leaseTtlSeconds;
    private final List<LeaseChangeListener> listeners = new CopyOnWriteArrayList<>();
    private volatile MetricsCollector metricsCollector;

    public DistributedLeaseManager(RedisCommands<String, String> commands, String controllerId, long leaseTtlSeconds) {
        this.commands = commands;
        this.controllerId = controllerId;
        this.leaseTtlSeconds = leaseTtlSeconds;
    }

    public void attachMetricsCollector(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * Try to acquire a lease for the given resource. Returns true if this controller
     * now holds the lease (either newly acquired or renewed).
     */
    public boolean tryAcquire(String resource) {
        return tryAcquireLease(resource).isPresent();
    }

    public void addLeaseChangeListener(LeaseChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Try to acquire a lease for the given resource and return the current fencing
     * token if successful.
     */
    public java.util.Optional<Lease> tryAcquireLease(String resource) {
        String key = RedisKeys.lease(resource);
        MetricsCollector mc = metricsCollector;

        LeaseHolder currentHolder = parseHolder(commands.get(key));
        if (currentHolder != null && controllerId.equals(currentHolder.controllerId())) {
            commands.expire(key, RedisKeys.leaseTtl(leaseTtlSeconds).getSeconds());
            if (mc != null) mc.recordLeaseRenewal();
            return java.util.Optional.of(new Lease(resource, currentHolder.token()));
        }

        long token = commands.incr(RedisKeys.leaseToken(resource));
        String result = commands.set(
                key,
                encodeValue(controllerId, token),
                SetArgs.Builder.nx().ex(RedisKeys.leaseTtl(leaseTtlSeconds).getSeconds()));
        if ("OK".equals(result)) {
            var lease = new Lease(resource, token);
            if (mc != null) mc.recordLeaseAcquisition();
            notifyLeaseAcquired(lease);
            return java.util.Optional.of(lease);
        }

        LeaseHolder observedHolder = parseHolder(commands.get(key));
        if (observedHolder != null && controllerId.equals(observedHolder.controllerId())) {
            commands.expire(key, RedisKeys.leaseTtl(leaseTtlSeconds).getSeconds());
            if (mc != null) mc.recordLeaseRenewal();
            return java.util.Optional.of(new Lease(resource, observedHolder.token()));
        }
        if (mc != null) mc.recordLeaseContention();
        return java.util.Optional.empty();
    }

    /**
     * Returns true if the provided lease token is still the active lease held by
     * this controller.
     */
    public boolean isCurrent(Lease lease) {
        if (lease == null) {
            return false;
        }
        LeaseHolder holder = parseHolder(commands.get(RedisKeys.lease(lease.resource())));
        return holder != null && controllerId.equals(holder.controllerId()) && holder.token() == lease.token();
    }

    /**
     * Release a lease only if this controller holds it.
     */
    public void release(String resource) {
        String key = KEY_PREFIX + resource;
        LeaseHolder holder = parseHolder(commands.get(key));
        if (holder != null && controllerId.equals(holder.controllerId())) {
            commands.del(key);
        }
    }

    /**
     * Release a lease only if this controller still holds the exact fencing token.
     */
    public void release(Lease lease) {
        if (isCurrent(lease)) {
            commands.del(KEY_PREFIX + lease.resource());
        }
    }

    /**
     * Return the current lease for a resource when this controller still owns it.
     */
    public java.util.Optional<Lease> currentLease(String resource) {
        LeaseHolder holder = parseHolder(commands.get(RedisKeys.lease(resource)));
        if (holder == null || !controllerId.equals(holder.controllerId())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Lease(resource, holder.token()));
    }

    private static String encodeValue(String controllerId, long token) {
        return controllerId + VALUE_SEPARATOR + token;
    }

    private static LeaseHolder parseHolder(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int separatorIndex = value.lastIndexOf(VALUE_SEPARATOR);
        if (separatorIndex <= 0 || separatorIndex == value.length() - 1) {
            return new LeaseHolder(value, 0);
        }
        String holder = value.substring(0, separatorIndex);
        String tokenRaw = value.substring(separatorIndex + 1);
        try {
            return new LeaseHolder(holder, Long.parseLong(tokenRaw));
        } catch (NumberFormatException _) {
            return new LeaseHolder(value, 0);
        }
    }

    private boolean heldByThisController(String key) {
        LeaseHolder holder = parseHolder(commands.get(key));
        return holder != null && controllerId.equals(holder.controllerId());
    }

    private void notifyLeaseAcquired(Lease lease) {
        for (LeaseChangeListener listener : listeners) {
            try {
                listener.leaseAcquired(lease);
            } catch (Exception e) {
                logger.warn(
                        "Lease listener failed for resource {} token {}: {}",
                        lease.resource(),
                        lease.token(),
                        e.getMessage());
            }
        }
    }

    /**
     * Scan all active leases in the keyspace and return them. Used by the
     * operator diagnostics surface; {@code commands} may be a controller's
     * production Redis connection or a backup-mode connection — the call is
     * read-only and never mutates lease state.
     */
    public static java.util.List<LeaseSnapshot> scanAllLeases(RedisCommands<String, String> commands) {
        if (commands == null) return java.util.List.of();
        java.util.List<LeaseSnapshot> snapshots = new java.util.ArrayList<>();
        try {
            var cursor = commands.scan(
                    io.lettuce.core.ScanArgs.Builder.matches(KEY_PREFIX + "*").limit(500));
            while (true) {
                for (String key : cursor.getKeys()) {
                    LeaseHolder holder = parseHolder(commands.get(key));
                    if (holder == null) continue;
                    long ttl = commands.ttl(key);
                    String resource = key.startsWith(KEY_PREFIX) ? key.substring(KEY_PREFIX.length()) : key;
                    snapshots.add(new LeaseSnapshot(resource, holder.controllerId(), holder.token(), ttl));
                }
                if (cursor.isFinished()) break;
                cursor = commands.scan(
                        cursor,
                        io.lettuce.core.ScanArgs.Builder.matches(KEY_PREFIX + "*")
                                .limit(500));
            }
        } catch (Exception e) {
            logger.warn("scanAllLeases failed: {}", e.getMessage());
        }
        snapshots.sort(java.util.Comparator.comparing(LeaseSnapshot::resource));
        return snapshots;
    }

    /**
     * Release all leases held by this controller. Called on shutdown.
     */
    public void releaseAll() {
        try {
            var cursor = commands.scan(
                    io.lettuce.core.ScanArgs.Builder.matches(KEY_PREFIX + "*").limit(500));
            while (true) {
                for (String key : cursor.getKeys()) {
                    if (heldByThisController(key)) {
                        commands.del(key);
                    }
                }
                if (cursor.isFinished()) break;
                cursor = commands.scan(
                        cursor,
                        io.lettuce.core.ScanArgs.Builder.matches(KEY_PREFIX + "*")
                                .limit(500));
            }
            logger.info("Released all leases for controller {}", controllerId);
        } catch (Exception e) {
            logger.warn("Failed to release all leases: {}", e.getMessage());
        }
    }
}
