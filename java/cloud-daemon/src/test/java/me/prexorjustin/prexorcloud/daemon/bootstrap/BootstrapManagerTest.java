package me.prexorjustin.prexorcloud.daemon.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import me.prexorjustin.prexorcloud.common.concurrent.Backoff;
import me.prexorjustin.prexorcloud.daemon.config.ControllerEndpoint;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

/**
 * Covers the bootstrap controller sweep: trying each seed until one enrolls the node, failing fast on a
 * permanent rejection (bad token), and retrying the whole sweep on a transient all-down outage. Uses an
 * injected exchange stub + a fast (no real sleep) retry policy so it runs offline.
 */
final class BootstrapManagerTest {

    private static final Path UNUSED = Path.of("/tmp/prexor-bootstrap-test-unused");

    private static ControllerEndpoint ep(String host) {
        return new ControllerEndpoint(host, 9090);
    }

    private static StatusRuntimeException sre(Status status) {
        return status.asRuntimeException();
    }

    /** A retry policy that uses the production transient classifier but with negligible delays. */
    private static Backoff.Policy fast(int attempts) {
        return new Backoff.Policy(
                attempts, Duration.ofMillis(1), Duration.ofMillis(2), 0.0, BootstrapManager.RETRY_TRANSIENT);
    }

    @Test
    void sweepsToTheNextControllerOnTransientFailureAndStopsAtFirstSuccess() throws Exception {
        var calls = new ArrayList<String>();
        BootstrapManager.JoinTokenExchange exchange = (controller, token, node) -> {
            calls.add(controller.host());
            if (controller.host().equals("a")) {
                throw sre(Status.UNAVAILABLE);
            }
            // "b" succeeds
        };
        var mgr = new BootstrapManager(List.of(ep("a"), ep("b"), ep("c")), UNUSED, exchange);

        mgr.bootstrap("token", "node-1", fast(1));

        assertEquals(List.of("a", "b"), calls, "tries a (transient), succeeds on b, never reaches c");
    }

    @Test
    void failsFastOnPermanentRejectionWithoutTryingOtherControllers() {
        var calls = new ArrayList<String>();
        BootstrapManager.JoinTokenExchange exchange = (controller, token, node) -> {
            calls.add(controller.host());
            throw sre(Status.UNAUTHENTICATED); // e.g. a bad / expired join token
        };
        var mgr = new BootstrapManager(List.of(ep("a"), ep("b"), ep("c")), UNUSED, exchange);

        var ex = assertThrows(StatusRuntimeException.class, () -> mgr.bootstrap("token", "node-1", fast(3)));

        assertEquals(Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
        assertEquals(List.of("a"), calls, "a permanent rejection neither tries other controllers nor retries");
    }

    @Test
    void retriesTheWholeSweepWhenEveryControllerIsTransientlyDown() throws Exception {
        int[] attempts = {0};
        BootstrapManager.JoinTokenExchange exchange = (controller, token, node) -> {
            attempts[0]++;
            // First full sweep (2 controllers) fails transiently; afterwards the next attempt succeeds.
            if (attempts[0] <= 2) {
                throw sre(Status.UNAVAILABLE);
            }
        };
        var mgr = new BootstrapManager(List.of(ep("a"), ep("b")), UNUSED, exchange);

        mgr.bootstrap("token", "node-1", fast(3));

        assertTrue(attempts[0] >= 3, "the sweep is retried after an all-transient pass");
    }

    @Test
    void throwsAfterExhaustingRetriesWhenNoControllerEverAnswers() {
        BootstrapManager.JoinTokenExchange exchange = (controller, token, node) -> {
            throw sre(Status.UNAVAILABLE);
        };
        var mgr = new BootstrapManager(List.of(ep("a"), ep("b")), UNUSED, exchange);

        assertThrows(Exception.class, () -> mgr.bootstrap("token", "node-1", fast(2)));
    }

    @Test
    void requiresAtLeastOneController() {
        assertThrows(IllegalArgumentException.class, () -> new BootstrapManager(List.<ControllerEndpoint>of(), UNUSED));
    }
}
