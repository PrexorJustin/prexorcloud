package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.Optional;

/**
 * Durable record of when each group last scaled, so the per-group scaling cooldown survives a
 * controller failover (Group/Template v2, Phase 1). Before this, the cooldown lived only in the
 * leader's memory and reset on every leadership change -- a new leader could scale a group one step
 * early. Only the leader runs the scheduler, so there is a single writer; this store is purely the
 * failover hand-off.
 *
 * <p>A small dedicated store rather than two more methods on the large {@link StateStore}: it keeps
 * {@code ScalingEvaluator}'s dependency to just these two methods, and the live impl
 * ({@code MongoScaleActionStore}) is its own class against its own {@code scale_actions} collection.
 */
public interface ScaleActionStore {

    /** Persist that {@code group} scaled at {@code when} (upsert, one document per group). */
    void recordScaleAction(String group, Instant when);

    /** The last recorded scale time for {@code group}, or empty if none is on record. */
    Optional<Instant> getLastScaleAction(String group);
}
