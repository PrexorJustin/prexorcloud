package me.prexorjustin.prexorcloud.modules.backup.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BackupSchedule")
class BackupScheduleTest {

    @Test
    @DisplayName("disabled when no interval, even with targets")
    void disabledWithoutInterval() {
        BackupSchedule schedule = BackupSchedule.parse(null, null, "node-1/lobby/inst-1");
        assertFalse(schedule.enabled());
        assertTrue(schedule.targets().isEmpty());
    }

    @Test
    @DisplayName("disabled when interval set but no valid targets")
    void disabledWithoutTargets() {
        assertFalse(BackupSchedule.parse("60", "1", null).enabled());
        assertFalse(BackupSchedule.parse("60", "1", "  ").enabled());
        assertFalse(BackupSchedule.parse("60", "1", "garbage-without-slashes").enabled());
    }

    @Test
    @DisplayName("enabled with positive interval and at least one target")
    void enabledWithBoth() {
        BackupSchedule schedule = BackupSchedule.parse("30", "5", "node-1/lobby/inst-1");
        assertTrue(schedule.enabled());
        assertEquals(Duration.ofMinutes(30), schedule.period());
        assertEquals(Duration.ofMinutes(5), schedule.initialDelay());
        assertEquals(List.of(new BackupSchedule.Target("node-1", "lobby", "inst-1")), schedule.targets());
    }

    @Test
    @DisplayName("parses multiple targets and skips malformed tokens")
    void parsesAndSkipsMalformed() {
        BackupSchedule schedule = BackupSchedule.parse(
                "15",
                null,
                "node-1/lobby/inst-1, node-2/survival/inst-2 ,nope, node-3//inst-3, /lobby/x, node-4/lobby/");
        assertTrue(schedule.enabled());
        assertEquals(
                List.of(
                        new BackupSchedule.Target("node-1", "lobby", "inst-1"),
                        new BackupSchedule.Target("node-2", "survival", "inst-2"),
                        new BackupSchedule.Target("node-3", "", "inst-3")),
                schedule.targets());
        // initial delay defaults to 1 minute when unset
        assertEquals(Duration.ofMinutes(1), schedule.initialDelay());
    }

    @Test
    @DisplayName("non-numeric interval falls back to disabled; non-numeric delay falls back to default")
    void numericFallbacks() {
        assertFalse(BackupSchedule.parse("soon", "1", "node-1/lobby/inst-1").enabled());
        BackupSchedule schedule = BackupSchedule.parse("10", "later", "node-1/lobby/inst-1");
        assertTrue(schedule.enabled());
        assertEquals(Duration.ofMinutes(1), schedule.initialDelay());
    }

    @Test
    @DisplayName("negative initial delay clamps to zero")
    void negativeInitialDelayClamps() {
        BackupSchedule schedule = BackupSchedule.parse("10", "-5", "node-1/lobby/inst-1");
        assertEquals(Duration.ZERO, schedule.initialDelay());
    }
}
