package me.prexorjustin.prexorcloud.controller.rest.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import io.lettuce.core.api.sync.RedisCommands;
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

    @Test
    void redisBackedTicketCanBeConsumedExactlyOnce() {
        var manager = new SseTicketManager(redisTicketCommands());

        String ticket = manager.issue("admin", "OWNER");

        assertNotNull(ticket);
        assertEquals("admin", manager.validate(ticket));
        assertNull(manager.validate(ticket));
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> redisTicketCommands() {
        Map<String, String> values = new HashMap<>();
        return (RedisCommands<String, String>) Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[] {RedisCommands.class},
                (ignored, method, args) -> switch (method.getName()) {
                    case "setex" -> {
                        values.put((String) args[0], (String) args[2]);
                        yield "OK";
                    }
                    case "getdel" -> values.remove((String) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
