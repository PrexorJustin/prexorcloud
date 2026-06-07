package me.prexorjustin.prexorcloud.controller.diagnostics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Ordered, JSON-friendly view of the controller diagnostics bundle. Consumed
 * by:
 *
 * <ul>
 *   <li>{@code GET /api/v1/system/diagnostics} — returns {@link #sections()} as JSON.</li>
 *   <li>{@code POST /api/v1/system/diagnostics/share} — feeds {@link #toTextDocument()}
 *       to {@code ShareService.shareText}.</li>
 *   <li>{@code prexorctl diagnostics bundle} — the CLI also embeds {@link #sections()}
 *       under {@code diagnostics.json} inside the {@code .tar.gz}.</li>
 * </ul>
 *
 * <p>
 * Keys are ordered for deterministic output; insertion order is preserved.
 * </p>
 */
public final class DiagnosticsSnapshot {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setNodeFactory(JsonNodeFactory.instance)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();

    private final Map<String, Object> sections;

    private DiagnosticsSnapshot(LinkedHashMap<String, Object> sections) {
        this.sections = Collections.unmodifiableMap(sections);
    }

    public Map<String, Object> sections() {
        return sections;
    }

    /**
     * Render the snapshot as a multi-section text document for the share path.
     * Each top-level section becomes a "## key" header followed by a
     * pretty-printed JSON block — operator-friendly, diffable, and lossless.
     */
    public String toTextDocument() {
        var out = new StringBuilder(8 * 1024);
        for (var entry : sections.entrySet()) {
            out.append("## ").append(entry.getKey()).append("\n");
            try {
                out.append(PRETTY.writeValueAsString(entry.getValue()));
            } catch (Exception e) {
                out.append("<serialization failed: ").append(e.getMessage()).append('>');
            }
            out.append("\n\n");
        }
        return out.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final LinkedHashMap<String, Object> sections = new LinkedHashMap<>();

        public Builder put(String key, Object value) {
            sections.put(key, value);
            return this;
        }

        public DiagnosticsSnapshot build() {
            return new DiagnosticsSnapshot(sections);
        }
    }
}
