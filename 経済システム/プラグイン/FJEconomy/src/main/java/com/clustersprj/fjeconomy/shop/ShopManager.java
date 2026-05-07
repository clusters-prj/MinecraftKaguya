package com.clustersprj.fjeconomy.shop;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import org.bukkit.Material;
import org.bukkit.entity.Entity;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class ShopManager {

    private final FJEconomy plugin;
    private final DatabaseManager dbManager;
    private final ConfigManager configManager;

    public ShopManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
        this.configManager = plugin.getConfigManager();
    }

    /**
     * Create a new shop
     */
    public boolean createShop(int npcId, String serverId, UUID ownerUUID, String itemMaterial, 
                             int price, int stock) {
        return createShop(npcId, serverId, ownerUUID, itemMaterial, null, price, stock);
    }

    /**
     * Create a new shop with NBT data
     */
    public boolean createShop(int npcId, String serverId, UUID ownerUUID, String itemMaterial,
                             String itemNBT, int price, int stock) {
        if (price < 0 || stock < 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO fje_shops (npc_id, server_id, owner_uuid, item_material, item_nbt, price, stock) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE owner_uuid = ?, item_material = ?, item_nbt = ?, price = ?, stock = ?")) {

            stmt.setInt(1, npcId);
            stmt.setString(2, serverId);
            stmt.setString(3, ownerUUID.toString());
            stmt.setString(4, itemMaterial);
            stmt.setString(5, itemNBT);
            stmt.setInt(6, price);
            stmt.setInt(7, stock);

            // ON DUPLICATE KEY UPDATE 用
            stmt.setString(8, ownerUUID.toString());
            stmt.setString(9, itemMaterial);
            stmt.setString(10, itemNBT);
            stmt.setInt(11, price);
            stmt.setInt(12, stock);

            stmt.executeUpdate();
            return true;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Shop creation error", e);
            return false;
        }
    }

    /**
     * Get shop information
     */
    public Shop getShop(int npcId, String serverId) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT npc_id, server_id, owner_uuid, item_material, item_nbt, price, stock " +
                     "FROM fje_shops WHERE npc_id = ? AND server_id = ?")) {

            stmt.setInt(1, npcId);
            stmt.setString(2, serverId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return new Shop(
                        rs.getInt("npc_id"),
                        rs.getString("server_id"),
                        UUID.fromString(rs.getString("owner_uuid")),
                        rs.getString("item_material"),
                        rs.getString("item_nbt"),
                        rs.getInt("price"),
                        rs.getInt("stock")
                );
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Shop query error", e);
        }

        return null;
    }

    /**
     * Get all shops by owner
     */
    public List<Shop> getShopsByOwner(UUID ownerUUID, String serverId) {
        List<Shop> shops = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT npc_id, server_id, owner_uuid, item_material, item_nbt, price, stock " +
                     "FROM fje_shops WHERE owner_uuid = ? AND server_id = ? ORDER BY npc_id")) {

            stmt.setString(1, ownerUUID.toString());
            stmt.setString(2, serverId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                shops.add(new Shop(
                        rs.getInt("npc_id"),
                        rs.getString("server_id"),
                        UUID.fromString(rs.getString("owner_uuid")),
                        rs.getString("item_material"),
                        rs.getString("item_nbt"),
                        rs.getInt("price"),
                        rs.getInt("stock")
                ));
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Shops query error", e);
        }

        return shops;
    }

    /**
     * Update shop price
     */
    public boolean updateShopPrice(int npcId, String serverId, int newPrice) {
        if (newPrice < 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE fje_shops SET price = ? WHERE npc_id = ? AND server_id = ?")) {

            stmt.setInt(1, newPrice);
            stmt.setInt(2, npcId);
            stmt.setString(3, serverId);

            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Shop price update error", e);
            return false;
        }
    }

    /**
     * Update shop stock
     */
    public boolean updateShopStock(int npcId, String serverId, int newStock) {
        if (newStock < 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE fje_shops SET stock = ? WHERE npc_id = ? AND server_id = ?")) {

            stmt.setInt(1, newStock);
            stmt.setInt(2, npcId);
            stmt.setString(3, serverId);

            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Shop stock update error", e);
            return false;
        }
    }

    /**
     * Add stock to shop
     */
    public boolean addStock(int npcId, String serverId, int quantity) {
        if (quantity <= 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE fje_shops SET stock = stock + ? WHERE npc_id = ? AND server_id = ?")) {

            stmt.setInt(1, quantity);
            stmt.setInt(2, npcId);
            stmt.setString(3, serverId);

            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Stock add error", e);
            return false;
        }
    }

    /**
     * Remove stock from shop
     */
    public boolean removeStock(int npcId, String serverId, int quantity) {
        if (quantity <= 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE fje_shops SET stock = GREATEST(0, stock - ?) WHERE npc_id = ? AND server_id = ?")) {

            stmt.setInt(1, quantity);
            stmt.setInt(2, npcId);
            stmt.setString(3, serverId);

            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Stock remove error", e);
            return false;
        }
    }

    /**
     * Delete a shop
     */
    public boolean deleteShop(int npcId, String serverId) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM fje_shops WHERE npc_id = ? AND server_id = ?")) {

            stmt.setInt(1, npcId);
            stmt.setString(2, serverId);

            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Shop delete error", e);
            return false;
        }
    }

    /**
     * Check if shop exists
     */
    public boolean shopExists(int npcId, String serverId) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT 1 FROM fje_shops WHERE npc_id = ? AND server_id = ? LIMIT 1")) {

            stmt.setInt(1, npcId);
            stmt.setString(2, serverId);
            ResultSet rs = stmt.executeQuery();

            return rs.next();

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Shop exists check error", e);
            return false;
        }
    }

    /**
     * Get shop by entity
     */
    public Shop getShopByEntity(Entity entity, String serverId) {
        int entityId = entity.getEntityId();
        return getShop(entityId, serverId);
    }

    /**
     * Shop data class
     */
    public static class Shop {
        private final int npcId;
        private final String serverId;
        private final UUID ownerUUID;
        private final String itemMaterial;
        private final String itemNBT;
        private final int price;
        private final int stock;

        public Shop(int npcId, String serverId, UUID ownerUUID, String itemMaterial,
                   String itemNBT, int price, int stock) {
            this.npcId = npcId;
            this.serverId = serverId;
            this.ownerUUID = ownerUUID;
            this.itemMaterial = itemMaterial;
            this.itemNBT = itemNBT;
            this.price = price;
            this.stock = stock;
        }

        // Getters
        public int getNpcId() { return npcId; }
        public String getServerId() { return serverId; }
        public UUID getOwnerUUID() { return ownerUUID; }
        public String getItemMaterial() { return itemMaterial; }
        public String getItemNBT() { return itemNBT; }
        public int getPrice() { return price; }
        public int getStock() { return stock; }

        /**
         * Check if item is in stock
         */
        public boolean hasStock(int quantity) {
            return stock >= quantity;
        }

        /**
         * Get shop display info
         */
        public String getDisplayInfo(String currencySymbol) {
            return String.format("%s: %s%d (在庫: %d個)", 
                    itemMaterial, currencySymbol, price, stock);
        }
    }
}
