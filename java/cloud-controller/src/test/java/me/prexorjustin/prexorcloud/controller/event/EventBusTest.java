package me.prexorjustin.prexorcloud.controller.event;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.EventHandler;
import me.prexorjustin.prexorcloud.api.event.events.GroupCrashLoopEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("EventBus")
class EventBusTest {

    private EventBus eventBus;

    // Simple test events
    record TestEventA(String message) implements CloudEvent {

        @Override
        public String type() {
            return "TEST_A";
        }
    }

    record TestEventB(int value) implements CloudEvent {

        @Override
        public String type() {
            return "TEST_B";
        }
    }

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
    }

    @Nested
    @DisplayName("Handler dispatch")
    class HandlerDispatch {

        @Test
        @DisplayName("Subscribed handler receives published event")
        void handlerReceivesEvent() throws InterruptedException {
            var latch = new CountDownLatch(1);
            var captured = new AtomicReference<TestEventA>();

            eventBus.subscribe(TestEventA.class, event -> {
                captured.set(event);
                latch.countDown();
            });

            eventBus.publish(new TestEventA("hello"));
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals("hello", captured.get().message());
        }

        @Test
        @DisplayName("Handler is not called for different event type")
        void wrongEventType() throws InterruptedException {
            var called = new AtomicInteger(0);

            eventBus.subscribe(TestEventA.class, _ -> called.incrementAndGet());
            eventBus.publish(new TestEventB(42));

            Thread.sleep(200);
            assertEquals(0, called.get());
        }

        @Test
        @DisplayName("Publishing with no subscribers does not throw")
        void noSubscribers() {
            assertDoesNotThrow(() -> eventBus.publish(new TestEventA("ignored")));
        }
    }

    @Nested
    @DisplayName("Multiple handlers")
    class MultipleHandlers {

        @Test
        @DisplayName("Multiple handlers for same event type all receive the event")
        void allHandlersCalled() throws InterruptedException {
            var latch = new CountDownLatch(3);

            eventBus.subscribe(TestEventA.class, _ -> latch.countDown());
            eventBus.subscribe(TestEventA.class, _ -> latch.countDown());
            eventBus.subscribe(TestEventA.class, _ -> latch.countDown());

            eventBus.publish(new TestEventA("test"));
            assertTrue(latch.await(2, TimeUnit.SECONDS), "All 3 handlers should have been called");
        }

        @Test
        @DisplayName("Handlers for different event types are independent")
        void independentHandlers() throws InterruptedException {
            var latchA = new CountDownLatch(1);
            var latchB = new CountDownLatch(1);

            eventBus.subscribe(TestEventA.class, _ -> latchA.countDown());
            eventBus.subscribe(TestEventB.class, _ -> latchB.countDown());

            eventBus.publish(new TestEventA("a"));
            assertTrue(latchA.await(2, TimeUnit.SECONDS));
            assertEquals(1, latchB.getCount(), "TestEventB handler should not have been called");
        }
    }

    @Nested
    @DisplayName("Exception isolation")
    class ExceptionIsolation {

        @Test
        @DisplayName("Failing handler does not prevent other handlers from executing")
        void failingHandlerIsolated() throws InterruptedException {
            var latch = new CountDownLatch(1);
            var secondHandlerCalled = new AtomicReference<Boolean>(false);

            eventBus.subscribe(TestEventA.class, _ -> {
                throw new RuntimeException("Intentional test failure");
            });
            eventBus.subscribe(TestEventA.class, _ -> {
                secondHandlerCalled.set(true);
                latch.countDown();
            });

            eventBus.publish(new TestEventA("test"));
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertTrue(secondHandlerCalled.get(), "Second handler should run despite first handler's exception");
        }
    }

    @Nested
    @DisplayName("Unsubscribe")
    class Unsubscribe {

        @Test
        @DisplayName("Unsubscribed handler is no longer called")
        void unsubscribedHandlerNotCalled() throws InterruptedException {
            var count = new AtomicInteger(0);
            EventHandler<TestEventA> handler = _ -> count.incrementAndGet();

            eventBus.subscribe(TestEventA.class, handler);
            eventBus.publish(new TestEventA("first"));
            Thread.sleep(200);
            assertEquals(1, count.get());

            eventBus.unsubscribe(TestEventA.class, handler);
            eventBus.publish(new TestEventA("second"));
            Thread.sleep(200);
            assertEquals(1, count.get(), "Handler should not be called after unsubscribe");
        }

        @Test
        @DisplayName("Unsubscribe on non-existent event type does not throw")
        void unsubscribeNonExistent() {
            EventHandler<TestEventA> handler = _ -> {};
            assertDoesNotThrow(() -> eventBus.unsubscribe(TestEventA.class, handler));
        }
    }

    @Nested
    @DisplayName("Real event types")
    class RealEventTypes {

        @Test
        @DisplayName("GroupCrashLoopEvent dispatches correctly")
        void crashLoopEventDispatches() throws InterruptedException {
            var latch = new CountDownLatch(1);
            var captured = new AtomicReference<GroupCrashLoopEvent>();

            eventBus.subscribe(GroupCrashLoopEvent.class, event -> {
                captured.set(event);
                latch.countDown();
            });

            var event = new GroupCrashLoopEvent("lobby", 5, Instant.now());
            eventBus.publish(event);

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals("lobby", captured.get().group());
            assertEquals(5, captured.get().crashCount());
        }
    }
}
