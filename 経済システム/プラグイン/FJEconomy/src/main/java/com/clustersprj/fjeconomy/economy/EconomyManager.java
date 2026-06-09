package com.clustersprj.fjeconomy.economy;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

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
     * Get player's balance
     */
    public long getBalance(UUID playerUUID) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT balance FROM fje_balances WHERE uuid = ?")) {
            stmt.setString(1, playerUUID.toString());
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getLong("balance");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Balance query error", e);
        }

        return 0;
    }

    /**
     * Set player's balance
     */
    public boolean setBalance(UUID playerUUID, String playerName, long amount) {
        if (!configManager.isNegativeBalanceAllowed() && amount < 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO fje_balances (uuid, player_name, balance) VALUES (?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE balance = ?, last_update = CURRENT_TIMESTAMP")) {
            stmt.setString(1, playerUUID.toString());
            stmt.setString(2, playerName);
            stmt.setLong(3, amount);
            stmt.setLong(4, amount);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Balance update error", e);
            return false;
        }
    }

    /**
     * Give money to player
     */
    public boolean giveMoney(UUID playerUUID, String playerName, long amount) {
        if (amount <= 0) return false;

        long currentBalance = getBalance(playerUUID);
        return setBalance(playerUUID, playerName, currentBalance + amount);
    }

    /**
     * Take money from player
     */
    public boolean takeMoney(UUID playerUUID, String playerName, long amount) {
        if (amount <= 0) return false;

        long currentBalance = getBalance(playerUUID);
        long newBalance = currentBalance - amount;

        if (newBalance < 0 && !configManager.isNegativeBalanceAllowed()) {
            return false;
        }

        return setBalance(playerUUID, playerName, newBalance);
    }

    /**
     * Send money between players
     */
    public boolean sendMoney(UUID senderUUID, String senderName, 
                            UUID receiverUUID, String receiverName, long amount) {
        if (amount <= 0) return false;

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // Check sender balance
                long senderBalance = getBalance(senderUUID);
                if (senderBalance < amount) {
                    conn.rollback();
                    return false;
                }

                // Deduct from sender
                if (!takeMoney(senderUUID, senderName, amount)) {
                    conn.rollback();
                    return false;
                }

                // Add to receiver
                if (!giveMoney(receiverUUID, receiverName, amount)) {
                    conn.rollback();
                    return false;
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
                // Check buyer balance
                long buyerBalance = getBalance(buyerUUID);
                if (buyerBalance < totalPrice) {
                    conn.rollback();
                    return false;
                }

                // Deduct from buyer
                if (!takeMoney(buyerUUID, buyerName, totalPrice)) {
                    conn.rollback();
                    return false;
                }

                // Add to shop owner
                if (!giveMoney(ownerUUID, ownerName, netProfit)) {
                    conn.rollback();
                    return false;
                }

                // Add tax to government
                if (!giveMoney(governmentUUID, governmentName, taxAmount)) {
                    conn.rollback();
                    return false;
                }

                // Record transaction
                String serverId = configManager.getServerId();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO fje_transactions " +
                        "(timestamp, server_id, buyer_uuid, owner_uuid, item_id, price_total, tax_amount, net_profit) " +
                        "VALUES (NOW(), ?, ?, ?, ?, ?, ?, ?)")) {
                    stmt.setString(1, serverId);
                    stmt.setString(2, buyerUUID.toString());
                    stmt.setString(3, ownerUUID.toString());
                    stmt.setString(4, itemMaterial);
                    stmt.setLong(5, totalPrice);
                    stmt.setLong(6, taxAmount);
                    stmt.setLong(7, netProfit);
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
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT IGNORE INTO fje_balances (uuid, player_name, balance) VALUES (?, ?, ?)")) {
            stmt.setString(1, playerUUID.toString());
            stmt.setString(2, playerName);
            stmt.setLong(3, configManager.getStartingBalance());
            stmt.executeUpdate();
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
