package me.prexorjustin.prexorcloud.modules.protocoltap.plugin.paper.v1_21;

import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.annotation.CloudPlugin;
import me.prexorjustin.prexorcloud.modules.protocoltap.plugin.shared.AbstractProtocolTapPlugin;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Paper 1.21 variant of protocol-tap.
 *
 * <p>Sibling of {@code v1_20}. Listens to the modern Adventure-flavoured
 * {@link AsyncChatEvent} that replaced the legacy {@code AsyncPlayerChatEvent}
 * in 1.21. Compiles against {@code paperApi121} via the
 * {@code prexorcloud.plugin-paper-1-21} convention plugin — the legacy event
 * isn't on this subproject's classpath, which is the whole point: each
 * version's subproject sees only its own world, so API drift surfaces at
 * compile time in the right place.
 *
 * <p>Shared logic (counter, flush, HTTP send) lives in
 * {@link AbstractProtocolTapPlugin}.
 */
@CloudPlugin(
        name = "ProtocolTapPaperV1_21",
        version = "1.0.0",
        description = "Paper 1.21 protocol observer — JAR-split tier-2 demo (modern AsyncChatEvent).",
        authors = {"PrexorCloud"})
public final class ProtocolTapPaperV1_21Plugin extends AbstractProtocolTapPlugin implements Listener {

    @Override
    public void onEnable(CloudPluginContext ctx) {
        super.onEnable(ctx);
        Bukkit.getPluginManager().registerEvents(this, getPlugin());
        ctx.logger()
                .info("ProtocolTapPaperV1_21 enabled — observing modern AsyncChatEvent on instance "
                        + ctx.self().instanceId());
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
        return (org.bukkit.plugin.java.JavaPlugin) Bukkit.getPluginManager().getPlugin("ProtocolTapPaperV1_21");
    }
}
