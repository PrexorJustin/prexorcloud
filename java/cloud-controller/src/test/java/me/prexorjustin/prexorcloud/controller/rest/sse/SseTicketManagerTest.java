package me.prexorjustin.prexorcloud.controller.rest.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class SseTicketManagerTest {

    @Test
    void issuedTicketCanBeConsumedExactlyOnce() {
        var manager = new SseTicketManager(Clock.systemUTC(), Duration.ofSeconds(30));

        String ticket = manager.issue("admin", "OWNER");

        assertNotNull(ticket);
        assertEquals("admin", manager.validate(ticket));
        assertNull(manager.validate(ticket));
    }

    @Test
    void expiredTicketIsRejected() {
        Instant issuedAt = Instant.parse("2026-04-14T00:00:00Z");
        var clock = Clock.fixed(issuedAt, ZoneOffset.UTC);
        var manager = new SseTicketManager(clock, Duration.ofSeconds(30));
        String ticket = manager.issue("admin", "OWNER");

        var expiredManager =
                new SseTicketManager(Clock.fixed(issuedAt.plusSeconds(31), ZoneOffset.UTC), Duration.ofSeconds(30));
        expiredManager.importTicket(ticket, "admin", "OWNER", issuedAt.plusSeconds(30));

        assertNull(expiredManager.validate(ticket));
    }
}
