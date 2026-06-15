package me.prexorjustin.prexorcloud.controller.cluster.raft;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterEntry;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftPeer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the SM's {@code AddMember}/{@code RemoveMember} commits onto the
 * Ratis-level Raft membership. Every committed entry on every controller's
 * state machine wakes a single background thread that, if and only if this
 * controller is the current Raft leader, calls
 * {@code RaftBootstrap#setConfiguration} with the current SM member list. Idempotent
 * — Ratis treats setConfiguration with the same membership as a no-op.
 *
 * <p>Race the reconciler has to tolerate: a freshly-joining controller calls
 * {@code RequestJoin}, which commits {@code AddMember} server-side before the
 * joiner has called {@code GroupManagementApi.add()} on its own server. If the
 * reconciler fires immediately, the leader's setConfiguration will stall
 * waiting for the joiner's quorum acknowledgement. We swallow the failure and
 * retry on a short backoff; once the joiner's add() lands, the next attempt
 * succeeds. Spike findings (Q A in {@code docs/engineering/ratis-spike.md})
 * cover the ordering constraint.
 */
public final class MembershipReconciler implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MembershipReconciler.class);
    private static final long RETRY_DELAY_MS = 500;
    private static final long SETCONFIG_TIMEOUT_HINT_MS = 5_000;
    // How many reconcile attempts to keep retrying while this node is not (yet) the leader, before
    // giving up. Tolerates a brief post-election settle window where leadership is still converging.
    private static final int LEADER_SETTLE_ATTEMPTS = 6;

    private final RaftBootstrap raft;
    private final ClusterControlStateMachine sm;
    private final LinkedBlockingQueue<Object> wake = new LinkedBlockingQueue<>();
    private final Thread worker;
    private volatile boolean stopped = false;

    public MembershipReconciler(RaftBootstrap raft, ClusterControlStateMachine sm) {
        this.raft = raft;
        this.sm = sm;
        this.worker = new Thread(this::run, "cluster-membership-reconciler");
        this.worker.setDaemon(true);
    }

    /** Subscribe to SM commits and start the worker. Safe to call before any peers exist. */
    public void start() {
        sm.setCommitListener(this::onCommit);
        worker.start();
        // Startup reconcile kick: don't wait for the next AddMember/RemoveMember commit. If the Ratis
        // group has drifted from the SM member list (e.g. a join whose setConfiguration never committed),
        // nothing else would re-trigger alignment — so fire one idempotent pass on startup.
        requestReconcile();
    }

    /** Wake the reconciler for an idempotent pass (startup kick / external nudge). */
    public void requestReconcile() {
        wake.offer(new Object());
    }

    private void onCommit(ClusterEntry entry) {
        if (entry instanceof ClusterEntry.AddMember || entry instanceof ClusterEntry.RemoveMember) {
            wake.offer(new Object());
        }
    }

    private void run() {
        while (!stopped) {
            try {
                // Drain the queue — multiple commits collapse into one reconcile pass.
                if (wake.poll(RETRY_DELAY_MS * 4, TimeUnit.MILLISECONDS) == null) {
                    continue;
                }
                wake.clear();
                tryReconcileWithRetries();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void tryReconcileWithRetries() {
        for (int attempt = 1; attempt <= 30 && !stopped; attempt++) {
            try {
                if (!raft.isLeader()) {
                    // Only the leader drives setConfiguration. Tolerate a brief post-election settle
                    // window (leadership still converging) instead of bailing on the first check.
                    if (attempt >= LEADER_SETTLE_ATTEMPTS) {
                        return;
                    }
                } else {
                    List<Member> members = sm.listMembers();
                    if (members.isEmpty()) {
                        return; // nothing to configure
                    }
                    List<RaftPeer> peers =
                            members.stream().map(MembershipReconciler::toPeer).toList();
                    raft.setConfiguration(peers);
                    logger.info(
                            "Raft membership reconciled to {} peer(s) on attempt {}: {}",
                            peers.size(),
                            attempt,
                            peers.stream().map(p -> p.getId().toString()).toList());
                    return;
                }
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                // NotLeaderException, joiner-not-yet-up timeouts, transient gRPC failures all land here.
                // The setConfiguration is idempotent under retry; keep trying for a bit.
                logger.debug(
                        "setConfiguration attempt {} failed (will retry up to ~{}ms): {}",
                        attempt,
                        SETCONFIG_TIMEOUT_HINT_MS,
                        e.getMessage());
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        logger.warn("setConfiguration retries exhausted — membership may be out of sync until next AddMember");
    }

    private static RaftPeer toPeer(Member m) {
        return RaftPeer.newBuilder().setId(m.nodeId()).setAddress(m.raftAddr()).build();
    }

    /**
     * Helper for tests + bootstrap wiring: build a {@link RaftGroup} for the given group id from
     * the SM's current member list. Empty member list returns an empty-peer group, which is what
     * Day-0 single-node startup needs before AddMember commits.
     */
    public static RaftGroup currentGroup(org.apache.ratis.protocol.RaftGroupId groupId, List<Member> members) {
        return RaftGroup.valueOf(
                groupId, members.stream().map(MembershipReconciler::toPeer).toList());
    }

    @Override
    public void close() {
        stopped = true;
        worker.interrupt();
    }
}
