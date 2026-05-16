package me.prexorjustin.prexorcloud.controller.rest.sse;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Manages short-lived, single-use tickets for SSE authentication.
 *
 * <p>
 * Instead of passing the JWT token as a URL query parameter (which leaks into
 * browser history, proxy logs, and Referer headers), clients first obtain a
 * short-lived ticket via an authenticated POST endpoint, then use that ticket
 * to establish the SSE connection.
 * </p>
 *
 * <p>
 * Tickets are single-use and expire after 30 seconds by default.
 * </p>
 */
public final class SseTicketManager {

    private static final Duration DEFAULT_TTL = RedisKeys.sseTicketTtl();
    private static final int TICKET_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    record TicketEntry(String username, String role, Instant expiresAt) {

        boolean isExpired(Clock clock) {
            return clock.instant().isAfter(expiresAt);
        }
    }

    /** Public projection of a validated ticket. */
    public record TicketHolder(String username, String role) {}

    private final Clock clock;
    private final Duration ttl;
    private final Map<String, TicketEntry> tickets = new ConcurrentHashMap<>();
    private final RedisCommands<String, String> redisCommands;

    public SseTicketManager() {
        this(Clock.systemUTC(), DEFAULT_TTL, null);
    }

    public SseTicketManager(RedisCommands<String, String> redisCommands) {
        this(Clock.systemUTC(), DEFAULT_TTL, redisCommands);
    }

    SseTicketManager(Clock clock, Duration ttl) {
        this(clock, ttl, null);
    }

    SseTicketManager(Clock clock, Duration ttl, RedisCommands<String, String> redisCommands) {
        this.clock = clock;
        this.ttl = ttl;
        this.redisCommands = redisCommands;
    }

    /**
     * Issue a new single-use SSE ticket for the given user.
     *
     * @param username
     *            the authenticated user
     * @param role
     *            the user's role
     * @return an opaque ticket string
     */
    public String issue(String username, String role) {
        cleanup();
        byte[] bytes = new byte[TICKET_BYTES];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var entry = new TicketEntry(username, role, clock.instant().plus(ttl));
        if (redisCommands != null) {
            try {
                redisCommands.setex(
                        RedisKeys.sseTicket(ticket),
                        RedisKeys.sanitizedTtl(ttl).getSeconds(),
                        JSON.writeValueAsString(entry));
            } catch (Exception e) {
                throw new IllegalStateException("failed to issue Redis-backed SSE ticket", e);
            }
        } else {
            tickets.put(ticket, entry);
        }
        return ticket;
    }

    /**
     * Validate and consume a ticket. Returns the username if valid, or null if the
     * ticket is unknown, expired, or already consumed.
     */
    public String validate(String ticket) {
        TicketHolder holder = validateHolder(ticket);
        return holder == null ? null : holder.username();
    }

    /**
     * Validate and consume a ticket, returning both username and role for
     * permission-gated SSE streams. Returns {@code null} for unknown, expired,
     * or already-consumed tickets.
     */
    public TicketHolder validateHolder(String ticket) {
        if (ticket == null || ticket.isBlank()) return null;
        if (redisCommands != null) {
            return validateRedisHolder(ticket);
        }
        TicketEntry entry = tickets.remove(ticket);
        if (entry == null || entry.isExpired(clock)) return null;
        return new TicketHolder(entry.username(), entry.role());
    }

    void importTicket(String ticket, String username, String role, Instant expiresAt) {
        if (redisCommands != null) {
            try {
                long ttlSeconds = Math.max(
                        1L,
                        RedisKeys.sanitizedTtl(Duration.between(clock.instant(), expiresAt))
                                .getSeconds());
                redisCommands.setex(
                        RedisKeys.sseTicket(ticket),
                        ttlSeconds,
                        JSON.writeValueAsString(new TicketEntry(username, role, expiresAt)));
                return;
            } catch (Exception e) {
                throw new IllegalStateException("failed to import Redis-backed SSE ticket", e);
            }
        }
        tickets.put(ticket, new TicketEntry(username, role, expiresAt));
    }

    private void cleanup() {
        if (redisCommands != null) {
            return;
        }
        tickets.entrySet().removeIf(e -> e.getValue().isExpired(clock));
    }

    private TicketHolder validateRedisHolder(String ticket) {
        try {
            String raw = redisCommands.getdel(RedisKeys.sseTicket(ticket));
            if (raw == null || raw.isBlank()) return null;
            TicketEntry entry = JSON.readValue(raw, TicketEntry.class);
            if (entry.isExpired(clock)) return null;
            return new TicketHolder(entry.username(), entry.role());
        } catch (Exception _) {
            return null;
        }
    }
}
