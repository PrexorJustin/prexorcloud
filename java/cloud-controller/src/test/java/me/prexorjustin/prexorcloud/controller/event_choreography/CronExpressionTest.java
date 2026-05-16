package me.prexorjustin.prexorcloud.controller.event_choreography;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

class CronExpressionTest {

    private static ZonedDateTime utc(int y, int mo, int d, int h, int m) {
        return ZonedDateTime.of(y, mo, d, h, m, 0, 0, ZoneOffset.UTC);
    }

    @Test
    void wildcardEveryMinuteAlwaysMatches() {
        var cron = CronExpression.parse("* * * * *");
        assertTrue(cron.matches(utc(2026, 5, 3, 0, 0)));
        assertTrue(cron.matches(utc(2026, 5, 3, 23, 59)));
    }

    @Test
    void singleValueFieldMatches() {
        var cron = CronExpression.parse("0 18 * * *");
        assertTrue(cron.matches(utc(2026, 5, 3, 18, 0)));
        assertFalse(cron.matches(utc(2026, 5, 3, 18, 1)));
        assertFalse(cron.matches(utc(2026, 5, 3, 17, 0)));
    }

    @Test
    void rangeAndStepInMinuteField() {
        // every 15 min between 0 and 45 inclusive
        var cron = CronExpression.parse("0-45/15 * * * *");
        assertTrue(cron.matches(utc(2026, 5, 3, 12, 0)));
        assertTrue(cron.matches(utc(2026, 5, 3, 12, 15)));
        assertTrue(cron.matches(utc(2026, 5, 3, 12, 30)));
        assertTrue(cron.matches(utc(2026, 5, 3, 12, 45)));
        assertFalse(cron.matches(utc(2026, 5, 3, 12, 46)));
        assertFalse(cron.matches(utc(2026, 5, 3, 12, 1)));
    }

    @Test
    void listValuesInDayOfWeek() {
        // Friday and Saturday at 19:00
        var cron = CronExpression.parse("0 19 * * 5,6");
        // 2026-05-01 is a Friday
        assertTrue(cron.matches(utc(2026, 5, 1, 19, 0)));
        assertTrue(cron.matches(utc(2026, 5, 2, 19, 0))); // Sat
        assertFalse(cron.matches(utc(2026, 5, 3, 19, 0))); // Sun
        assertFalse(cron.matches(utc(2026, 5, 4, 19, 0))); // Mon
    }

    @Test
    void sundayAcceptsBothZeroAndSeven() {
        var sunZero = CronExpression.parse("0 12 * * 0");
        var sunSeven = CronExpression.parse("0 12 * * 7");
        // 2026-05-03 is a Sunday
        assertTrue(sunZero.matches(utc(2026, 5, 3, 12, 0)));
        assertTrue(sunSeven.matches(utc(2026, 5, 3, 12, 0)));
    }

    @Test
    void domAndDowOrSemanticsWhenBothRestricted() {
        // 13th of any month OR every Friday at 03:00
        var cron = CronExpression.parse("0 3 13 * 5");
        assertTrue(cron.matches(utc(2026, 5, 13, 3, 0))); // 13th, Wed → matches via DOM
        assertTrue(cron.matches(utc(2026, 5, 1, 3, 0))); // 1st, Fri → matches via DOW
        assertFalse(cron.matches(utc(2026, 5, 14, 3, 0))); // 14th, Thu → neither
    }

    @Test
    void rejectsTooFewFields() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* * *"));
    }

    @Test
    void rejectsOutOfRangeMinute() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("60 * * * *"));
    }

    @Test
    void rejectsZeroStep() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("*/0 * * * *"));
    }
}
