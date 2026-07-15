package com.clustersprj.fjeconomy;

import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import com.clustersprj.fjeconomy.economy.EconomyManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.logging.Level;

/**
 * ログインボーナスシステムを制御・管理するクラスです。
 * プレイヤーの最終受け取り日時をデータベースでチェックし、
 * クールダウン時間が経過していれば自動的にお金を付与します。
 */
public class LoginBonusManager {

    /** プラグインのメインクラスのインスタンス */
    private final FJEconomy plugin;

    /**
     * LoginBonusManager を初期化するためのコンストラクタです。
     *
     * @param plugin FJEconomy プラグインのメインクラス
     */
    public LoginBonusManager(FJEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * プレイヤーがログインボーナスを受け取る資格があるか確認し、条件を満たしていれば付与します。
     * このメソッドは、プレイヤーがサーバーに参加した（Joinイベントが発生した）際に非同期で呼び出す必要があります。
     *
     * @param playerUUID ログインしたプレイヤーのUUID
     * @param playerName ログインしたプレイヤーの名前
     */
    public void checkAndGrantLoginBonus(UUID playerUUID, String playerName) {
        ConfigManager configManager = plugin.getConfigManager();
        DatabaseManager dbManager = plugin.getDatabaseManager();
        EconomyManager economyManager = plugin.getEconomyManager();

        if (!configManager.isLoginBonusEnabled()) {
            plugin.getLogger().info("ログインボーナスは現在無効に設定されています (config.yml)");
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

                    // メッセージ送信はメインスレッドで実行
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        Player player = Bukkit.getPlayer(playerUUID);
                        if (player != null && player.isOnline()) {
                            player.sendMessage("§aログインボーナスとして " + economyManager.formatMoney(configManager.getLoginBonusAmount()) + " を受け取りました！");
                        }
                    });
                    plugin.getLogger().info("プレイヤー " + playerName + " にログインボーナス " + configManager.getLoginBonusAmount() + " を付与しました。");
                } else {
                    plugin.getLogger().info("プレイヤー " + playerName + " は既にボーナス取得済みです。次回取得可能: " + nextClaimTime);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "ログインボーナス処理中にエラーが発生しました: " + playerName, e);
            }
        });
    }
}
