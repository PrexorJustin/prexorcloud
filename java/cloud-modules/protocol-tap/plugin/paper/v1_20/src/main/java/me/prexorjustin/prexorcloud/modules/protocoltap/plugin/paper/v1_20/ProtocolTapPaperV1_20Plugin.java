package me.prexorjustin.prexorcloud.modules.protocoltap.plugin.paper.v1_20;

import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.annotation.CloudPlugin;
import me.prexorjustin.prexorcloud.modules.protocoltap.plugin.shared.AbstractProtocolTapPlugin;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Paper 1.20 variant of protocol-tap.
 *
 * <h3>Why two subprojects (tier-2 multi-version)?</h3>
 * Paper's chat-event API drifted between 1.20 and 1.21:
 * <ul>
 *   <li>1.20.x still ships the legacy {@link AsyncPlayerChatEvent} (deprecated
 *       but present).</li>
 *   <li>1.21.x phased it out; the v1_21 sibling listens to
 *       {@code io.papermc.paper.event.player.AsyncChatEvent} instead.</li>
 * </ul>
 * That cross-version delta is small but real: an {@code @ForVersion}
 * adapter would have to import both event types in one source set, and
 * whichever one isn't on the running server's classpath would resolve to a
 * NoClassDefFoundError when the JVM tries to load the adapter. JAR-split
 * sidesteps that — each subproject sees only its own world.
 *
 * <p>Shared logic (counter, flush, HTTP send) lives in
 * {@link AbstractProtocolTapPlugin}.
 *
 * <p><strong>Phase E upgrade (real NMS):</strong> swap this plugin's
 * subscription for a packet-listener hook on
 * {@code net.minecraft.server.network.ServerGamePacketListenerImpl} via
 * paperweight-userdev. The same pattern (per-version subproject, distinct
 * imports) carries over — the version delta just becomes much larger and
 * paperweight's mappings are what make it tractable.
 */
@CloudPlugin(
        name = "ProtocolTapPaperV1_20",
        version = "1.0.0",
        description = "Paper 1.20 protocol observer — JAR-split tier-2 demo (legacy AsyncPlayerChatEvent).",
        authors = {"PrexorCloud"})
public final class ProtocolTapPaperV1_20Plugin extends AbstractProtocolTapPlugin implements Listener {

    @Override
    public void onEnable(CloudPluginContext ctx) {
        super.onEnable(ctx);
        Bukkit.getPluginManager().registerEvents(this, getPlugin());
        ctx.logger()
                .info("ProtocolTapPaperV1_20 enabled — observing legacy AsyncPlayerChatEvent on instance "
                        + ctx.self().instanceId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // legacy 1.20 event is the whole teaching point
    public void onChat(AsyncPlayerChatEvent event) {
        recordPacket();
    }

    @Override
    protected String packetTypeName() {
        return "AsyncPlayerChat";
    }

    private org.bukkit.plugin.java.JavaPlugin getPlugin() {
        return (org.bukkit.plugin.java.JavaPlugin) Bukkit.getPluginManager().getPlugin("ProtocolTapPaperV1_20");
    }
}
