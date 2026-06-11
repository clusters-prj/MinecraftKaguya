package com.clustersprj.fjeconomy.economy;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.UUID;
import java.util.logging.Level;

public class EconomyManager {

    private final FJEconomy plugin;
    private final DatabaseManager dbManager;
    private final ConfigManager configManager;

    public EconomyManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
        this.configManager = plugin.getConfigManager();
    }

    /**
     * Get player's balance (既存のコネクションを使用)
     */
    public long getBalance(Connection conn, UUID playerUUID) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT balance FROM fje_balances WHERE uuid = ?")) {
            stmt.setString(1, playerUUID.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("balance");
                }
            }
        }
        return 0;
    }

    /**
     * Get player's balance
     */
    public long getBalance(UUID playerUUID) {
        try (Connection conn = dbManager.getConnection()) {
            return getBalance(conn, playerUUID);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Balance query error", e);
        }
        return 0;
    }

    /**
     * Set player's balance (既存のコネクションを使用)
     */
    public boolean setBalance(Connection conn, UUID playerUUID, String playerName, long amount) throws SQLException {
        if (!configManager.isNegativeBalanceAllowed() && amount < 0) {
            return false;
        }

        try (PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO fje_balances (uuid, player_name, balance) VALUES (?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE balance = ?, player_name = ?, last_update = CURRENT_TIMESTAMP")) {
            stmt.setString(1, playerUUID.toString());
            stmt.setString(2, playerName);
            stmt.setLong(3, amount);
            stmt.setLong(4, amount);
            stmt.setString(5, playerName); // 名前が変わっていた場合への対策
            stmt.executeUpdate();
            return true;
        }
    }

    /**
     * Set player's balance
     */
    public boolean setBalance(UUID playerUUID, String playerName, long amount) {
        try (Connection conn = dbManager.getConnection()) {
            return setBalance(conn, playerUUID, playerName, amount);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Balance update error", e);
            return false;
        }
    }

    /**
     * トランザクション内でプレイヤーのアカウント存在を保証する内部メソッド
     * （Connectionを受け取る）
     */
    private void ensurePlayerAccount(Connection conn, UUID playerUUID, String playerName) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO fje_balances (uuid, player_name, balance) VALUES (?, ?, ?) " +
                 "ON DUPLICATE KEY UPDATE player_name = ?, last_update = CURRENT_TIMESTAMP")) {
            stmt.setString(1, playerUUID.toString());
            stmt.setString(2, playerName);
            stmt.setLong(3, configManager.getStartingBalance());
            stmt.setString(4, playerName); // すでに存在していても名前が最新なら更新
            stmt.executeUpdate();
        }
    }

    /**
     * Give money to player (既存のコネクションを使用)
     */
    public boolean giveMoney(Connection conn, UUID playerUUID, String playerName, long amount) throws SQLException {
        if (amount <= 0) return false;

        // アカウントの存在を保証してから直接SQLで加算（競合防止）
        ensurePlayerAccount(conn, playerUUID, playerName);

        String sql = "UPDATE fje_balances SET balance = balance + ?, player_name = ?, last_update = CURRENT_TIMESTAMP WHERE uuid = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, amount);
            stmt.setString(2, playerName);
            stmt.setString(3, playerUUID.toString());
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * Give money to player
     */
    public boolean giveMoney(UUID playerUUID, String playerName, long amount) {
        try (Connection conn = dbManager.getConnection()) {
            return giveMoney(conn, playerUUID, playerName, amount);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Give money error", e);
            return false;
        }
    }

    /**
     * Take money from player (既存のコネクションを使用)
     */
    public boolean takeMoney(Connection conn, UUID playerUUID, String playerName, long amount) throws SQLException {
        if (amount <= 0) return false;

        // アカウントの存在を保証
        ensurePlayerAccount(conn, playerUUID, playerName);

        // SQLで直接引き算。WHERE句で残高チェックを同時に行うことで競合を完全に防ぐ
        String sql = "UPDATE fje_balances SET balance = balance - ?, player_name = ?, last_update = CURRENT_TIMESTAMP " +
                     "WHERE uuid = ? AND (balance >= ? OR ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, amount);
            stmt.setString(2, playerName);
            stmt.setString(3, playerUUID.toString());
            stmt.setLong(4, amount); // 必要残高
            stmt.setBoolean(5, configManager.isNegativeBalanceAllowed()); // マイナス許容設定

            int affectedRows = stmt.executeUpdate();
            return affectedRows > 0; // 条件を満たして更新できたらtrue
        }
    }

    /**
     * Take money from player
     */
    public boolean takeMoney(UUID playerUUID, String playerName, long amount) {
        try (Connection conn = dbManager.getConnection()) {
            return takeMoney(conn, playerUUID, playerName, amount);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Take money error", e);
            return false;
        }
    }

    /**
     * Send money between players
     */
    public boolean sendMoney(UUID senderUUID, String senderName, 
                            UUID receiverUUID, String receiverName, long amount) {
        if (amount <= 0) return false;
        if (senderUUID.equals(receiverUUID)) return false; // 同一プレイヤー間の送金は弾く

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // デッドロック対策：UUIDの文字列比較で、ロックを取得する順番を常に一定に固定する
                boolean senderFirst = senderUUID.toString().compareTo(receiverUUID.toString()) < 0;

                if (senderFirst) {
                    if (!takeMoney(conn, senderUUID, senderName, amount)) { conn.rollback(); return false; }
                    if (!giveMoney(conn, receiverUUID, receiverName, amount)) { conn.rollback(); return false; }
                } else {
                    if (!giveMoney(conn, receiverUUID, receiverName, amount)) { conn.rollback(); return false; }
                    if (!takeMoney(conn, senderUUID, senderName, amount)) { conn.rollback(); return false; }
                }

                // Record transaction
                String serverId = configManager.getServerId();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO fje_transactions " +
                        "(timestamp, server_id, buyer_uuid, owner_uuid, item_id, amount, price_total, tax_amount, net_profit) " +
                        "VALUES (NOW(), ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    stmt.setString(1, serverId);
                    stmt.setString(2, senderUUID.toString());
                    stmt.setString(3, receiverUUID.toString());
                    stmt.setString(4, "PAY");
                    stmt.setInt(5, 1);
                    stmt.setLong(6, amount);
                    stmt.setLong(7, 0);
                    stmt.setLong(8, amount);
                    stmt.executeUpdate();
                }

                conn.commit();
                return true;

            } catch (Exception e) {
                conn.rollback();
                plugin.getLogger().log(Level.WARNING, "Transaction error", e);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Money transfer error", e);
            return false;
        }
    }



    /**
     * Execute a shop purchase
     */
    public boolean processPurchase(UUID buyerUUID, String buyerName,
                                   UUID ownerUUID, String ownerName,
                                   String itemMaterial, int quantity, long unitPrice) {
        if (quantity <= 0 || unitPrice < 0) return false;

        long totalPrice = unitPrice * quantity;
        double taxRate = configManager.getTaxRate() / 100.0;
        RoundingMode rounding = RoundingMode.valueOf(configManager.getRoundingMethod());

        // Calculate tax
        BigDecimal taxDecimal = BigDecimal.valueOf(totalPrice)
                .multiply(BigDecimal.valueOf(taxRate))
                .setScale(0, rounding);
        long taxAmount = taxDecimal.longValue();

        long netProfit = totalPrice - taxAmount;

        // Government UUID
        UUID governmentUUID = UUID.fromString(configManager.getGovernmentUUID());
        String governmentName = configManager.getGovernmentName();

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // 【変更点】事前に getBalance する必要はなし！
                // takeMoney が自動で残高不足を判定して弾いてくれる
                if (!takeMoney(conn, buyerUUID, buyerName, totalPrice)) {
                    conn.rollback();
                    return false;
                }

                // Add to shop owner
                if (!giveMoney(conn, ownerUUID, ownerName, netProfit)) {
                    conn.rollback();
                    return false;
                }

                // Add tax to government
                if (!giveMoney(conn, governmentUUID, governmentName, taxAmount)) {
                    conn.rollback();
                    return false;
                }

                // Record transaction
                String serverId = configManager.getServerId();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO fje_transactions " +
                        "(timestamp, server_id, buyer_uuid, owner_uuid, item_id, amount, price_total, tax_amount, net_profit) " +
                        "VALUES (NOW(), ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    stmt.setString(1, serverId);
                    stmt.setString(2, buyerUUID.toString());
                    stmt.setString(3, ownerUUID.toString());
                    stmt.setString(4, itemMaterial);
                    stmt.setInt(5, quantity);
                    stmt.setLong(6, totalPrice);
                    stmt.setLong(7, taxAmount);
                    stmt.setLong(8, netProfit);
                    stmt.executeUpdate();
                }

                // Record government ledger
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO fje_government_ledger (timestamp, type, amount, description) " +
                        "VALUES (NOW(), ?, ?, ?)")) {
                    stmt.setString(1, "TAX_IN");
                    stmt.setLong(2, taxAmount);
                    stmt.setString(3, itemMaterial + " x" + quantity + " from " + buyerName);
                    stmt.executeUpdate();
                }

                conn.commit();
                return true;

            } catch (Exception e) {
                conn.rollback();
                plugin.getLogger().log(Level.WARNING, "Purchase error", e);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Purchase transaction error", e);
            return false;
        }
    }

    /**
     * Ensure player account exists
     */
    public void ensurePlayerAccount(UUID playerUUID, String playerName) {
        try (Connection conn = dbManager.getConnection()) {
            // 上で新設した Connectionを受け取るメソッドに丸投げする
            ensurePlayerAccount(conn, playerUUID, playerName);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Player account creation error", e);
        }
    }

    /**
     * Format currency string
     */
    public String formatMoney(long amount) {
        return configManager.getCurrencySymbol() + amount;
    }

    /**
     * Get government balance
     */
    public long getGovernmentBalance() {
        UUID govUUID = UUID.fromString(configManager.getGovernmentUUID());
        return getBalance(govUUID);
    }
}
