package com.clustersprj.fjeconomy.economy;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.UUID;
import java.util.logging.Level;
import java.util.List;
import java.util.ArrayList;


public class EconomyManager {

    private final FJEconomy plugin;
    private final DatabaseManager dbManager;
    private final ConfigManager configManager;

    public EconomyManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
        this.configManager = plugin.getConfigManager();
    }
    // EconomyManager に追加
    public UUID getPlayerUUIDByName(String playerName) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT uuid FROM fje_balances WHERE player_name = ?")) {
            stmt.setString(1, playerName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return UUID.fromString(rs.getString("uuid"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "UUID query error", e);
        }
        return null;
    }
    
    /**
     * UUID から DB に登録されている正式なプレイヤー名を取得します。
     * <p>
     * DB の照合順序は utf8mb4_unicode_ci（大文字小文字を区別しない）なので、
     * {@link #getPlayerUUIDByName(String)} は入力の大小を問わずヒットします。
     * その入力文字列をそのまま残高操作へ渡すと、{@code ON DUPLICATE KEY UPDATE
     * player_name = ?} で登録名が入力どおりに書き換わってしまうため、
     * コマンドの引数ではなくこのメソッドが返す正式名を使うこと。
     * </p>
     *
     * @param playerUUID 対象プレイヤーのUUID
     * @return 登録されているプレイヤー名。見つからない場合は null
     */
    public String getPlayerNameByUUID(UUID playerUUID) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT player_name FROM fje_balances WHERE uuid = ?")) {
            stmt.setString(1, playerUUID.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("player_name");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Player name query error", e);
        }
        return null;
    }

    // EconomyManager に追加
    public List<String> getAllPlayerNames() {
        List<String> names = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT DISTINCT player_name FROM fje_balances ORDER BY player_name");
            while (rs.next()) {
                names.add(rs.getString("player_name"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Get player names error", e);
        }
        return names;
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

        // ★ 先に同期
        syncPlayerAccount(conn, playerUUID, playerName);

        String sql = "UPDATE fje_balances SET balance = ?, player_name = ?, last_update = CURRENT_TIMESTAMP WHERE uuid = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, amount);
            stmt.setString(2, playerName);
            stmt.setString(3, playerUUID.toString());
            return stmt.executeUpdate() > 0;
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
     * Ensure player account exists and sync player name
     * Internal method: Connectionを受け取る
     */
    private void syncPlayerAccount(Connection conn, UUID playerUUID, String playerName) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO fje_balances (uuid, player_name, balance) VALUES (?, ?, ?) " +
                 "ON DUPLICATE KEY UPDATE player_name = ?, last_update = CURRENT_TIMESTAMP")) {
            stmt.setString(1, playerUUID.toString());
            stmt.setString(2, playerName);
            stmt.setLong(3, configManager.getStartingBalance());
            stmt.setString(4, playerName);
            stmt.executeUpdate();
        }
    }

    /**
     * Ensure player account exists（外部用、Connectionなし）
     */
    public void ensurePlayerAccount(UUID playerUUID, String playerName) {
        try (Connection conn = dbManager.getConnection()) {
            syncPlayerAccount(conn, playerUUID, playerName);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Player account sync error", e);
        }
    }

    public boolean giveMoney(Connection conn, UUID playerUUID, String playerName, long amount) throws SQLException {
        if (amount <= 0) return false;

        // ★ 必ずここで同期（ユーザー名更新も含む）
        syncPlayerAccount(conn, playerUUID, playerName);

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

        // ★ 必ずここで同期
        syncPlayerAccount(conn, playerUUID, playerName);

        String sql = "UPDATE fje_balances SET balance = balance - ?, player_name = ?, last_update = CURRENT_TIMESTAMP " +
                     "WHERE uuid = ? AND (balance >= ? OR ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, amount);
            stmt.setString(2, playerName);
            stmt.setString(3, playerUUID.toString());
            stmt.setLong(4, amount);
            stmt.setBoolean(5, configManager.isNegativeBalanceAllowed());

            int affectedRows = stmt.executeUpdate();
            return affectedRows > 0;
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
     * 販売価格に対する税額を計算します。
     * <p>
     * 金額は必ず整数で扱うため、double ではなく BigDecimal と
     * config の {@code economy.rounding_method} で整数に丸めます。
     * 「販売価格 = 税額 + 店主受取額」が常に一致するよう、
     * 税額を求めてから店主受取額を差分で出すこと。
     * </p>
     *
     * @param totalPrice 販売価格の合計
     * @return 税額
     */
    public long calculateTax(long totalPrice) {
        double taxRate = configManager.getTaxRate() / 100.0;
        RoundingMode rounding = RoundingMode.valueOf(configManager.getRoundingMethod());

        return BigDecimal.valueOf(totalPrice)
                .multiply(BigDecimal.valueOf(taxRate))
                .setScale(0, rounding)
                .longValue();
    }

    /**
     * Execute a shop purchase
     */
    public boolean processPurchase(UUID buyerUUID, String buyerName,
                                   UUID ownerUUID, String ownerName,
                                   String itemMaterial, int quantity, long unitPrice) {
        if (quantity <= 0 || unitPrice < 0) return false;

        long totalPrice = unitPrice * quantity;
        long taxAmount = calculateTax(totalPrice);
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
                // 税率100%などで netProfit が 0 になる場合、giveMoney は amount <= 0 を
                // 弾いて false を返すため、口座の同期だけ行って加算はスキップする。
                if (netProfit > 0) {
                    if (!giveMoney(conn, ownerUUID, ownerName, netProfit)) {
                        conn.rollback();
                        return false;
                    }
                } else {
                    // トランザクション内なので、別コネクションを取る公開版ではなく
                    // conn を引き回す内部版を使う
                    syncPlayerAccount(conn, ownerUUID, ownerName);
                }

                // Add tax to government
                // 少額商品や tax_rate: 0 では taxAmount が 0 に丸められる。
                // ここで giveMoney を呼ぶと false が返り、購入全体がロールバックされて
                // 「買えないのにエラーも出ない」状態になっていたためスキップする。
                if (taxAmount > 0) {
                    if (!giveMoney(conn, governmentUUID, governmentName, taxAmount)) {
                        conn.rollback();
                        return false;
                    }
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
     * 取引履歴を fje_transactions に記録する
     * (村人ショップ購入・processPurchase 双方から共通で呼び出す用)
     */
    public boolean recordTransaction(UUID buyerUUID, UUID ownerUUID, String itemId,
                                     int quantity, long priceTotal, long taxAmount, long netProfit) {
        String serverId = configManager.getServerId();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO fje_transactions " +
                     "(timestamp, server_id, buyer_uuid, owner_uuid, item_id, amount, price_total, tax_amount, net_profit) " +
                     "VALUES (NOW(), ?, ?, ?, ?, ?, ?, ?, ?)")) {
            stmt.setString(1, serverId);
            stmt.setString(2, buyerUUID.toString());
            stmt.setString(3, ownerUUID.toString());
            stmt.setString(4, itemId);
            stmt.setInt(5, quantity);
            stmt.setLong(6, priceTotal);
            stmt.setLong(7, taxAmount);
            stmt.setLong(8, netProfit);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Transaction record error", e);
            return false;
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