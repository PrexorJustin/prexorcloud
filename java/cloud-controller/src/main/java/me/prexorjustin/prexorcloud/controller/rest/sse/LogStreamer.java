package me.prexorjustin.prexorcloud.controller.rest.sse;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.auth.Role;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.LogFilter;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.LogRecord;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.RoutesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSE endpoint streaming controller log records from a {@link ControllerLogBuffer}
 * to authenticated operator clients (`prexorctl logs controller --follow`,
 * dashboard live-tail). Connects on
 * {@code GET /api/v1/system/logs/stream?ticket=...&level=...&logger=...&tail=...}.
 *
 * <p>
 * The ticket holder must carry {@link Permission#SYSTEM_LOGS_VIEW}; any other
 * role is rejected at handshake time. Tickets are issued by
 * {@code POST /api/v1/system/logs/ticket} (in {@code SystemRoutes}).
 * </p>
 */
public final class LogStreamer {

    private static final Logger logger = LoggerFactory.getLogger(LogStreamer.class);
    private static final int MAX_TAIL = 1000;

    private final SseTicketManager ticketManager;
    private final ControllerLogBuffer buffer;
    private final ObjectMapper objectMapper;

    public LogStreamer(SseTicketManager ticketManager, ControllerLogBuffer buffer, ObjectMapper objectMapper) {
        this.ticketManager = ticketManager;
        this.buffer = buffer;
        this.objectMapper = objectMapper;
    }

    public void register(RoutesConfig routes) {
        routes.sse("/api/v1/system/logs/stream", client -> {
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

            String levelParam = client.ctx().queryParam("level");
            String loggerParam = client.ctx().queryParam("logger");
            int tail = parseTail(client.ctx().queryParam("tail"));
            LogFilter filter = LogFilter.atLeast(levelParam == null ? "INFO" : levelParam, loggerParam);

            // Flush headers immediately so the client transitions out of "connecting".
            client.sendEvent("connected", "{\"username\":\"" + holder.username() + "\"}");

            // Replay history.
            if (tail > 0) {
                List<LogRecord> history = buffer.recent(filter, tail);
                for (LogRecord record : history) {
                    if (!sendRecord(client, record)) return;
                }
            }

            ControllerLogBuffer.Subscription subscription = buffer.subscribe(record -> {
                if (!filter.matches(record)) return;
                if (!sendRecord(client, record)) {
                    client.close();
                }
            });
            client.keepAlive();
            client.onClose(subscription::close);

            logger.debug(
                    "Log SSE client connected: user={} level={} logger={}", holder.username(), levelParam, loggerParam);
        });
    }

    private boolean sendRecord(io.javalin.http.sse.SseClient client, LogRecord record) {
        try {
            client.sendEvent("log", objectMapper.writeValueAsString(toWire(record)));
            return true;
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize log record: {}", e.getMessage());
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

    /**
     * Wire format used by both REST and SSE so `prexorctl logs` parses one shape
     * regardless of how it received the record.
     */
    public static Map<String, Object> toWire(LogRecord record) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("seq", record.sequence());
        map.put("ts", record.timestampMs());
        map.put("level", record.level());
        map.put("logger", record.logger());
        map.put("thread", record.thread());
        map.put("message", record.message());
        if (record.throwable() != null) map.put("throwable", record.throwable());
        if (!record.mdc().isEmpty()) map.put("mdc", record.mdc());
        return map;
    }
}
