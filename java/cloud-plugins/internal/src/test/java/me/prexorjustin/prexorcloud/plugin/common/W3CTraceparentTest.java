package me.prexorjustin.prexorcloud.plugin.common;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("W3CTraceparent")
class W3CTraceparentTest {

    // version "00", 32-hex trace-id, 16-hex span-id, flags "01" (sampled).
    private static final Pattern VALID = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$");

    @Test
    @DisplayName("emits a spec-shaped, sampled traceparent")
    void emitsValidTraceparent() {
        String tp = W3CTraceparent.random();
        assertTrue(VALID.matcher(tp).matches(), () -> "not a valid traceparent: " + tp);
    }

    @Test
    @DisplayName("trace-id and span-id are not all-zero (invalid per spec)")
    void notAllZero() {
        for (int i = 0; i < 1000; i++) {
            String tp = W3CTraceparent.random();
            String traceId = tp.substring(3, 35);
            String spanId = tp.substring(36, 52);
            assertNotEquals("0".repeat(32), traceId);
            assertNotEquals("0".repeat(16), spanId);
        }
    }

    @Test
    @DisplayName("successive values differ")
    void valuesDiffer() {
        assertNotEquals(W3CTraceparent.random(), W3CTraceparent.random());
    }
}
