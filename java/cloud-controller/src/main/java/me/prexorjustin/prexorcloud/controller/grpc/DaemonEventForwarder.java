package me.prexorjustin.prexorcloud.controller.grpc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.EventSubscription;
import me.prexorjustin.prexorcloud.common.io.ObjectMappers;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.session.NodeSession;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.ErrorReport;
import me.prexorjustin.prexorcloud.protocol.ModuleEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the controller's in-process {@link EventBus} to subscribed daemons over gRPC.
 *
 * <p>Subscribe-registration model: each daemon explicitly registers the {@code CloudEvent}
 * subclasses it wants. The forwarder maintains a {@code Map<nodeId, Map<eventType, EventSubscription>>}
 * and only attaches one EventBus subscription per (node, event-type) pair. When the daemon
 * unsubscribes (or disconnects) the corresponding {@link EventSubscription} is detached so
 * the controller-side EventBus does not amplify traffic to N daemons when only one cares.
 *
 * <p>Each forwarded event is serialized via {@link ObjectMappers#standard()} to JSON bytes
 * and delivered as a {@link ModuleEvent} envelope over the daemon's response stream.
 */
public final class DaemonEventForwarder {

    private static final Logger logger = LoggerFactory.getLogger(DaemonEventForwarder.class);

    private final EventBus eventBus;
    private final NodeSessionManager sessionManager;
    private final ObjectMapper json;

    /**
     * Per-daemon, per-event-type live subscription handles. Outer map keyed by nodeId so
     * disconnect-cleanup is a single {@link Map#remove} + iteration. Inner map keyed by
     * fully-qualified event class name (the wire form daemons send).
     */
    private final Map<String, Map<String, EventSubscription>> subscriptionsByNode = new ConcurrentHashMap<>();

    public DaemonEventForwarder(EventBus eventBus, NodeSessionManager sessionManager) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.json = ObjectMappers.standard();
    }

    /**
     * Apply an {@code EventSubscribe} message: resolve each event-type class name and attach
     * an EventBus subscription for any not already registered for this daemon. Unknown class
     * names produce an {@link ErrorReport} back to the daemon (the rest of the list still
     * subscribes — partial success is fine).
     */
    public void onSubscribe(String nodeId, List<String> eventTypes) {
        Objects.requireNonNull(nodeId, "nodeId");
        Map<String, EventSubscription> nodeSubs =
                subscriptionsByNode.computeIfAbsent(nodeId, _ -> new LinkedHashMap<>());
        synchronized (nodeSubs) {
            for (String eventType : eventTypes) {
                if (eventType == null || eventType.isBlank()) {
                    sendErrorReport(nodeId, "EVENT_TYPE_BLANK", "EventSubscribe contained a blank event type");
                    continue;
                }
                if (nodeSubs.containsKey(eventType)) {
                    continue;
                }
                Class<? extends CloudEvent> clazz = resolveEventClass(nodeId, eventType);
                if (clazz == null) {
                    continue;
                }
                EventSubscription sub = subscribeFor(clazz, nodeId);
                nodeSubs.put(eventType, sub);
                logger.debug("daemon {} subscribed to controller-bus event {}", nodeId, eventType);
            }
        }
    }

    /**
     * Apply an {@code EventUnsubscribe} message: detach any matching subscription handles.
     * Silent for event types this daemon never subscribed to — same shape as
     * {@link EventSubscription#unsubscribe()} on a stale handle.
     */
    public void onUnsubscribe(String nodeId, List<String> eventTypes) {
        Objects.requireNonNull(nodeId, "nodeId");
        Map<String, EventSubscription> nodeSubs = subscriptionsByNode.get(nodeId);
        if (nodeSubs == null) {
            return;
        }
        synchronized (nodeSubs) {
            for (String eventType : eventTypes) {
                EventSubscription sub = nodeSubs.remove(eventType);
                if (sub != null) {
                    sub.unsubscribe();
                    logger.debug("daemon {} unsubscribed from controller-bus event {}", nodeId, eventType);
                }
            }
        }
    }

    /**
     * Detach every subscription attached for {@code nodeId}. Called when a daemon stream
     * completes or errors so the controller's EventBus does not retain references to a
     * dead session's response observer.
     */
    public void onDisconnect(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        Map<String, EventSubscription> nodeSubs = subscriptionsByNode.remove(nodeId);
        if (nodeSubs == null) {
            return;
        }
        synchronized (nodeSubs) {
            for (EventSubscription sub : nodeSubs.values()) {
                sub.unsubscribe();
            }
            nodeSubs.clear();
        }
    }

    /**
     * Visible for tests and metrics: how many event types are currently subscribed for
     * the given daemon.
     */
    public int subscribedEventTypeCount(String nodeId) {
        Map<String, EventSubscription> nodeSubs = subscriptionsByNode.get(nodeId);
        return nodeSubs == null ? 0 : nodeSubs.size();
    }

    private <T extends CloudEvent> EventSubscription subscribeFor(Class<T> clazz, String nodeId) {
        return eventBus.subscribe(clazz, event -> forward(nodeId, clazz.getName(), event));
    }

    private void forward(String nodeId, String eventType, CloudEvent event) {
        NodeSession session = sessionManager.getByNodeId(nodeId).orElse(null);
        if (session == null) {
            // Race: daemon disconnected after the EventBus dispatched. Subscription will be
            // cleaned up by onDisconnect; drop this event.
            return;
        }
        byte[] payload;
        try {
            payload = json.writeValueAsBytes(event);
        } catch (Exception e) {
            logger.warn("failed to serialize {} for daemon {}: {}", eventType, nodeId, e.getMessage());
            return;
        }
        ControllerMessage envelope = ControllerMessage.newBuilder()
                .setModuleEvent(
                        ModuleEvent.newBuilder().setEventType(eventType).setPayloadJson(ByteString.copyFrom(payload)))
                .build();
        try {
            session.send(envelope);
        } catch (Exception e) {
            logger.warn(
                    "failed to forward {} to daemon {} (session {}): {}",
                    eventType,
                    nodeId,
                    session.sessionId(),
                    e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Class<? extends CloudEvent> resolveEventClass(String nodeId, String eventType) {
        try {
            Class<?> clazz = Class.forName(eventType, false, CloudEvent.class.getClassLoader());
            if (!CloudEvent.class.isAssignableFrom(clazz)) {
                sendErrorReport(
                        nodeId,
                        "EVENT_TYPE_NOT_CLOUD_EVENT",
                        "subscribed event type does not implement CloudEvent: " + eventType);
                return null;
            }
            return (Class<? extends CloudEvent>) clazz;
        } catch (ClassNotFoundException _) {
            sendErrorReport(
                    nodeId,
                    "EVENT_TYPE_UNKNOWN",
                    "subscribed event type is not on the controller's classpath: " + eventType);
            return null;
        }
    }

    private void sendErrorReport(String nodeId, String code, String message) {
        NodeSession session = sessionManager.getByNodeId(nodeId).orElse(null);
        if (session == null) {
            return;
        }
        ControllerMessage envelope = ControllerMessage.newBuilder()
                .setErrorReport(ErrorReport.newBuilder().setErrorCode(code).setErrorMessage(message))
                .build();
        try {
            session.send(envelope);
        } catch (Exception e) {
            logger.debug("failed to send ErrorReport to daemon {}: {}", nodeId, e.getMessage());
        }
    }
}
