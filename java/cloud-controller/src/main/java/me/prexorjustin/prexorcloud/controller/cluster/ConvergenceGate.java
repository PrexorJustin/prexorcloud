package me.prexorjustin.prexorcloud.controller.cluster;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The convergence (observation-phase) gate — the Kubernetes node-readiness analog that makes
 * "derive actual state from daemons" safe across a leadership change.
 *
 * <p>The hazard: on takeover a new leader reads desired state from Mongo (e.g. {@code want 3}) but,
 * in the window before the daemons have reconnected and reported their inventory, observes
 * {@code running 0}. A naive reconcile would spawn 3 <em>while the real 3 are still running</em> on
 * not-yet-reported daemons — 2× instances, the exact divergence the single-writer rewrite kills,
 * reborn at every leader change.
 *
 * <p>The fix: on leadership acquisition the leader enters an <b>observation phase</b> and must NOT
 * run scale-reconcile (no spawn, no scale-down, no reschedule) until either every node registered in
 * Mongo has reconnected and reported, or a bounded grace window expires. Nodes that miss the window
 * are treated as down and their instances become reschedule-eligible only <em>then</em>.
 *
 * <p>The grace window MUST exceed {@code max(daemon redirect + reconnect)} (Phase 3 timing) or it
 * false-evicts during the redirect storm. Idempotent recovery redispatch and pure state-learning are
 * NOT gated by this — only scaling decisions are.
 */
public final class ConvergenceGate {

    private static final Logger logger = LoggerFactory.getLogger(ConvergenceGate.class);

    private final Supplier<Set<String>> expectedNodes;
    private final Duration graceWindow;
    private final LongSupplier nanoClock;

    private volatile boolean observing = false;
    private volatile long observationStartNanos = 0L;
    private volatile Set<String> expectedAtAcquire = Set.of();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    // observability
    private volatile long lastObservationDurationMs = -1L;

    public ConvergenceGate(Supplier<Set<String>> expectedNodes, Duration graceWindow) {
        this(expectedNodes, graceWindow, System::nanoTime);
    }

    public ConvergenceGate(Supplier<Set<String>> expectedNodes, Duration graceWindow, LongSupplier nanoClock) {
        this.expectedNodes = expectedNodes;
        this.graceWindow = graceWindow;
        this.nanoClock = nanoClock;
    }

    /**
     * Begin observing on leadership acquisition. Snapshots the set of nodes Mongo expects to report;
     * scale-reconcile is blocked until they all report or the grace window expires. If no nodes are
     * registered there is nothing to wait for, so the gate opens immediately.
     */
    public synchronized void beginObservation() {
        reported.clear();
        expectedAtAcquire = Set.copyOf(expectedNodes.get());
        observationStartNanos = nanoClock.getAsLong();
        observing = !expectedAtAcquire.isEmpty();
        if (observing) {
            logger.info(
                    "Convergence: entering observation phase — deferring scale-reconcile until {} node(s) report or {} elapses",
                    expectedAtAcquire.size(),
                    graceWindow);
        } else {
            logger.info("Convergence: no nodes registered, observation phase skipped — scale-reconcile enabled");
        }
    }

    /** Stop observing on leadership loss; the (now-follower) controller reconciles nothing anyway. */
    public synchronized void reset() {
        observing = false;
        reported.clear();
    }

    /** Record that a daemon has reconnected and reported its inventory. */
    public void nodeReported(String nodeId) {
        if (!observing) {
            return;
        }
        reported.add(nodeId);
        checkExit();
    }

    /**
     * Whether scale-reconcile (spawn / scale-down / reschedule) is currently permitted. Always true
     * outside an observation phase; during one, true only once all expected nodes have reported or the
     * grace window has expired.
     */
    public boolean canScaleReconcile() {
        checkExit();
        return !observing;
    }

    /** Whether the gate is currently in its observation phase (observability). */
    public boolean isObserving() {
        return observing;
    }

    /** Duration of the most recent observation phase, in ms, or -1 if none has completed. */
    public long lastObservationDurationMs() {
        return lastObservationDurationMs;
    }

    private synchronized void checkExit() {
        if (!observing) {
            return;
        }
        long elapsedNanos = nanoClock.getAsLong() - observationStartNanos;
        boolean allReported = reported.containsAll(expectedAtAcquire);
        boolean graceExpired = elapsedNanos >= graceWindow.toNanos();
        if (!allReported && !graceExpired) {
            return;
        }
        observing = false;
        lastObservationDurationMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        if (allReported) {
            logger.info(
                    "Convergence: observation complete — all {} node(s) reported in {}ms; scale-reconcile enabled",
                    expectedAtAcquire.size(),
                    lastObservationDurationMs);
        } else {
            var missing = new java.util.HashSet<>(expectedAtAcquire);
            missing.removeAll(reported);
            logger.warn(
                    "Convergence: grace window ({}) expired after {}ms with {}/{} node(s) reported; "
                            + "treating {} as down (reschedule-eligible). Missing: {}",
                    graceWindow,
                    lastObservationDurationMs,
                    reported.size(),
                    expectedAtAcquire.size(),
                    missing.size(),
                    missing);
        }
    }
}
