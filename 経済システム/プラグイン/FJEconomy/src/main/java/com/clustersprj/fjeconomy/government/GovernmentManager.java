package com.clustersprj.fjeconomy.government;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import com.clustersprj.fjeconomy.economy.EconomyManager;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * 政府アカウント管理と税金システムの統括
 * 
 * 責務：
 * - 政府アカウント（特殊なプレイヤーアカウント）の管理
 * - 税金の自動集計・管理
 * - 政府台帳（fje_government_ledger）の記録・クエリ
 * - 政府資金の配分機能（将来的な給付金・公共工事など）
 */
public class GovernmentManager {

    private final FJEconomy plugin;
    private final DatabaseManager dbManager;
    private final ConfigManager configManager;
    private final EconomyManager economyManager;

    private UUID governmentUUID;
    private String governmentName;

    public GovernmentManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
        this.configManager = plugin.getConfigManager();
        this.economyManager = new EconomyManager(plugin);

        this.governmentUUID = UUID.fromString(configManager.getGovernmentUUID());
        this.governmentName = configManager.getGovernmentName();
    }

    /**
     * 初期化：政府アカウントを自動作成（初回起動時）
     */
    public void initialize() {
        try {
            economyManager.ensurePlayerAccount(governmentUUID, governmentName);
            plugin.getLogger().info("✓ 政府アカウントを初期化しました: " + governmentName);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "政府アカウント初期化エラー", e);
        }
    }

    /**
     * 政府アカウントの残高を取得
     */
    public long getGovernmentBalance() {
        return economyManager.getBalance(governmentUUID);
    }

    /**
     * 政府残高を直接設定（リセット用）
     */
    public boolean setGovernmentBalance(long amount) {
        return economyManager.setBalance(governmentUUID, governmentName, amount);
    }

    /**
     * 政府アカウントに資金を追加
     */
    public boolean addGovernmentFunds(long amount, String reason) {
        if (amount <= 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // 資金追加
                economyManager.giveMoney(conn, governmentUUID, governmentName, amount);

                // 台帳に記録
                recordLedger(conn, "FUND_ADD", amount, reason);

                conn.commit();
                plugin.getLogger().info("政府資金を追加: " + amount + " - " + reason);
                return true;

            } catch (Exception e) {
                conn.rollback();
                plugin.getLogger().log(Level.WARNING, "政府資金追加エラー", e);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "政府資金追加時のDB接続エラー", e);
            return false;
        }
    }

    /**
     * 政府アカウントから資金を引き出す（給付金・配分用）
     */
    public boolean withdrawGovernmentFunds(long amount, String reason) {
        if (amount <= 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // 資金引き出し
                if (!economyManager.takeMoney(conn, governmentUUID, governmentName, amount)) {
                    conn.rollback();
                    return false;
                }

                // 台帳に記録
                recordLedger(conn, "FUND_WITHDRAW", amount, reason);

                conn.commit();
                plugin.getLogger().info("政府資金を引き出し: " + amount + " - " + reason);
                return true;

            } catch (Exception e) {
                conn.rollback();
                plugin.getLogger().log(Level.WARNING, "政府資金引き出しエラー", e);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "政府資金引き出し時のDB接続エラー", e);
            return false;
        }
    }

    /**
     * 政府台帳に記録（内部用）
     */
    private void recordLedger(Connection conn, String type, long amount, String description) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO fje_government_ledger (timestamp, type, amount, description) " +
                "VALUES (NOW(), ?, ?, ?)")) {
            stmt.setString(1, type);
            stmt.setLong(2, amount);
            stmt.setString(3, description);
            stmt.executeUpdate();
        }
    }

    /**
     * 税金の記録
     */
    public void recordTax(Connection conn, long taxAmount, String itemDescription) throws SQLException {
        recordLedger(conn, "TAX_IN", taxAmount, "Sale: " + itemDescription);
    }

    /**
     * 指定期間の税金総額を取得
     */
    public long getTaxIncome(long minutesAgo) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COALESCE(SUM(amount), 0) as total FROM fje_government_ledger " +
                     "WHERE type = 'TAX_IN' AND timestamp >= DATE_SUB(NOW(), INTERVAL ? MINUTE)")) {

            stmt.setLong(1, minutesAgo);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getLong("total");
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Tax income query error", e);
        }

        return 0;
    }

    /**
     * 本日の税金総額
     */
    public long getTodayTaxIncome() {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COALESCE(SUM(amount), 0) as total FROM fje_government_ledger " +
                     "WHERE type = 'TAX_IN' AND DATE(timestamp) = CURDATE()")) {

            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getLong("total");
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Today tax income query error", e);
        }

        return 0;
    }

    /**
     * 政府台帳の履歴を取得
     */
    public List<LedgerEntry> getLedgerHistory(int limit) {
        List<LedgerEntry> entries = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, timestamp, type, amount, description FROM fje_government_ledger " +
                     "ORDER BY timestamp DESC LIMIT ?")) {

            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                entries.add(new LedgerEntry(
                        rs.getInt("id"),
                        rs.getTimestamp("timestamp"),
                        rs.getString("type"),
                        rs.getLong("amount"),
                        rs.getString("description")
                ));
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Ledger history query error", e);
        }

        return entries;
    }

    /**
     * 指定タイプの台帳項目を取得
     */
    public List<LedgerEntry> getLedgerByType(String type, int limit) {
        List<LedgerEntry> entries = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, timestamp, type, amount, description FROM fje_government_ledger " +
                     "WHERE type = ? ORDER BY timestamp DESC LIMIT ?")) {

            stmt.setString(1, type);
            stmt.setInt(2, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                entries.add(new LedgerEntry(
                        rs.getInt("id"),
                        rs.getTimestamp("timestamp"),
                        rs.getString("type"),
                        rs.getLong("amount"),
                        rs.getString("description")
                ));
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Ledger type query error", e);
        }

        return entries;
    }

    /**
     * 政府資金の配分（プレイヤーに給付金を送金）
     * 
     * @param recipientUUID 受取人
     * @param recipientName 受取人名
     * @param amount 給付額
     * @param reason 理由
     * @return 成功したら true
     */
    public boolean distributeGovernmentFunds(UUID recipientUUID, String recipientName, 
                                            long amount, String reason) {
        if (amount <= 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // アカウント存在確認
                economyManager.ensurePlayerAccount(conn, recipientUUID, recipientName);

                // 政府から引き出し
                if (!economyManager.takeMoney(conn, governmentUUID, governmentName, amount)) {
                    conn.rollback();
                    return false;
                }

                // プレイヤーに給付
                if (!economyManager.giveMoney(conn, recipientUUID, recipientName, amount)) {
                    conn.rollback();
                    return false;
                }

                // 台帳に記録
                recordLedger(conn, "FUND_DISTRIBUTE", amount, 
                           "To: " + recipientName + " - " + reason);

                conn.commit();
                plugin.getLogger().info("政府資金配分: " + recipientName + " に " + amount + 
                                      " (" + reason + ")");
                return true;

            } catch (Exception e) {
                conn.rollback();
                plugin.getLogger().log(Level.WARNING, "Government distribution error", e);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Distribution transaction error", e);
            return false;
        }
    }

    /**
     * 政府アカウント情報
     */
    public UUID getGovernmentUUID() {
        return governmentUUID;
    }

    public String getGovernmentName() {
        return governmentName;
    }

    /**
     * 政府台帳のエントリ
     */
    public static class LedgerEntry {
        private final int id;
        private final Timestamp timestamp;
        private final String type;
        private final long amount;
        private final String description;

        public LedgerEntry(int id, Timestamp timestamp, String type, long amount, String description) {
            this.id = id;
            this.timestamp = timestamp;
            this.type = type;
            this.amount = amount;
            this.description = description;
        }

        public int getId() { return id; }
        public Timestamp getTimestamp() { return timestamp; }
        public String getType() { return type; }
        public long getAmount() { return amount; }
        public String getDescription() { return description; }

        @Override
        public String toString() {
            return String.format("[%s] %s: %d - %s", timestamp, type, amount, description);
        }
    }
}
