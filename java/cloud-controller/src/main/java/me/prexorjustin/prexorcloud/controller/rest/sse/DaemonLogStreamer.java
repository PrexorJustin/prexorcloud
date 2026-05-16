package me.prexorjustin.prexorcloud.controller.rest.sse;

import java.util.List;

import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.auth.Role;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.LogFilter;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.LogRecord;
import me.prexorjustin.prexorcloud.controller.observability.DaemonLogStore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.RoutesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSE endpoint that mirrors {@link LogStreamer} for daemon-scoped logs. Connects
 * on {@code GET /api/v1/nodes/{id}/logs/stream?ticket=...&level=...&logger=...&tail=...}
 * and tails the per-node ring buffer maintained by {@link DaemonLogStore}.
 *
 * <p>
 * Tickets are issued at {@code POST /api/v1/nodes/{id}/logs/ticket} (see
 * {@code DaemonLogRoutes}); ticket holders need {@link Permission#SYSTEM_LOGS_VIEW}.
 * </p>
 */
public final class DaemonLogStreamer {

    private static final Logger logger = LoggerFactory.getLogger(DaemonLogStreamer.class);
    private static final int MAX_TAIL = 1000;

    private final SseTicketManager ticketManager;
    private final DaemonLogStore store;
    private final ObjectMapper objectMapper;

    public DaemonLogStreamer(SseTicketManager ticketManager, DaemonLogStore store, ObjectMapper objectMapper) {
        this.ticketManager = ticketManager;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    public void register(RoutesConfig routes) {
        routes.sse("/api/v1/nodes/{id}/logs/stream", client -> {
            String ticket = client.ctx().queryParam("ticket");
            var holder = ticketManager.validateHolder(ticket);
            if (holder == null) {
                client.sendEvent("error", "{\"message\":\"Unauthorized\"}");
                client.close();
                return;
            }
            if (!Role.hasPermission(holder.role(), Permission.SYSTEM_LOGS_VIEW)) {
                client.sendEvent("error", "{\"message\":\"Forbidden\"}");
                client.close();
                return;
            }
            String nodeId = client.ctx().pathParam("id");

            String levelParam = client.ctx().queryParam("level");
            String loggerParam = client.ctx().queryParam("logger");
            int tail = parseTail(client.ctx().queryParam("tail"));
            LogFilter filter = LogFilter.atLeast(levelParam == null ? "INFO" : levelParam, loggerParam);

            client.sendEvent("connected", "{\"username\":\"" + holder.username() + "\",\"nodeId\":\"" + nodeId + "\"}");

            if (tail > 0) {
                List<LogRecord> history = store.recent(nodeId, filter, tail);
                for (LogRecord record : history) {
                    if (!sendRecord(client, record)) return;
                }
            }

            ControllerLogBuffer.Subscription subscription = store.subscribe(nodeId, record -> {
                if (!filter.matches(record)) return;
                if (!sendRecord(client, record)) {
                    client.close();
                }
            });
            client.keepAlive();
            client.onClose(subscription::close);

            logger.debug(
                    "Daemon log SSE client connected: user={} node={} level={} logger={}",
                    holder.username(),
                    nodeId,
                    levelParam,
                    loggerParam);
        });
    }

    private boolean sendRecord(io.javalin.http.sse.SseClient client, LogRecord record) {
        try {
            client.sendEvent("log", objectMapper.writeValueAsString(LogStreamer.toWire(record)));
            return true;
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize daemon log record: {}", e.getMessage());
            return true;
        } catch (Exception _) {
            return false;
        }
    }

    private static int parseTail(String value) {
        if (value == null || value.isBlank()) return 200;
        try {
            int n = Integer.parseInt(value);
            if (n < 0) return 0;
            return Math.min(n, MAX_TAIL);
        } catch (NumberFormatException _) {
            return 200;
        }
    }
}
