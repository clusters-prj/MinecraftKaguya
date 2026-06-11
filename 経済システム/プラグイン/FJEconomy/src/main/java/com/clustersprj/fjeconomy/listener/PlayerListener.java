package com.clustersprj.fjeconomy.listener;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.economy.EconomyManager;
import com.clustersprj.fjeconomy.loginbonus.LoginBonusManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerListener implements Listener {
    private final FJEconomy plugin;
    private final EconomyManager economyManager;
    private final LoginBonusManager loginBonusManager;

    public PlayerListener(FJEconomy plugin) {
        this.plugin = plugin;
        // EconomyManager は FJEconomy から取得するように変更
        this.economyManager = plugin.getEconomyManager(); // FJEconomyにgetterを追加するか、直接インスタンスを渡す
        this.loginBonusManager = plugin.getLoginBonusManager();
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
        
        // ログインボーナスをチェック
        loginBonusManager.checkAndGrantLoginBonus(
                event.getPlayer().getUniqueId(), event.getPlayer().getName());

        plugin.getLogger().info("プレイヤー " + event.getPlayer().getName() + " のアカウントを確認しました");
    }
}
