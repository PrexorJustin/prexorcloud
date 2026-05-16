package me.prexorjustin.prexorcloud.modules.tablist.plugin.paper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import me.prexorjustin.prexorcloud.api.client.env.PluginEnv;
import me.prexorjustin.prexorcloud.api.client.version.ForVersion;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginBase;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.annotation.CloudPlugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

/**
 * Tablist Paper plugin — reference implementation of {@code @ForVersion}
 * intra-jar dispatch across a wide MC version range (1.18 → 1.21).
 *
 * <p>The plugin polls
 * {@code GET /api/v1/modules/tablist/active?group=<group>} on the controller
 * every {@code refreshSeconds} (driven by the active template). When a fresh
 * template arrives, the version-selected {@link LineRenderer} converts each
 * raw line into an Adventure {@link Component} and the result is pushed to
 * every online player as their tab-list header/footer.
 *
 * <h3>Why three adapters across 1.18 → 1.21?</h3>
 * <ul>
 *   <li>{@link Legacy118Renderer} (1.18.x): only legacy {@code &}-prefixed
 *       chat colour codes. RGB hex is not parsed; raw input shows through.</li>
 *   <li>{@link Legacy119Renderer} (1.19.x): legacy {@code &} codes
 *       <strong>plus</strong> {@code &#aabbcc} hex via Adventure's hex-aware
 *       legacy serializer.</li>
 *   <li>{@link MiniMessage120Renderer} (1.20+ inherits on 1.21+): full
 *       MiniMessage parsing — gradients, hover events, click events, the
 *       works. No legacy codes recognised; templates targeting MiniMessage
 *       must use {@code <yellow>} not {@code &e}.</li>
 * </ul>
 * The same stored template renders progressively richer output as the server
 * version moves up. A 1.21 server reading a template authored with
 * {@code <gradient:#ff0000:#0000ff>Hello</gradient>} sees a real gradient; a
 * 1.18 server sees the literal string. That is the {@code @ForVersion}
 * teaching point: a single jar adapts to whatever version is running it.
 *
 * <p><strong>Why this is tier-1, not tier-2:</strong> every renderer compiles
 * cleanly against {@code paperApi120}. Nothing here needs NMS, mappings, or
 * binaries that won't load cross-version. {@code @ForVersion} dispatch is
 * sufficient. The planned {@code cloud-module-protocol-tap} module
 * demonstrates tier-2 (JAR-split + paperweight) for cases where the version
 * difference is genuinely binary-incompatible.
 */
@CloudPlugin(
        name = "ExampleTablistPaper",
        version = "1.0.0",
        description = "@ForVersion tablist demo — adapts header/footer rendering to the running MC version.",
        authors = {"PrexorCloud"})
public final class ExampleTablistPaperPlugin extends CloudPluginBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final AtomicReference<ActiveTemplate> active = new AtomicReference<>();

    private CloudPluginContext ctx;
    private LineRenderer renderer;

    @Override
    public void onEnable(CloudPluginContext ctx) {
        this.ctx = ctx;
        this.renderer = adapt(LineRenderer.class, ExampleTablistPaperPlugin.class);
        ctx.logger()
                .info("ExampleTablistPaper enabled on instance "
                        + ctx.self().instanceId()
                        + " — @ForVersion renderer: "
                        + renderer.describe());

        // Seed once + schedule periodic refresh. Initial poll runs ~2s after
        // enable so the controller's module-route registry has time to mount
        // /api/v1/modules/tablist/* on first install.
        ctx.scheduler().runDelayed(Duration.ofSeconds(2), this::pollAndApply);
        ctx.scheduler().runAtFixedRate(Duration.ofSeconds(5), Duration.ofSeconds(5), this::tick);
    }

    @Override
    public void onDisable() {
        ctx = null;
        renderer = null;
        active.set(null);
    }

    /**
     * Periodic tick: poll if enough time has elapsed (driven by the active
     * template's refreshSeconds), then re-apply to all online players. Apply
     * runs on every tick because new players who joined since the last apply
     * also need the current header/footer.
     */
    private void tick() {
        ActiveTemplate current = active.get();
        long now = System.currentTimeMillis();
        long pollIntervalMs = current == null ? 5_000L : Math.max(1_000L, current.template.refreshSeconds * 1_000L);
        if (current == null || now - current.fetchedAtMs >= pollIntervalMs) {
            pollAndApply();
            return;
        }
        applyToAllPlayers(current.headerComponent, current.footerComponent);
    }

    private void pollAndApply() {
        if (ctx == null) return;
        try {
            String url = PluginEnv.controllerUrl() + "/api/v1/modules/tablist/active?group="
                    + java.net.URLEncoder.encode(PluginEnv.group(), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + PluginEnv.pluginToken())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                // No template bound to this group — clear any prior state.
                if (active.getAndSet(null) != null) {
                    applyToAllPlayers(Component.empty(), Component.empty());
                }
                return;
            }
            if (response.statusCode() != 200) {
                ctx.logger().warning("tablist poll: HTTP " + response.statusCode());
                return;
            }
            TemplateDto dto = MAPPER.readValue(response.body(), TemplateDto.class);
            Component header = renderJoined(dto.headerLines());
            Component footer = renderJoined(dto.footerLines());
            active.set(new ActiveTemplate(dto, header, footer, System.currentTimeMillis()));
            applyToAllPlayers(header, footer);
        } catch (Exception e) {
            ctx.logger().warning("tablist poll failed: " + e.getMessage());
        }
    }

    private Component renderJoined(List<String> lines) {
        if (lines == null || lines.isEmpty()) return Component.empty();
        Component result = renderer.render(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            result = result.append(Component.newline()).append(renderer.render(lines.get(i)));
        }
        return result;
    }

    private void applyToAllPlayers(Component header, Component footer) {
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            p.sendPlayerListHeaderAndFooter(header, footer);
        }
    }

    // ── @ForVersion adapter contract ──────────────────────────────────────

    /**
     * Renders one line of the stored template as an Adventure {@link Component}.
     * One implementation per major-MC-version family; {@link VersionDispatcher}
     * picks the highest one whose {@code @ForVersion(min=...)} range covers
     * {@code Bukkit.getServer().getBukkitVersion()}.
     */
    public interface LineRenderer {
        Component render(String raw);

        String describe();
    }

    /** 1.18 — legacy {@code &}-codes only. No RGB hex support. */
    @ForVersion(min = "1.18", max = "1.18.99")
    public static final class Legacy118Renderer implements LineRenderer {
        private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();

        @Override
        public Component render(String raw) {
            return serializer.deserialize(raw == null ? "" : raw);
        }

        @Override
        public String describe() {
            return "1.18 — legacy ampersand colours (no hex)";
        }
    }

    /** 1.19 — legacy {@code &}-codes <em>plus</em> {@code &#aabbcc} RGB hex. */
    @ForVersion(min = "1.19", max = "1.19.99")
    public static final class Legacy119Renderer implements LineRenderer {
        private final LegacyComponentSerializer serializer = LegacyComponentSerializer.builder()
                .character('&')
                .hexColors()
                .useUnusualXRepeatedCharacterHexFormat()
                .build();

        @Override
        public Component render(String raw) {
            return serializer.deserialize(raw == null ? "" : raw);
        }

        @Override
        public String describe() {
            return "1.19 — legacy ampersand + RGB hex (&#aabbcc)";
        }
    }

    /** 1.20+ (and 1.21 by inheritance of dispatcher highest-min selection) — full MiniMessage parser. */
    @ForVersion(min = "1.20")
    public static final class MiniMessage120Renderer implements LineRenderer {
        private final MiniMessage miniMessage = MiniMessage.miniMessage();

        @Override
        public Component render(String raw) {
            return miniMessage.deserialize(raw == null ? "" : raw);
        }

        @Override
        public String describe() {
            return "1.20+ — MiniMessage (gradients, hover, click)";
        }
    }

    // ── Internal types ────────────────────────────────────────────────────

    private record ActiveTemplate(
            TemplateDto template, Component headerComponent, Component footerComponent, long fetchedAtMs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TemplateDto(
            String name, String group, List<String> headerLines, List<String> footerLines, int refreshSeconds) {}
}
