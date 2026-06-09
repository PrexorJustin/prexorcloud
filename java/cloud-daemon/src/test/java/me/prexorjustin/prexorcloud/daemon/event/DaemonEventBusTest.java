package me.prexorjustin.prexorcloud.daemon.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicReference;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.EventSubscription;
import me.prexorjustin.prexorcloud.daemon.grpc.DaemonGrpcClient;
import me.prexorjustin.prexorcloud.protocol.DaemonMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DaemonEventBusTest {

    static final class TestEvent implements CloudEvent {
        public String name = "";

        @Override
        public String type() {
            return "test.TestEvent";
        }
    }

    @Test
    @DisplayName("first subscriber sends EventSubscribe")
    void firstSubscriberRegisters() {
        DaemonGrpcClient client = mock(DaemonGrpcClient.class);
        DaemonEventBus bus = new DaemonEventBus(client);

        EventSubscription sub = bus.subscribe(TestEvent.class, _ -> {});
        try {
            ArgumentCaptor<DaemonMessage> captor = ArgumentCaptor.forClass(DaemonMessage.class);
            verify(client, atLeastOnce()).sendMessage(captor.capture());
            var registered = captor.getAllValues().stream()
                    .filter(m -> m.getPayloadCase() == DaemonMessage.PayloadCase.EVENT_SUBSCRIBE)
                    .toList();
            assertTrue(!registered.isEmpty(), "expected at least one EventSubscribe");
            assertEquals(
                    java.util.List.of(TestEvent.class.getName()),
                    registered.getFirst().getEventSubscribe().getEventTypesList());
        } finally {
            sub.unsubscribe();
        }
    }

    @Test
    @DisplayName("last unsubscribe sends EventUnsubscribe")
    void lastUnsubscribeDeregisters() {
        DaemonGrpcClient client = mock(DaemonGrpcClient.class);
        DaemonEventBus bus = new DaemonEventBus(client);

        EventSubscription a = bus.subscribe(TestEvent.class, _ -> {});
        EventSubscription b = bus.subscribe(TestEvent.class, _ -> {});

        a.unsubscribe();
        verify(client, never()).sendMessage(argThatIsUnsubscribe());

        b.unsubscribe();
        verify(client, times(1)).sendMessage(argThatIsUnsubscribe());
    }

    @Test
    @DisplayName("publishFromController dispatches to local subscribers")
    void inboundEventsReachSubscribers() throws Exception {
        DaemonGrpcClient client = mock(DaemonGrpcClient.class);
        DaemonEventBus bus = new DaemonEventBus(client);
        AtomicReference<TestEvent> seen = new AtomicReference<>();
        EventSubscription sub = bus.subscribe(TestEvent.class, seen::set);
        try {
            byte[] payload = "{\"name\":\"hello\"}".getBytes();
            bus.publishFromController(TestEvent.class.getName(), payload);
            // Handlers run on virtual threads — poll briefly.
            for (int i = 0; i < 100 && seen.get() == null; i++) {
                Thread.sleep(5);
            }
            assertNotNull(seen.get());
            assertEquals("hello", seen.get().name);
        } finally {
            sub.unsubscribe();
        }
    }

    @Test
    @DisplayName("onReconnect re-sends EventSubscribe with the full subscribed set")
    void reconnectReregisters() {
        DaemonGrpcClient client = mock(DaemonGrpcClient.class);
        DaemonEventBus bus = new DaemonEventBus(client);
        EventSubscription a = bus.subscribe(TestEvent.class, _ -> {});
        try {
            // Reset mock interactions and trigger reconnect path.
            org.mockito.Mockito.reset(client);
            bus.onReconnect();
            ArgumentCaptor<DaemonMessage> captor = ArgumentCaptor.forClass(DaemonMessage.class);
            verify(client, times(1)).sendMessage(captor.capture());
            assertEquals(
                    DaemonMessage.PayloadCase.EVENT_SUBSCRIBE, captor.getValue().getPayloadCase());
            assertEquals(
                    java.util.List.of(TestEvent.class.getName()),
                    captor.getValue().getEventSubscribe().getEventTypesList());
        } finally {
            a.unsubscribe();
        }
    }

    private static DaemonMessage argThatIsUnsubscribe() {
        return org.mockito.ArgumentMatchers.argThat(
                m -> m != null && m.getPayloadCase() == DaemonMessage.PayloadCase.EVENT_UNSUBSCRIBE);
    }
}
