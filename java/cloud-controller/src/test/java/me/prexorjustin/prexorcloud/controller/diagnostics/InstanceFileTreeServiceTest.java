package me.prexorjustin.prexorcloud.controller.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import me.prexorjustin.prexorcloud.controller.grpc.PendingRequestRegistry;
import me.prexorjustin.prexorcloud.controller.scheduler.NodeMessageDispatcher;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.FileEntry;
import me.prexorjustin.prexorcloud.protocol.InstanceFileTree;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InstanceFileTreeServiceTest {

    private ScheduledExecutorService scheduler;
    private PendingRequestRegistry registry;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        registry = new PendingRequestRegistry(scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void dispatchFailureReturnsDaemonUnreachable() {
        NodeMessageDispatcher dispatcher = mock(NodeMessageDispatcher.class);
        when(dispatcher.dispatch(eq("node-a"), any(ControllerMessage.class))).thenReturn(false);

        var service = new InstanceFileTreeService(dispatcher, registry);
        InstanceFileTreeResult result = service.walkInstanceFiles("node-a", "lobby", "lobby-1");

        assertEquals("DAEMON_UNREACHABLE", result.error());
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void happyPathPropagatesEntriesAndTruncated() {
        NodeMessageDispatcher dispatcher = mock(NodeMessageDispatcher.class);

        // dispatcher fires the WalkInstanceFiles; capture request_id and complete the registry future
        ArgumentCaptor<ControllerMessage> sent = ArgumentCaptor.forClass(ControllerMessage.class);
        when(dispatcher.dispatch(eq("node-a"), sent.capture())).thenAnswer(invocation -> {
            String requestId = sent.getValue().getWalkInstanceFiles().getRequestId();
            // Complete asynchronously to simulate the daemon reply
            scheduler.execute(() -> registry.complete(
                    requestId,
                    InstanceFileTree.newBuilder()
                            .setRequestId(requestId)
                            .addEntries(FileEntry.newBuilder()
                                    .setPath("server.jar")
                                    .setSizeBytes(123L)
                                    .setIsDir(false)
                                    .setModifiedAtMs(0L))
                            .setTruncated(true)
                            .build()));
            return true;
        });

        var service = new InstanceFileTreeService(dispatcher, registry);
        InstanceFileTreeResult result = service.walkInstanceFiles("node-a", "lobby", "lobby-1");

        assertEquals("", result.error());
        assertEquals(1, result.entries().size());
        assertEquals("server.jar", result.entries().get(0).path());
        assertTrue(result.truncated());
    }

    @Test
    void unknownNodeReturnsNodeUnknown() {
        NodeMessageDispatcher dispatcher = mock(NodeMessageDispatcher.class);
        var service = new InstanceFileTreeService(dispatcher, registry);

        InstanceFileTreeResult result = service.walkInstanceFiles("", "lobby", "lobby-1");
        assertEquals("NODE_UNKNOWN", result.error());
    }
}
