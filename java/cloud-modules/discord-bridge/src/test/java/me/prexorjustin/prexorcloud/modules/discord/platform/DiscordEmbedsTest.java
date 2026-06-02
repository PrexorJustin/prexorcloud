package me.prexorjustin.prexorcloud.modules.discord.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.api.event.events.InstanceCrashedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeConnectedEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DiscordEmbeds")
class DiscordEmbedsTest {

    @Test
    @DisplayName("colour encodes severity: crash red, healthy green, routine blurple, unknown grey")
    void colours() {
        assertEquals(0xED4245, DiscordEmbeds.colorFor("instance_crashed"));
        assertEquals(0x57F287, DiscordEmbeds.colorFor("deployment_completed"));
        assertEquals(0x5865F2, DiscordEmbeds.colorFor("instance_state_changed"));
        assertEquals(0x95A5A6, DiscordEmbeds.colorFor("something_unmapped"));
    }

    @Test
    @DisplayName("title is humanised for unmapped wire names")
    void titles() {
        assertEquals("Instance crashed", DiscordEmbeds.titleFor("instance_crashed"));
        assertEquals("Some new thing", DiscordEmbeds.titleFor("some_new_thing"));
    }

    @Test
    @DisplayName("payload wraps a single embed with username and injected timestamp")
    @SuppressWarnings("unchecked")
    void payloadShape() {
        var event = new NodeConnectedEvent("node-1", "sess-9", null);
        Map<String, Object> payload =
                DiscordEmbeds.payload("PrexorCloud", "node_connected", event, "2026-06-02T00:00:00Z");

        assertEquals("PrexorCloud", payload.get("username"));
        var embeds = (List<Map<String, Object>>) payload.get("embeds");
        assertEquals(1, embeds.size());
        Map<String, Object> embed = embeds.get(0);
        assertEquals("Node connected", embed.get("title"));
        assertEquals(0x57F287, embed.get("color"));
        assertEquals("2026-06-02T00:00:00Z", embed.get("timestamp"));

        var fields = (List<Map<String, Object>>) embed.get("fields");
        assertEquals("Node", fields.get(0).get("name"));
        assertEquals("node-1", fields.get(0).get("value"));
        assertEquals("sess-9", fields.get(1).get("value"));
    }

    @Test
    @DisplayName("blank username is omitted; crash event renders its key fields")
    @SuppressWarnings("unchecked")
    void crashFieldsAndNoUsername() {
        var event = new InstanceCrashedEvent("inst-7", "lobby", "node-3", 134, "OOM", List.of("line"), 5000L);
        Map<String, Object> payload = DiscordEmbeds.payload("", "instance_crashed", event, "");

        assertFalse(payload.containsKey("username"));
        var embed = ((List<Map<String, Object>>) payload.get("embeds")).get(0);
        assertFalse(embed.containsKey("timestamp"));
        var fields = (List<Map<String, Object>>) embed.get("fields");
        var byName = fields.stream()
                .collect(java.util.stream.Collectors.toMap(f -> (String) f.get("name"), f -> f.get("value")));
        assertEquals("inst-7", byName.get("Instance"));
        assertEquals("lobby", byName.get("Group"));
        assertEquals("134", byName.get("Exit code"));
        assertEquals("OOM", byName.get("Classification"));
        assertTrue(fields.stream().allMatch(f -> Boolean.TRUE.equals(f.get("inline"))));
    }
}
