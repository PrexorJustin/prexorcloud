package me.prexorjustin.prexorcloud.proxy.bungeecord;

import net.md_5.bungee.api.plugin.Plugin;

public final class PrexorCloudBungeeCord extends Plugin {

    private final BungeeCloudCore core = new BungeeCloudCore(this);

    @Override
    public void onEnable() {
        core.start();
    }

    @Override
    public void onDisable() {
        core.stop();
    }
}
