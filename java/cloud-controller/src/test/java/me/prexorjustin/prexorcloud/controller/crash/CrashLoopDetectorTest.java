package me.prexorjustin.prexorcloud.controller.crash;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;

import me.prexorjustin.prexorcloud.api.event.events.GroupCrashLoopEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CrashLoopDetector")
class CrashLoopDetectorTest {

    private EventBus eventBus;
    private CrashLoopDetector detector;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        // threshold=3, window=60s
        detector = new CrashLoopDetector(3, 60, eventBus);
    }

    @Nested
    @DisplayName("Threshold detection")
    class ThresholdDetection {

        @Test
        @DisplayName("Group is not paused before reaching threshold")
        void belowThreshold() {
            detector.recordCrash("lobby");
            detector.recordCrash("lobby");

            assertFalse(detector.isCrashLoopPaused("lobby"));
        }

        @Test
        @DisplayName("Group is paused after reaching threshold")
        void atThreshold() {
            detector.recordCrash("lobby");
            detector.recordCrash("lobby");
            detector.recordCrash("lobby");

            assertTrue(detector.isCrashLoopPaused("lobby"));
        }

        @Test
        @DisplayName("Exceeding threshold keeps group paused")
        void aboveThreshold() {
            for (int i = 0; i < 5; i++) {
                detector.recordCrash("lobby");
            }
            assertTrue(detector.isCrashLoopPaused("lobby"));
        }

        @Test
        @DisplayName("Different groups are tracked independently")
        void independentGroups() {
            detector.recordCrash("lobby");
            detector.recordCrash("lobby");
            detector.recordCrash("lobby");

            detector.recordCrash("bedwars");

            assertTrue(detector.isCrashLoopPaused("lobby"));
            assertFalse(detector.isCrashLoopPaused("bedwars"));
        }

        @Test
        @DisplayName("Unrecorded group is not paused")
        void unknownGroup() {
            assertFalse(detector.isCrashLoopPaused("nonexistent"));
        }
    }

    @Nested
    @DisplayName("Event publishing")
    class EventPublishing {

        @Test
        @DisplayName("Publishes GroupCrashLoopEvent when threshold is reached")
        void publishesEvent() throws InterruptedException {
            var captured = new AtomicReference<GroupCrashLoopEvent>();
            eventBus.subscribe(GroupCrashLoopEvent.class, captured::set);

            detector.recordCrash("lobby");
            detector.recordCrash("lobby");
            detector.recordCrash("lobby");

            // Event is dispatched on a virtual thread; give it a moment
            Thread.sleep(100);

            assertNotNull(captured.get());
            assertEquals("lobby", captured.get().group());
            assertEquals(3, captured.get().crashCount());
        }

        @Test
        @DisplayName("Does not publish duplicate events for already-paused group")
        void noDuplicateEvents() throws InterruptedException {
            var count = new java.util.concurrent.atomic.AtomicInteger(0);
            eventBus.subscribe(GroupCrashLoopEvent.class, _ -> count.incrementAndGet());

            for (int i = 0; i < 6; i++) {
                detector.recordCrash("lobby");
            }

            Thread.sleep(100);
            assertEquals(1, count.get());
        }
    }

    @Nested
    @DisplayName("Unpause")
    class Unpause {

        @Test
        @DisplayName("Unpause clears the crash loop state")
        void unpauseClearsState() {
            detector.recordCrash("lobby");
            detector.recordCrash("lobby");
            detector.recordCrash("lobby");
            assertTrue(detector.isCrashLoopPaused("lobby"));

            detector.unpause("lobby");
            assertFalse(detector.isCrashLoopPaused("lobby"));
        }

        @Test
        @DisplayName("Unpause resets crash history so threshold must be reached again")
        void unpauseResetsHistory() {
            detector.recordCrash("lobby");
            detector.recordCrash("lobby");
            detector.recordCrash("lobby");

            detector.unpause("lobby");

            // One more crash should not re-trigger
            detector.recordCrash("lobby");
            assertFalse(detector.isCrashLoopPaused("lobby"));
        }

        @Test
        @DisplayName("Unpause on non-paused group is a no-op")
        void unpauseNonPaused() {
            assertDoesNotThrow(() -> detector.unpause("nonexistent"));
        }
    }
}
