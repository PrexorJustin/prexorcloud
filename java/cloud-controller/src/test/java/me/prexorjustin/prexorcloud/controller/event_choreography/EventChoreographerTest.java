package me.prexorjustin.prexorcloud.controller.event_choreography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import me.prexorjustin.prexorcloud.api.domain.EventChoreography;
import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.events.ChoreographyOverlayActivatedEvent;
import me.prexorjustin.prexorcloud.api.event.events.ChoreographyOverlayDeactivatedEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;

import org.junit.jupiter.api.Test;

class EventChoreographerTest {

    private static GroupConfig group(String name, int min, int max, String mode, boolean maintenance) {
        return new GroupConfig(
                name, // name
                null, // parent
                "PAPER",
                "1.21.4",
                "server.jar",
                List.of(),
                mode,
                min,
                max,
                100, // maxPlayers
                0.8, // scaleUpThreshold
                300, // scaleDownAfterSeconds
                60, // scaleCooldownSeconds
                false, // predictiveScaling
                0.0, // scaleUpMargin
                0, // burstCeiling
                "LOWEST_PLAYERS", // routing
                30000, // portRangeStart
                30100, // portRangeEnd
                120, // startupTimeoutSeconds
                30, // shutdownGraceSeconds
                false, // drainOnShutdown
                0, // maxLifetimeSeconds
                false, // isStatic
                List.of(), // staticInstanceNames
                List.of(), // protectedPaths
                null, // fallbackGroup
                false, // defaultGroup
                List.of(), // dependsOn
                0, // startupWeight
                maintenance, // maintenance
                "", // maintenanceMessage
                List.of(), // maintenanceBypass
                "ROLLING", // updateStrategy
                List.of(), // nodeAffinity
                List.of(), // nodeAntiAffinity
                "", // spreadConstraint
                0, // priority
                1024, // memoryMb
                0.0, // cpuReservation
                0L, // diskReservationMb
                List.of(), // jvmArgs
                Map.of(), // env
                List.of(), // motds
                "STATIC", // motdMode
                30, // motdIntervalSeconds
                List.of(), // attachedModules
                List.of(), // enabledModules
                List.of(), // disabledModules
                List.of(), // attachedExtensions
                List.of(), // enabledExtensions
                List.of(), // disabledExtensions
                Map.of(), // configPatches
                "", // bedrockProxyGroup
                0); // warmPoolMinPrepared
    }

    private static EventChoreography overlay(
            String name, String groupName, String cron, long durationSeconds, EventChoreography.EventOverlay overlay) {
        return new EventChoreography(name, "", groupName, cron, "UTC", durationSeconds, overlay);
    }

    @Test
    void overlayInsideWindowReplacesScalingFields() {
        var event = overlay(
                "friday_peak",
                "lobby",
                "0 18 * * 5",
                7200,
                new EventChoreography.EventOverlay(20, 50, "DYNAMIC", null, null));
        var choreographer = new EventChoreographer(List.of(event), null);
        // 2026-05-01 is a Friday
        Instant inside =
                ZonedDateTime.of(2026, 5, 1, 18, 30, 0, 0, ZoneOffset.UTC).toInstant();
        var result = choreographer.apply(group("lobby", 2, 10, "DYNAMIC", false), inside);
        assertEquals(20, result.minInstances());
        assertEquals(50, result.maxInstances());
    }

    @Test
    void overlayOutsideWindowLeavesGroupUntouched() {
        var event = overlay(
                "friday_peak",
                "lobby",
                "0 18 * * 5",
                7200,
                new EventChoreography.EventOverlay(20, 50, null, null, null));
        var choreographer = new EventChoreographer(List.of(event), null);
        Instant outside =
                ZonedDateTime.of(2026, 5, 1, 21, 1, 0, 0, ZoneOffset.UTC).toInstant();
        var source = group("lobby", 2, 10, "DYNAMIC", false);
        assertSame(source, choreographer.apply(source, outside));
    }

    @Test
    void overlayDoesNotApplyToOtherGroups() {
        var event = overlay(
                "lobby_peak",
                "lobby",
                "0 18 * * 5",
                3600,
                new EventChoreography.EventOverlay(20, null, null, null, null));
        var choreographer = new EventChoreographer(List.of(event), null);
        Instant inside =
                ZonedDateTime.of(2026, 5, 1, 18, 30, 0, 0, ZoneOffset.UTC).toInstant();
        var source = group("survival", 2, 10, "DYNAMIC", false);
        assertSame(source, choreographer.apply(source, inside));
    }

    @Test
    void maintenanceOverlayFlipsTheFlag() {
        var event = overlay(
                "monday_maintenance",
                "lobby",
                "0 4 * * 1",
                1800,
                new EventChoreography.EventOverlay(null, null, null, true, "Weekly maintenance"));
        var choreographer = new EventChoreographer(List.of(event), null);
        // 2026-05-04 is a Monday
        Instant inside =
                ZonedDateTime.of(2026, 5, 4, 4, 5, 0, 0, ZoneOffset.UTC).toInstant();
        var result = choreographer.apply(group("lobby", 2, 10, "DYNAMIC", false), inside);
        assertTrue(result.maintenance());
        assertEquals("Weekly maintenance", result.maintenanceMessage());
    }

    @Test
    void invalidCronIsRejectedAtConstruction() {
        var event = new EventChoreography(
                "bad",
                "",
                "lobby",
                "*/0 * * * *",
                "UTC",
                60,
                new EventChoreography.EventOverlay(1, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new EventChoreographer(List.of(event), null));
    }

    @Test
    void duplicateNamesRejected() {
        var a = overlay(
                "dup", "lobby", "0 18 * * *", 60, new EventChoreography.EventOverlay(1, null, null, null, null));
        var b = overlay(
                "dup", "lobby", "0 19 * * *", 60, new EventChoreography.EventOverlay(2, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new EventChoreographer(List.of(a, b), null));
    }

    @Test
    void emptyOverlayRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> new EventChoreography.EventOverlay(null, null, null, null, null));
    }

    @Test
    void refreshEmitsActivationAndDeactivation() {
        var captured = new CopyOnWriteArrayList<CloudEvent>();
        var bus = new EventBus();
        bus.subscribeAll(captured::add);
        var event = overlay(
                "friday_peak",
                "lobby",
                "0 18 * * 5",
                1800,
                new EventChoreography.EventOverlay(10, null, null, null, null));
        var choreographer = new EventChoreographer(List.of(event), bus);

        Instant before =
                ZonedDateTime.of(2026, 5, 1, 17, 59, 0, 0, ZoneOffset.UTC).toInstant();
        choreographer.refresh(before);
        Instant inside =
                ZonedDateTime.of(2026, 5, 1, 18, 1, 0, 0, ZoneOffset.UTC).toInstant();
        choreographer.refresh(inside);
        Instant after =
                ZonedDateTime.of(2026, 5, 1, 18, 31, 0, 0, ZoneOffset.UTC).toInstant();
        choreographer.refresh(after);

        bus.shutdown();
        // Wait briefly for virtual-thread fanout
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline
                && captured.stream().noneMatch(c -> c instanceof ChoreographyOverlayDeactivatedEvent)) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        var activated = captured.stream()
                .filter(c -> c instanceof ChoreographyOverlayActivatedEvent)
                .map(c -> (ChoreographyOverlayActivatedEvent) c)
                .findFirst()
                .orElse(null);
        assertNotNull(activated);
        assertEquals("friday_peak", activated.eventName());
        assertEquals("lobby", activated.group());

        assertTrue(captured.stream().anyMatch(c -> c instanceof ChoreographyOverlayDeactivatedEvent));
    }

    @Test
    void activeForReturnsEmptyOutsideWindow() {
        var event = overlay(
                "lobby_peak", "lobby", "0 18 * * 5", 60, new EventChoreography.EventOverlay(5, null, null, null, null));
        var choreographer = new EventChoreographer(List.of(event), null);
        Instant outside =
                ZonedDateTime.of(2026, 5, 1, 19, 0, 0, 0, ZoneOffset.UTC).toInstant();
        assertFalse(choreographer.activeFor("lobby", outside).isPresent());
    }

    @Test
    void invalidTimezoneIsRejected() {
        var event = new EventChoreography(
                "bad_tz",
                "",
                "lobby",
                "0 18 * * *",
                "Not/A_Real_Zone",
                60,
                new EventChoreography.EventOverlay(1, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new EventChoreographer(List.of(event), null));
    }
}
