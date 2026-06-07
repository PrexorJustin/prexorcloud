package me.prexorjustin.prexorcloud.modules.discord.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DiscordTarget")
class DiscordTargetTest {

    @Test
    @DisplayName("null fields normalise to empty defaults")
    void defaults() {
        var target = new DiscordTarget(null, null, null);
        assertEquals("", target.url());
        assertEquals("", target.username());
        assertTrue(target.events().isEmpty());
    }

    @Test
    @DisplayName("provided fields are preserved")
    void preservesValues() {
        var target =
                new DiscordTarget("https://discord.com/api/webhooks/1/abc", "PrexorCloud", List.of("instance_crashed"));
        assertEquals("https://discord.com/api/webhooks/1/abc", target.url());
        assertEquals("PrexorCloud", target.username());
        assertEquals(List.of("instance_crashed"), target.events());
    }
}
