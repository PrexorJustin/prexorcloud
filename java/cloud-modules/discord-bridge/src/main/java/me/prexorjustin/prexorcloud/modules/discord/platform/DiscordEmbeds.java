package me.prexorjustin.prexorcloud.modules.discord.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.events.DeploymentCompletedEvent;
import me.prexorjustin.prexorcloud.api.event.events.DeploymentCreatedEvent;
import me.prexorjustin.prexorcloud.api.event.events.GroupCrashLoopEvent;
import me.prexorjustin.prexorcloud.api.event.events.InstanceCrashedEvent;
import me.prexorjustin.prexorcloud.api.event.events.InstanceStateChangedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeConnectedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeDisconnectedEvent;

/**
 * Pure formatter that renders a {@link CloudEvent} as a Discord incoming-webhook payload
 * ({@code {username?, embeds:[{title, color, fields, timestamp?}]}}). Kept side-effect-free and
 * timestamp-injected so it is fully unit-testable; the module supplies {@code Instant.now()}.
 *
 * <p>The embed colour encodes severity at a glance: green for healthy transitions, red for crashes,
 * orange for disconnects, blurple for routine activity.
 */
public final class DiscordEmbeds {

    // Discord embed colours as decimal RGB (the field is an int in the webhook API).
    private static final int GREEN = 0x57F287;
    private static final int RED = 0xED4245;
    private static final int DARK_RED = 0x992D22;
    private static final int ORANGE = 0xE67E22;
    private static final int BLURPLE = 0x5865F2;
    private static final int GREY = 0x95A5A6;

    private DiscordEmbeds() {}

    /** Build the full Discord webhook payload for one event. */
    public static Map<String, Object> payload(String username, String wireName, CloudEvent event, String timestamp) {
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", titleFor(wireName));
        embed.put("color", colorFor(wireName));
        embed.put("fields", fieldsFor(event));
        if (timestamp != null && !timestamp.isEmpty()) {
            embed.put("timestamp", timestamp);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (username != null && !username.isEmpty()) {
            payload.put("username", username);
        }
        payload.put("embeds", List.of(embed));
        return payload;
    }

    static int colorFor(String wireName) {
        return switch (wireName) {
            case "node_connected", "deployment_completed" -> GREEN;
            case "instance_crashed" -> RED;
            case "crash_loop" -> DARK_RED;
            case "node_disconnected" -> ORANGE;
            case "instance_state_changed", "deployment_created" -> BLURPLE;
            default -> GREY;
        };
    }

    static String titleFor(String wireName) {
        return switch (wireName) {
            case "node_connected" -> "Node connected";
            case "node_disconnected" -> "Node disconnected";
            case "instance_state_changed" -> "Instance state changed";
            case "instance_crashed" -> "Instance crashed";
            case "crash_loop" -> "Crash loop detected";
            case "deployment_created" -> "Deployment started";
            case "deployment_completed" -> "Deployment completed";
            default -> humanize(wireName);
        };
    }

    /** Turn an unknown wire name like {@code "some_event"} into {@code "Some event"}. */
    private static String humanize(String wireName) {
        if (wireName == null || wireName.isEmpty()) {
            return "Event";
        }
        String spaced = wireName.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    static List<Map<String, Object>> fieldsFor(CloudEvent event) {
        List<Map<String, Object>> fields = new ArrayList<>();
        switch (event) {
            case NodeConnectedEvent e -> {
                fields.add(field("Node", e.nodeId()));
                fields.add(field("Session", e.sessionId()));
            }
            case NodeDisconnectedEvent e -> {
                fields.add(field("Node", e.nodeId()));
                fields.add(field("Reason", e.reason()));
            }
            case InstanceStateChangedEvent e -> {
                fields.add(field("Instance", e.instanceId()));
                fields.add(field("Group", e.group()));
                fields.add(field("Node", e.nodeId()));
                fields.add(field(
                        "Transition", e.oldState().name() + " → " + e.newState().name()));
            }
            case InstanceCrashedEvent e -> {
                fields.add(field("Instance", e.instanceId()));
                fields.add(field("Group", e.group()));
                fields.add(field("Node", e.nodeId()));
                fields.add(field("Exit code", e.exitCode()));
                fields.add(field("Classification", e.classification()));
            }
            case GroupCrashLoopEvent e -> {
                fields.add(field("Group", e.group()));
                fields.add(field("Crashes", e.crashCount()));
                fields.add(field("Window start", e.windowStart().toString()));
            }
            case DeploymentCreatedEvent e -> {
                fields.add(field("Group", e.groupName()));
                fields.add(field("Revision", e.revision()));
                fields.add(field("Strategy", e.strategy()));
            }
            case DeploymentCompletedEvent e -> {
                fields.add(field("Group", e.groupName()));
                fields.add(field("Revision", e.revision()));
                fields.add(field("Outcome", e.outcome()));
            }
            default -> {
                // No structured fields for an unmapped event — the title alone carries it.
            }
        }
        return fields;
    }

    private static Map<String, Object> field(String name, Object value) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("value", String.valueOf(value));
        field.put("inline", true);
        return field;
    }
}
