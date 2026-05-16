package me.prexorjustin.prexorcloud.modules.tablist.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TablistTemplate")
class TablistTemplateTest {

    @Test
    @DisplayName("rejects blank name")
    void rejectsBlankName() {
        assertThrows(
                IllegalArgumentException.class, () -> new TablistTemplate("", "lobby", List.of("h"), List.of("f"), 5));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TablistTemplate("   ", "lobby", List.of("h"), List.of("f"), 5));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TablistTemplate(null, "lobby", List.of("h"), List.of("f"), 5));
    }

    @Test
    @DisplayName("null header/footer lines default to empty lists")
    void nullLinesDefaultEmpty() {
        TablistTemplate t = new TablistTemplate("default", "lobby", null, null, 5);
        assertTrue(t.headerLines().isEmpty());
        assertTrue(t.footerLines().isEmpty());
    }

    @Test
    @DisplayName("non-positive refresh falls back to 5 seconds")
    void nonPositiveRefreshFallsBackToFive() {
        assertEquals(5, new TablistTemplate("a", "g", List.of(), List.of(), 0).refreshSeconds());
        assertEquals(5, new TablistTemplate("a", "g", List.of(), List.of(), -10).refreshSeconds());
    }

    @Test
    @DisplayName("line lists are defensively copied — caller mutation does not leak in")
    void lineListsAreDefensivelyCopied() {
        List<String> headers = new ArrayList<>(List.of("h1"));
        TablistTemplate t = new TablistTemplate("a", "g", headers, List.of(), 5);
        headers.add("h2-after-construction");
        assertEquals(List.of("h1"), t.headerLines());
    }
}
