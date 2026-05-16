package me.prexorjustin.prexorcloud.controller.rest.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InstanceRoutes — console history parsers")
class InstanceRoutesTest {

    @Test
    @DisplayName("parseInstantQuery returns null for blank input")
    void parseInstantQueryBlank() {
        assertNull(InstanceRoutes.parseInstantQuery(null));
        assertNull(InstanceRoutes.parseInstantQuery(""));
        assertNull(InstanceRoutes.parseInstantQuery("   "));
    }

    @Test
    @DisplayName("parseInstantQuery accepts ISO-8601 instants")
    void parseInstantQueryAcceptsIso() {
        assertEquals(Instant.parse("2026-05-11T12:34:56Z"), InstanceRoutes.parseInstantQuery("2026-05-11T12:34:56Z"));
    }

    @Test
    @DisplayName("parseInstantQuery rejects malformed input")
    void parseInstantQueryRejectsMalformed() {
        assertThrows(DateTimeParseException.class, () -> InstanceRoutes.parseInstantQuery("yesterday"));
    }

    @Test
    @DisplayName("parseHistoryLimit defaults blank/non-numeric to 1000")
    void parseHistoryLimitDefaults() {
        assertEquals(1000, InstanceRoutes.parseHistoryLimit(null));
        assertEquals(1000, InstanceRoutes.parseHistoryLimit(""));
        assertEquals(1000, InstanceRoutes.parseHistoryLimit("not-a-number"));
        assertEquals(1000, InstanceRoutes.parseHistoryLimit("0"));
        assertEquals(1000, InstanceRoutes.parseHistoryLimit("-5"));
    }

    @Test
    @DisplayName("parseHistoryLimit caps at 10000")
    void parseHistoryLimitCaps() {
        assertEquals(10000, InstanceRoutes.parseHistoryLimit("99999"));
        assertEquals(10000, InstanceRoutes.parseHistoryLimit("10000"));
    }

    @Test
    @DisplayName("parseHistoryLimit passes valid values through")
    void parseHistoryLimitValid() {
        assertEquals(500, InstanceRoutes.parseHistoryLimit("500"));
        assertEquals(1, InstanceRoutes.parseHistoryLimit("1"));
    }
}
