package me.prexorjustin.prexorcloud.modules.webhook.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WebhookConfig")
class WebhookConfigTest {

    @Test
    @DisplayName("null url normalises to empty string")
    void nullUrlBecomesEmpty() {
        WebhookConfig cfg = new WebhookConfig(null, List.of("node_connected"));
        assertEquals("", cfg.url());
    }

    @Test
    @DisplayName("null events list normalises to empty list (meaning: deliver every event)")
    void nullEventsBecomesEmpty() {
        WebhookConfig cfg = new WebhookConfig("https://example.com/hook", null);
        assertTrue(cfg.events().isEmpty());
    }

    @Test
    @DisplayName("populated url and event list pass through unchanged")
    void populatedFieldsPassThrough() {
        WebhookConfig cfg =
                new WebhookConfig("https://example.com/hook", List.of("node_connected", "instance_crashed"));
        assertEquals("https://example.com/hook", cfg.url());
        assertEquals(List.of("node_connected", "instance_crashed"), cfg.events());
    }
}
