package me.prexorjustin.prexorcloud.controller.state;

import java.util.Map;
import java.util.Optional;

/**
 * Durable persistence for plugin/workload tokens and their replay-protection
 * sequence windows. Backs the in-memory {@link WorkloadIdentityRegistry} cache:
 * the issuing leader writes through here before handing a token out, and any
 * leader that does not have a token cached reads it back on demand. This is the
 * mechanism that keeps a leadership change from 401-ing every running plugin —
 * the new leader's cache is cold, but the token is still durable here.
 *
 * <p>Extends {@link WorkloadIdentityRegistry.SequenceWindowStore} so callback
 * replay protection survives a failover too: the accepted-sequence high-water
 * mark per instance must not reset to zero when leadership moves.
 */
public interface WorkloadTokenStore extends WorkloadIdentityRegistry.SequenceWindowStore {

    /** Write (or overwrite) the durable record for an issued token. */
    void saveToken(String token, WorkloadIdentityRegistry.PluginTokenEntry entry);

    /** Remove a token's durable record (revoke / refresh / instance teardown). */
    void removeToken(String token);

    /** Read a single token back, e.g. to hydrate a cold leader cache on a validation miss. */
    Optional<WorkloadIdentityRegistry.PluginTokenEntry> loadToken(String token);

    /** Load every persisted token, used to warm the cache on leadership takeover. */
    Map<String, WorkloadIdentityRegistry.PluginTokenEntry> loadAllTokens();
}
