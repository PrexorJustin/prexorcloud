package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import me.prexorjustin.prexorcloud.controller.config.ClusterConfig;
import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;
import me.prexorjustin.prexorcloud.controller.config.CorsConfig;
import me.prexorjustin.prexorcloud.controller.config.HttpConfig;
import me.prexorjustin.prexorcloud.controller.config.NetworkConfig;
import me.prexorjustin.prexorcloud.controller.config.RaftConfig;
import me.prexorjustin.prexorcloud.controller.config.RedisConfig;
import me.prexorjustin.prexorcloud.controller.config.RuntimeConfig;
import me.prexorjustin.prexorcloud.controller.config.SecurityControllerConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for {@link ClusterLeaseManager}: lease acquisition,
 * contention between holders, renewal, release, and the runUnderLease
 * convenience. Backs the manager with a real {@link ClusterControlService}
 * so the assertions exercise the actual Raft state machine semantics
 * (LEASE_HELD rejection, holder-identity check on release).
 */
class ClusterLeaseManagerTest {

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static ControllerConfig sampleConfig(Path tmp, int raftPort) {
        return new ControllerConfig(
                "node-local-uuid",
                new HttpConfig("0.0.0.0", 8080, new CorsConfig(List.of())),
                null,
                new NetworkConfig(List.of("10.0.0.0/8")),
                null,
                null,
                null,
                null,
                new RuntimeConfig(RuntimeConfig.PRODUCTION),
                new SecurityControllerConfig("jwt-secret-1234567890123456789012345678901234567890", 720, "", null),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                new RedisConfig("redis://10.0.0.50:6379"),
                new ClusterConfig(null, null, null),
                new RaftConfig("127.0.0.1", raftPort, tmp.resolve("raft").toString(), List.of()));
    }

    @Test
    @DisplayName("first acquire wins; contending holder is rejected; release frees it")
    void contendingHoldersRespectExclusivity(@TempDir Path tmp) throws Exception {
        int port = freePort();
        try (ClusterControlService svc = new ClusterControlService(sampleConfig(tmp, port), "controller-1")) {
            svc.start();
            var plane = svc.controlPlane();
            var a = new ClusterLeaseManager(plane, "controller-a");
            var b = new ClusterLeaseManager(plane, "controller-b");

            assertTrue(a.tryAcquire("audit-pruner", Duration.ofMinutes(5)));
            assertFalse(
                    b.tryAcquire("audit-pruner", Duration.ofMinutes(5)),
                    "second holder must be rejected while first one's lease is valid");

            // Same holder re-acquiring is treated as a re-grant (state machine
            // collapses the self-conflict so renewals from the holder's own next
            // tick don't bounce).
            assertTrue(a.tryAcquire("audit-pruner", Duration.ofMinutes(5)));

            a.release("audit-pruner");
            assertTrue(
                    b.tryAcquire("audit-pruner", Duration.ofMinutes(5)),
                    "after release the contending holder must acquire cleanly");
        }
    }

    @Test
    @DisplayName("renew succeeds for the holder, fails for a non-holder")
    void renewRespectsHolderIdentity(@TempDir Path tmp) throws Exception {
        int port = freePort();
        try (ClusterControlService svc = new ClusterControlService(sampleConfig(tmp, port), "controller-1")) {
            svc.start();
            var plane = svc.controlPlane();
            var a = new ClusterLeaseManager(plane, "controller-a");
            var b = new ClusterLeaseManager(plane, "controller-b");

            assertTrue(a.tryAcquire("scheduler", Duration.ofMinutes(1)));
            assertTrue(a.tryRenew("scheduler"), "holder can renew");
            assertFalse(b.tryRenew("scheduler"), "non-holder cannot renew");
            assertFalse(b.tryRenew("does-not-exist"), "renew on unknown lease must return false, not throw");
        }
    }

    @Test
    @DisplayName("runUnderLease executes work exactly once across contending controllers")
    void runUnderLeaseExecutesExactlyOnce(@TempDir Path tmp) throws Exception {
        int port = freePort();
        try (ClusterControlService svc = new ClusterControlService(sampleConfig(tmp, port), "controller-1")) {
            svc.start();
            var plane = svc.controlPlane();
            var a = new ClusterLeaseManager(plane, "controller-a");
            var b = new ClusterLeaseManager(plane, "controller-b");

            AtomicInteger ranA = new AtomicInteger();
            AtomicInteger ranB = new AtomicInteger();

            // a starts work; while it's holding, b's tick should be a no-op.
            boolean aRan = a.runUnderLease("audit-pruner", Duration.ofSeconds(10), () -> {
                ranA.incrementAndGet();
                // Inside a's protected work, b tries and should be rejected.
                boolean bRanInner = b.runUnderLease("audit-pruner", Duration.ofSeconds(10), ranB::incrementAndGet);
                assertFalse(bRanInner, "contending holder must NOT run work while lease is held");
            });
            assertTrue(aRan);
            assertTrue(ranA.get() == 1);
            assertTrue(ranB.get() == 0);
        }
    }
}
