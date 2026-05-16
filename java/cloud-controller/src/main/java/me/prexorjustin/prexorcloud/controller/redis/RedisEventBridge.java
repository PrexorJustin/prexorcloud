package me.prexorjustin.prexorcloud.controller.redis;

import me.prexorjustin.prexorcloud.api.event.events.*;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges local EventBus events to Redis Pub/Sub channels and subscribes to
 * remote controller events for multi-controller HA.
 *
 * Each event is wrapped in a {@link RedisEventEnvelope} with the publishing
 * controller's ID. On receive, own events are skipped (loop prevention).
 */
public final class RedisEventBridge {

    private static final Logger logger = LoggerFactory.getLogger(RedisEventBridge.class);

    static final String CHANNEL_NODE = RedisKeys.CHANNEL_NODE;
    static final String CHANNEL_INSTANCE = RedisKeys.CHANNEL_INSTANCE;
    static final String CHANNEL_PLAYER = RedisKeys.CHANNEL_PLAYER;
    static final String CHANNEL_GROUP = RedisKeys.CHANNEL_GROUP;
    static final String CHANNEL_COMMAND = RedisKeys.CHANNEL_COMMAND;
    static final String CHANNEL_REPLY = RedisKeys.CHANNEL_REPLY;

    private final String controllerId;
    private final EventBus eventBus;
    private final ClusterState clusterState;
    private final NodeSessionManager sessionManager;
    private final GroupManager groupManager;
    private final StatefulRedisPubSubConnection<String, String> publishPubSub;
    private final StatefulRedisPubSubConnection<String, String> subscribePubSub;
    private final ObjectMapper mapper;
    private volatile java.util.function.Consumer<me.prexorjustin.prexorcloud.protocol.DaemonMessage> remoteReplyHandler;

    record RedisEventEnvelope(String controllerId, String eventType, String payload) {}

    public RedisEventBridge(
            String controllerId,
            EventBus eventBus,
            ClusterState clusterState,
            NodeSessionManager sessionManager,
            GroupManager groupManager,
            StatefulRedisPubSubConnection<String, String> publishPubSub,
            StatefulRedisPubSubConnection<String, String> subscribePubSub,
            ObjectMapper mapper) {
        this.controllerId = controllerId;
        this.eventBus = eventBus;
        this.clusterState = clusterState;
        this.sessionManager = sessionManager;
        this.groupManager = groupManager;
        this.publishPubSub = publishPubSub;
        this.subscribePubSub = subscribePubSub;
        this.mapper = mapper;
    }

    /**
     * Subscribe to local EventBus and publish events to Redis.
     */
    public void register() {
        eventBus.subscribe(NodeConnectedEvent.class, e -> publish(CHANNEL_NODE, e));
        eventBus.subscribe(NodeDisconnectedEvent.class, e -> publish(CHANNEL_NODE, e));
        eventBus.subscribe(NodeStatusUpdatedEvent.class, e -> publish(CHANNEL_NODE, e));
        eventBus.subscribe(InstanceStateChangedEvent.class, e -> publish(CHANNEL_INSTANCE, e));
        eventBus.subscribe(PlayerConnectedEvent.class, e -> publish(CHANNEL_PLAYER, e));
        eventBus.subscribe(PlayerDisconnectedEvent.class, e -> publish(CHANNEL_PLAYER, e));
        eventBus.subscribe(GroupCreatedEvent.class, e -> publish(CHANNEL_GROUP, e));
        eventBus.subscribe(GroupUpdatedEvent.class, e -> publish(CHANNEL_GROUP, e));
        eventBus.subscribe(GroupDeletedEvent.class, e -> publish(CHANNEL_GROUP, e));
    }

    /**
     * Subscribe to Redis Pub/Sub channels and apply remote events to local state.
     */
    public void subscribe() {
        subscribePubSub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                try {
                    var envelope = mapper.readValue(message, RedisEventEnvelope.class);
                    if (controllerId.equals(envelope.controllerId())) return; // skip own events
                    applyRemoteEvent(channel, envelope);
                } catch (Exception e) {
                    logger.warn("Failed to process remote event on channel {}: {}", channel, e.getMessage());
                }
            }
        });
        subscribePubSub
                .async()
                .subscribe(
                        CHANNEL_NODE, CHANNEL_INSTANCE, CHANNEL_PLAYER, CHANNEL_GROUP, CHANNEL_COMMAND, CHANNEL_REPLY);
        logger.info("Redis event subscription started for controller {}", controllerId);
    }

    /**
     * Route a command to a remote controller via Redis.
     */
    public void routeCommand(String targetControllerId, String nodeId, byte[] messageBytes) {
        var cmd = new RemoteCommand(
                targetControllerId, nodeId, java.util.Base64.getEncoder().encodeToString(messageBytes));
        publish(CHANNEL_COMMAND, cmd);
    }

    /**
     * Forward a daemon reply to the controller that originated the request.
     * Used by the HA cross-controller round-trip path — the controller that
     * owns the daemon session receives the reply, recognises that the
     * correlation id was minted by a different controller, and publishes the
     * reply here so the originator can complete its pending future.
     */
    public void routeReply(String targetControllerId, byte[] daemonMessageBytes) {
        var reply = new RemoteReply(
                targetControllerId, java.util.Base64.getEncoder().encodeToString(daemonMessageBytes));
        publish(CHANNEL_REPLY, reply);
    }

    /**
     * Register a callback invoked when this controller receives a
     * {@code CHANNEL_REPLY} envelope addressed to it. Typically wired in
     * bootstrap to {@code PendingRequestRegistry}.
     */
    public void onRemoteReply(java.util.function.Consumer<me.prexorjustin.prexorcloud.protocol.DaemonMessage> handler) {
        this.remoteReplyHandler = handler;
    }

    public String controllerId() {
        return controllerId;
    }

    public NodeSessionManager sessionManager() {
        return sessionManager;
    }

    // --- Internal ---

    private void publish(String channel, Object event) {
        try {
            String eventType = event.getClass().getSimpleName();
            String payload = mapper.writeValueAsString(event);
            var envelope = new RedisEventEnvelope(controllerId, eventType, payload);
            publishPubSub.async().publish(channel, mapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            logger.warn("Failed to publish event to Redis channel {}: {}", channel, e.getMessage());
        }
    }

    private void applyRemoteEvent(String channel, RedisEventEnvelope envelope) {
        try {
            switch (channel) {
                case CHANNEL_NODE -> applyRemoteNodeEvent(envelope);
                case CHANNEL_INSTANCE -> applyRemoteInstanceEvent(envelope);
                case CHANNEL_PLAYER -> applyRemotePlayerEvent(envelope);
                case CHANNEL_GROUP -> applyRemoteGroupEvent(envelope);
                case CHANNEL_COMMAND -> applyRemoteCommand(envelope);
                case CHANNEL_REPLY -> applyRemoteReply(envelope);
                default -> logger.debug("Unknown Redis channel: {}", channel);
            }
        } catch (Exception e) {
            logger.warn("Failed to apply remote {} event: {}", envelope.eventType(), e.getMessage());
        }
    }

    private void applyRemoteNodeEvent(RedisEventEnvelope envelope) throws Exception {
        switch (envelope.eventType()) {
            case "NodeConnectedEvent" -> {
                var event = mapper.readValue(envelope.payload(), NodeConnectedEvent.class);
                clusterState.applyRemoteNodeConnected(event.nodeId(), event.sessionId(), event.timestamp());
            }
            case "NodeDisconnectedEvent" -> {
                var event = mapper.readValue(envelope.payload(), NodeDisconnectedEvent.class);
                clusterState.applyRemoteNodeDisconnected(event.nodeId());
            }
            case "NodeStatusUpdatedEvent" -> {
                var event = mapper.readValue(envelope.payload(), NodeStatusUpdatedEvent.class);
                clusterState.applyRemoteNodeStatusUpdated(
                        event.nodeId(), event.cpuUsage(), event.usedMemoryMb(), event.totalMemoryMb());
            }
            default -> logger.debug("Unknown node event type: {}", envelope.eventType());
        }
    }

    private void applyRemoteInstanceEvent(RedisEventEnvelope envelope) throws Exception {
        if ("InstanceStateChangedEvent".equals(envelope.eventType())) {
            var event = mapper.readValue(envelope.payload(), InstanceStateChangedEvent.class);
            clusterState.applyRemoteInstanceStateChanged(event.instanceId(), event.newState());
        }
    }

    private void applyRemotePlayerEvent(RedisEventEnvelope envelope) throws Exception {
        switch (envelope.eventType()) {
            case "PlayerConnectedEvent" -> {
                var event = mapper.readValue(envelope.payload(), PlayerConnectedEvent.class);
                clusterState.applyRemotePlayerConnected(event.uuid(), event.name(), event.instanceId(), event.group());
            }
            case "PlayerDisconnectedEvent" -> {
                var event = mapper.readValue(envelope.payload(), PlayerDisconnectedEvent.class);
                clusterState.applyRemotePlayerDisconnected(event.uuid());
            }
            default -> logger.debug("Unknown player event type: {}", envelope.eventType());
        }
    }

    private void applyRemoteGroupEvent(RedisEventEnvelope envelope) throws Exception {
        switch (envelope.eventType()) {
            case "GroupCreatedEvent" -> {
                var event = mapper.readValue(envelope.payload(), GroupCreatedEvent.class);
                groupManager.reloadGroup(event.groupName());
            }
            case "GroupUpdatedEvent" -> {
                var event = mapper.readValue(envelope.payload(), GroupUpdatedEvent.class);
                groupManager.reloadGroup(event.groupName());
            }
            case "GroupDeletedEvent" -> {
                var event = mapper.readValue(envelope.payload(), GroupDeletedEvent.class);
                groupManager.removeGroupFromCache(event.groupName());
            }
            default -> logger.debug("Unknown group event type: {}", envelope.eventType());
        }
    }

    private void applyRemoteCommand(RedisEventEnvelope envelope) throws Exception {
        var cmd = mapper.readValue(envelope.payload(), RemoteCommand.class);
        if (!controllerId.equals(cmd.targetControllerId())) return;
        byte[] bytes = java.util.Base64.getDecoder().decode(cmd.messageBase64());
        var message = me.prexorjustin.prexorcloud.protocol.ControllerMessage.parseFrom(bytes);
        sessionManager.getByNodeId(cmd.nodeId()).ifPresent(session -> session.send(message));
        logger.debug("Dispatched remote command to node {} from controller {}", cmd.nodeId(), envelope.controllerId());
    }

    record RemoteCommand(String targetControllerId, String nodeId, String messageBase64) {}

    record RemoteReply(String targetControllerId, String messageBase64) {}

    private void applyRemoteReply(RedisEventEnvelope envelope) throws Exception {
        var reply = mapper.readValue(envelope.payload(), RemoteReply.class);
        if (!controllerId.equals(reply.targetControllerId())) return;
        var handler = remoteReplyHandler;
        if (handler == null) {
            logger.debug("Dropping remote reply — no handler wired");
            return;
        }
        byte[] bytes = java.util.Base64.getDecoder().decode(reply.messageBase64());
        var message = me.prexorjustin.prexorcloud.protocol.DaemonMessage.parseFrom(bytes);
        handler.accept(message);
    }
}
