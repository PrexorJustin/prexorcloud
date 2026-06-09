package me.prexorjustin.prexorcloud.controller.rest.sse;

import me.prexorjustin.prexorcloud.api.event.EventSubscription;
import me.prexorjustin.prexorcloud.api.event.events.InstanceConsoleOutputEvent;
import me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer;
import me.prexorjustin.prexorcloud.controller.event.EventBus;

import io.javalin.config.RoutesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSE endpoint for per-instance console output streaming. Clients connect to
 * GET /api/v1/services/{id}/console?ticket=... and receive console lines as
 * plain-text SSE message events.
 *
 * <p>
 * Each connected client subscribes to {@link InstanceConsoleOutputEvent}
 * filtered to their requested instance ID. The subscription is removed
 * automatically when the client disconnects.
 */
public final class ConsoleStreamer {

    private static final Logger logger = LoggerFactory.getLogger(ConsoleStreamer.class);

    private final EventBus eventBus;
    private final SseTicketManager ticketManager;
    private final ConsoleBuffer consoleBuffer;

    public ConsoleStreamer(EventBus eventBus, SseTicketManager ticketManager, ConsoleBuffer consoleBuffer) {
        this.eventBus = eventBus;
        this.ticketManager = ticketManager;
        this.consoleBuffer = consoleBuffer;
    }

    public void register(RoutesConfig routes) {
        routes.sse("/api/v1/services/{id}/console", client -> {
            // Ticket-based auth only. The CLI/dashboard exchange a JWT for a
            // short-lived single-use ticket before opening the stream.
            String ticket = client.ctx().queryParam("ticket");
            if (ticketManager.validate(ticket) == null) {
                client.sendEvent("error", "{\"message\":\"Unauthorized\"}");
                client.close();
                return;
            }

            String instanceId = client.ctx().pathParam("id");

            // Flush HTTP headers immediately so EventSource.onopen fires in the browser.
            // Without an initial event, Jetty buffers the response until the first data
            // chunk, leaving the client stuck in "connecting" state indefinitely.
            client.sendEvent("connected", "{\"instanceId\":\"" + instanceId + "\"}");

            // Replay buffered history so clients joining after server startup see prior
            // output.
            for (String line : consoleBuffer.getLines(instanceId)) {
                try {
                    client.sendEvent(line);
                } catch (Exception _) {
                    client.close();
                    return;
                }
            }

            EventSubscription subscription = eventBus.subscribe(InstanceConsoleOutputEvent.class, event -> {
                if (!event.instanceId().equals(instanceId)) return;
                try {
                    client.sendEvent(event.line());
                } catch (Exception _) {
                    logger.debug("Console SSE send failed for instance {}, closing client", instanceId);
                    client.close();
                }
            });
            client.keepAlive();
            client.onClose(subscription::unsubscribe);

            logger.debug("Console SSE client connected for instance {}", instanceId);
        });
    }
}
