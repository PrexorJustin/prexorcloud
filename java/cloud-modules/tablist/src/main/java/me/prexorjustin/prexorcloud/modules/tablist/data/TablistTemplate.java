package me.prexorjustin.prexorcloud.modules.tablist.data;

import java.util.List;

/**
 * A tab-list template: name, header lines, footer lines, and how often the
 * plugin should poll for changes.
 *
 * <p>Strings carry whatever placeholder syntax the plugin's renderer
 * understands. The example-tablist Paper plugin uses {@code @ForVersion}
 * adapters that read the same template differently per MC version:
 * <ul>
 *   <li>1.18: legacy {@code &}-prefixed colour codes only</li>
 *   <li>1.19: legacy + RGB hex {@code &#aabbcc}</li>
 *   <li>1.20+: full MiniMessage syntax ({@code <gradient>}, {@code <click>}, ...)</li>
 * </ul>
 * The same stored template renders progressively richer output the newer the
 * server is — that is the @ForVersion teaching point.
 */
public record TablistTemplate(
        String name, String group, List<String> headerLines, List<String> footerLines, int refreshSeconds) {

    public TablistTemplate {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        headerLines = headerLines == null ? List.of() : List.copyOf(headerLines);
        footerLines = footerLines == null ? List.of() : List.copyOf(footerLines);
        if (refreshSeconds <= 0) refreshSeconds = 5;
    }
}
