package com.clustersprj.fjeconomy.listener;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.economy.EconomyManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerListener implements Listener {

    private final FJEconomy plugin;
    private final EconomyManager economyManager;

    public PlayerListener(FJEconomy plugin) {
        this.plugin = plugin;
        this.economyManager = new EconomyManager(plugin);
    }

    /**
     * Handle player join event
     * Create account if player doesn't exist
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        economyManager.ensurePlayerAccount(
                event.getPlayer().getUniqueId(),
                event.getPlayer().getName());

        plugin.getLogger().info("プレイヤー " + event.getPlayer().getName() + " のアカウントを確認しました");
    }
}
