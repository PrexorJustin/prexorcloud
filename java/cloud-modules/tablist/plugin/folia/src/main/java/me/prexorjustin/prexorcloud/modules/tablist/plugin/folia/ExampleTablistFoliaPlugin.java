package me.prexorjustin.prexorcloud.modules.tablist.plugin.folia;

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
 * Folia variant of the tablist module.
 *
 * <p>Identical {@code @ForVersion} dispatch logic to the Paper sibling —
 * header/footer rendering is global-scheduler-safe and uses only Adventure
 * Components, so no region-thread concerns. Teaching point: tier-1
 * multi-version dispatch is platform-agnostic. The only differences from
 * the Paper plugin are the {@code @CloudPlugin} name + the Folia convention
 * plugin in build.gradle.kts.
 */
@CloudPlugin(
        name = "ExampleTablistFolia",
        version = "1.0.0",
        description = "@ForVersion tablist demo on Folia — same logic as the Paper sibling.",
        authors = {"PrexorCloud"})
public final class ExampleTablistFoliaPlugin extends CloudPluginBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final AtomicReference<ActiveTemplate> active = new AtomicReference<>();

    private CloudPluginContext ctx;
    private LineRenderer renderer;

    @Override
    public void onEnable(CloudPluginContext ctx) {
        this.ctx = ctx;
        this.renderer = adapt(LineRenderer.class, ExampleTablistFoliaPlugin.class);
        ctx.logger()
                .info("ExampleTablistFolia enabled on instance "
                        + ctx.self().instanceId()
                        + " — @ForVersion renderer: "
                        + renderer.describe());

        ctx.scheduler().runDelayed(Duration.ofSeconds(2), this::pollAndApply);
        ctx.scheduler().runAtFixedRate(Duration.ofSeconds(5), Duration.ofSeconds(5), this::tick);
    }

    @Override
    public void onDisable() {
        ctx = null;
        renderer = null;
        active.set(null);
    }

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

    public interface LineRenderer {
        Component render(String raw);

        String describe();
    }

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

    private record ActiveTemplate(
            TemplateDto template, Component headerComponent, Component footerComponent, long fetchedAtMs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TemplateDto(
            String name, String group, List<String> headerLines, List<String> footerLines, int refreshSeconds) {}
}
