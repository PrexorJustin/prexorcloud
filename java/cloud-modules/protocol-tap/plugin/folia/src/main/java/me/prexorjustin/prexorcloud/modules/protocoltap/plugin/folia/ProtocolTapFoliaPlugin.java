package me.prexorjustin.prexorcloud.modules.protocoltap.plugin.folia;

import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.annotation.CloudPlugin;
import me.prexorjustin.prexorcloud.modules.protocoltap.plugin.shared.AbstractProtocolTapPlugin;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Folia variant of protocol-tap.
 *
 * <p>Folia ships only on Paper 1.20+, so the modern Adventure
 * {@link AsyncChatEvent} is always available — no JAR-split needed on this
 * side. Counterpart to the Paper sibling: tier-2 dispatch is only for cases
 * where API drift forces it, and Folia (at the Paper-API level) doesn't
 * have that problem here.
 *
 * <p>Shared logic (counter, flush, HTTP send) lives in
 * {@link AbstractProtocolTapPlugin}.
 */
@CloudPlugin(
        name = "ProtocolTapFolia",
        version = "1.0.0",
        description = "Folia protocol observer — single-version on purpose (modern AsyncChatEvent).",
        authors = {"PrexorCloud"})
public final class ProtocolTapFoliaPlugin extends AbstractProtocolTapPlugin implements Listener {

    @Override
    public void onEnable(CloudPluginContext ctx) {
        super.onEnable(ctx);
        Bukkit.getPluginManager().registerEvents(this, getPlugin());
        ctx.logger().info("ProtocolTapFolia enabled on instance " + ctx.self().instanceId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        recordPacket();
    }

    @Override
    protected String packetTypeName() {
        return "AsyncChat";
    }

    private org.bukkit.plugin.java.JavaPlugin getPlugin() {
        return (org.bukkit.plugin.java.JavaPlugin) Bukkit.getPluginManager().getPlugin("ProtocolTapFolia");
    }
}
