package me.prexorjustin.prexorcloud.controller.rest.sse;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * Tickets are single-use and expire after 30 seconds by default. They live in
 * leader memory: SSE streams are served by the single leader, so a leader-local
 * ticket is sufficient — a client whose connection moves after a failover simply
 * requests a fresh ticket.
 * </p>
 */
public final class SseTicketManager {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    private static final int TICKET_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

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

    public SseTicketManager() {
        this(Clock.systemUTC(), DEFAULT_TTL);
    }

    SseTicketManager(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
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
        tickets.put(ticket, new TicketEntry(username, role, clock.instant().plus(ttl)));
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
        TicketEntry entry = tickets.remove(ticket);
        if (entry == null || entry.isExpired(clock)) return null;
        return new TicketHolder(entry.username(), entry.role());
    }

    void importTicket(String ticket, String username, String role, Instant expiresAt) {
        tickets.put(ticket, new TicketEntry(username, role, expiresAt));
    }

    private void cleanup() {
        tickets.entrySet().removeIf(e -> e.getValue().isExpired(clock));
    }
}
