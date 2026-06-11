package com.clustersprj.fjeconomy;

import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import com.clustersprj.fjeconomy.economy.EconomyManager;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.logging.Level;

public class LoginBonusManager {

    private final FJEconomy plugin;
    private final DatabaseManager dbManager;
    private final ConfigManager configManager;
    private final EconomyManager economyManager;

    public LoginBonusManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
        this.configManager = plugin.getConfigManager();
        this.economyManager = plugin.getEconomyManager();
    }

    /**
     * Checks if a player is eligible for a login bonus and grants it if so.
     * This method should be called when a player joins the server.
     *
     * @param playerUUID The UUID of the joining player.
     * @param playerName The name of the joining player.
     */
    public void checkAndGrantLoginBonus(UUID playerUUID, String playerName) {
        if (!configManager.isLoginBonusEnabled()) {
            return; // ログインボーナスが無効な場合は何もしない
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = dbManager.getConnection()) {
                Timestamp lastBonusClaim = null;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT last_bonus_claim FROM fje_login_bonuses WHERE uuid = ?")) {
                    stmt.setString(1, playerUUID.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            lastBonusClaim = rs.getTimestamp("last_bonus_claim");
                        }
                    }
                }

                LocalDateTime now = LocalDateTime.now();
                LocalDateTime nextClaimTime = null;
                if (lastBonusClaim != null) {
                    nextClaimTime = lastBonusClaim.toLocalDateTime().plusHours(configManager.getLoginBonusCooldownHours());
                }

                if (lastBonusClaim == null || now.isAfter(nextClaimTime)) {
                    // ボーナスを付与
                    economyManager.giveMoney(conn, playerUUID, playerName, configManager.getLoginBonusAmount());

                    // 最終取得日時を更新または挿入
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "INSERT INTO fje_login_bonuses (uuid, last_bonus_claim) VALUES (?, ?) " +
                            "ON DUPLICATE KEY UPDATE last_bonus_claim = ?")) {
                        stmt.setString(1, playerUUID.toString());
                        stmt.setTimestamp(2, Timestamp.valueOf(now));
                        stmt.setTimestamp(3, Timestamp.valueOf(now));
                        stmt.executeUpdate();
                    }
                    Bukkit.getPlayer(playerUUID).sendMessage("§aログインボーナスとして " + economyManager.formatMoney(configManager.getLoginBonusAmount()) + " を受け取りました！");
                    plugin.getLogger().info("プレイヤー " + playerName + " にログインボーナス " + configManager.getLoginBonusAmount() + " を付与しました。");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "ログインボーナス処理中にエラーが発生しました: " + playerName, e);
            }
        });
    }
}