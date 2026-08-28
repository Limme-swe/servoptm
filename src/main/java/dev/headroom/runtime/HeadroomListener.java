package dev.headroom.runtime;

import com.destroystokyo.paper.event.player.PlayerClientOptionsChangeEvent;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import dev.headroom.HeadroomPlugin;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * Thin event bridge. The plugin owns the replaceable engine instance.
 */
public final class HeadroomListener implements Listener {

    private final HeadroomPlugin plugin;

    public HeadroomListener(HeadroomPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        this.plugin.getServer().getScheduler().runTask(
                this.plugin,
                () -> this.plugin.engine().addPlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.plugin.engine().removePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        this.plugin.getServer().getScheduler().runTask(
                this.plugin,
                () -> this.plugin.engine().resetPlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClientOptions(PlayerClientOptionsChangeEvent event) {
        if (event.hasViewDistanceChanged()) {
            this.plugin.engine().updateClientDistance(event.getPlayer(), event.getViewDistance());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(PlayerChunkLoadEvent event) {
        this.plugin.engine().confirmChunkDelivery(event.getPlayer(), event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(PlayerChunkUnloadEvent event) {
        this.plugin.engine().observeChunkUnload(event.getPlayer(), event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        this.plugin.engine().tick(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        String name = event.getPlugin().getName();
        if (name.equals("Geyser-Spigot") || name.equals("floodgate")) {
            this.plugin.engine().refreshClientDetection();
        }
    }
}
