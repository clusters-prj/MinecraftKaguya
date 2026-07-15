package com.clustersprj.fjeconomy.shop;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import org.bukkit.Material;
import org.bukkit.entity.Entity;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * データベースを介したショップ情報の永続化、および作成、更新、在庫調整などのデータ操作を管理するクラスです[span_24](start_span)[span_24](end_span)。
 */
public class ShopManager {

    /** プラグインのメインクラスのインスタンス */
    private final FJEconomy plugin;

    /** データベース接続を制御するマネージャー */
    private final DatabaseManager dbManager;

    /** 設定ファイルを制御するマネージャー */
    private final ConfigManager configManager;

    /**
     * ShopManager を初期化するためのコンストラクタです[span_25](start_span)[span_25](end_span)。
     *
     * @param plugin FJEconomy プラグインのメインクラス[span_26](start_span)[span_26](end_span)
     */
    public ShopManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
        this.configManager = plugin.getConfigManager();
    }

    /**
     * NBTデータなしで新規ショップを作成、または既存のショップデータを更新します[span_27](start_span)[span_27](end_span)。
     *
     * @param npcUuid      ショップに紐づくNPCのUUID[span_28](start_span)[span_28](end_span)
     * @param serverId     ショップを設置するサーバーのID[span_29](start_span)[span_29](end_span)
     * @param ownerUUID    ショップのオーナープレイヤーのUUID[span_30](start_span)[span_30](end_span)
     * @param itemMaterial 販売するアイテムのMinecraftマテリアル名[span_31](start_span)[span_31](end_span)
     * @param price        販売価格[span_32](start_span)[span_32](end_span)
     * @param stock        初期在庫数[span_33](start_span)[span_33](end_span)
     * @return データベース処理が成功した場合は true、失敗した場合は false[span_34](start_span)[span_34](end_span)
     */
    public boolean createShop(UUID npcUuid, String serverId, UUID ownerUUID, String itemMaterial, 
                             int price, int stock) {
        return createShop(npcUuid, serverId, ownerUUID, itemMaterial, null, price, stock);
    }

    /**
     * NBTデータありで新規ショップを作成、または既存のショップデータを更新します[span_35](start_span)[span_35](end_span)。
     *
     * @param npcUuid      ショップに紐づくNPCのUUID[span_36](start_span)[span_36](end_span)
     * @param serverId     ショップを設置するサーバーのID[span_37](start_span)[span_37](end_span)
     * @param ownerUUID    ショップのオーナープレイヤーのUUID[span_38](start_span)[span_38](end_span)
     * @param itemMaterial 販売するアイテムのMinecraftマテリアル名[span_39](start_span)[span_39](end_span)
     * @param itemNBT      アイテムに付属するNBTデータ（JSON/String形式など）[span_40](start_span)[span_40](end_span)
     * @param price        販売価格[span_41](start_span)[span_41](end_span)
     * @param stock        初期在庫数[span_42](start_span)[span_42](end_span)
     * @return データベース処理が成功した場合は true、失敗した場合は false[span_43](start_span)[span_43](end_span)
     */
    public boolean createShop(UUID npcUuid, String serverId, UUID ownerUUID, String itemMaterial,
                             String itemNBT, int price, int stock) {
        if (price < 0 || stock < 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO fje_shops (npc_uuid, server_id, owner_uuid, item_material, item_nbt, price, stock) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE owner_uuid = ?, item_material = ?, item_nbt = ?, price = ?, stock = ?")) {

            stmt.setString(1, npcUuid.toString());
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
     * NPCのUUIDとサーバーIDに合致するショップ情報を取得します[span_44](start_span)[span_44](end_span)。
     *
     * @param npcUuid  ショップを識別するNPCのUUID[span_45](start_span)[span_45](end_span)
     * @param serverId 取得対象のサーバーID[span_46](start_span)[span_46](end_span)
     * @return 該当するショップデータが存在する場合は {@link Shop} インスタンス、なければ null[span_47](start_span)[span_47](end_span)
     */
    public Shop getShop(UUID npcUuid, String serverId) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT npc_uuid, server_id, owner_uuid, item_material, item_nbt, price, stock " +
                     "FROM fje_shops WHERE npc_uuid = ? AND server_id = ?")) {

            stmt.setString(1, npcUuid.toString());
            stmt.setString(2, serverId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return new Shop(
                        UUID.fromString(rs.getString("npc_uuid")),
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
     * 指定されたオーナー（プレイヤー）が特定のサーバーに展開しているすべてのショップ一覧を取得します[span_48](start_span)[span_48](end_span)。
     *
     * @param ownerUUID オーナーのプレイヤーUUID[span_49](start_span)[span_49](end_span)
     * @param serverId  取得対象のサーバーID[span_50](start_span)[span_50](end_span)
     * @return 所有しているショップの一覧（List形式、データがない場合は空のList）[span_51](start_span)[span_51](end_span)
     */
    public List<Shop> getShopsByOwner(UUID ownerUUID, String serverId) {
        List<Shop> shops = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT npc_uuid, server_id, owner_uuid, item_material, item_nbt, price, stock " +
                     "FROM fje_shops WHERE owner_uuid = ? AND server_id = ? ORDER BY npc_uuid")) {

            stmt.setString(1, ownerUUID.toString());
            stmt.setString(2, serverId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                shops.add(new Shop(
                        UUID.fromString(rs.getString("npc_uuid")),
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
     * ショップの販売価格を新価格に更新します[span_52](start_span)[span_52](end_span)。
     *
     * @param npcUuid  価格を更新する対象のNPCのUUID[span_53](start_span)[span_53](end_span)
     * @param serverId 対象のサーバーID[span_54](start_span)[span_54](end_span)
     * @param newPrice 設定する新しい価格[span_55](start_span)[span_55](end_span)
     * @return 更新処理が正常に完了した場合は true、失敗した（または引数が不正な）場合は false[span_56](start_span)[span_56](end_span)
     */
    public boolean updateShopPrice(UUID npcUuid, String serverId, int newPrice) {
        if (newPrice < 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE fje_shops SET price = ? WHERE npc_uuid = ? AND server_id = ?")) {

            stmt.setInt(1, newPrice);
            stmt.setString(2, npcUuid.toString());
            stmt.setString(3, serverId);

            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Shop price update error", e);
            return false;
        }
    }

    /**
     * ショップの在庫数を指定した新しい在庫数に上書き更新します[span_57](start_span)[span_57](end_span)。
     *
     * @param npcUuid  在庫を変更する対象のNPCのUUID[span_58](start_span)[span_58](end_span)
     * @param serverId 対象のサーバーID[span_59](start_span)[span_59](end_span)
     * @param newStock 設定する新しい在庫数[span_60](start_span)[span_60](end_span)
     * @return 更新処理が正常に完了した場合は true、失敗した（または値が負の）場合は false[span_61](start_span)[span_61](end_span)
     */
    public boolean updateShopStock(UUID npcUuid, String serverId, int newStock) {
        if (newStock < 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE fje_shops SET stock = ? WHERE npc_uuid = ? AND server_id = ?")) {

            stmt.setInt(1, newStock);
            stmt.setString(2, npcUuid.toString());
            stmt.setString(3, serverId);

            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Shop stock update error", e);
            return false;
        }
    }

    /**
     * 既存のショップに在庫を追加（加算）します[span_62](start_span)[span_62](end_span)。
     *
     * @param npcUuid  在庫を追加する対象のNPCのUUID[span_63](start_span)[span_63](end_span)
     * @param serverId 対象のサーバーID[span_64](start_span)[span_64](end_span)
     * @param quantity 加算する在庫数（1以上である必要があります）[span_65](start_span)[span_65](end_span)
     * @return 在庫の追加が成功した場合は true、失敗した場合は false[span_66](start_span)[span_66](end_span)
     */
    public boolean addStock(UUID npcUuid, String serverId, int quantity) {
        if (quantity <= 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE fje_shops SET stock = stock + ? WHERE npc_uuid = ? AND server_id = ?")) {

            stmt.setInt(1, quantity);
            stmt.setString(2, npcUuid.toString());
            stmt.setString(3, serverId);

            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Stock add error", e);
            return false;
        }
    }

    /**
     * 既存のショップから在庫を減少（減算）させます[span_67](start_span)[span_67](end_span)。
     * 在庫の合計値が要求された数量を下回らないよう、安全機能（SQL内の {@code stock >= quantity} 制限）が備わっています[span_68](start_span)[span_68](end_span)。
     *
     * @param npcUuid  在庫を減らす対象のNPCのUUID[span_69](start_span)[span_69](end_span)
     * @param serverId 対象のサーバーID[span_70](start_span)[span_70](end_span)
     * @param quantity 減算する在庫数（1以上である必要があります）[span_71](start_span)[span_71](end_span)
     * @return 正常に在庫が引かれた場合は true、在庫不足またはDBエラーにより引き落とせなかった場合は false[span_72](start_span)[span_72](end_span)
     */
    public boolean removeStock(UUID npcUuid, String serverId, int quantity) {
        if (quantity <= 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE fje_shops SET stock = stock - ? WHERE npc_uuid = ? AND server_id = ? AND stock >= ?")) {

            stmt.setInt(1, quantity);
            stmt.setString(2, npcUuid.toString());
            stmt.setString(3, serverId);
            stmt.setInt(4, quantity); // 在庫が足りている場合のみ減らす

            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Stock remove error", e);
            return false;
        }
    }

    /**
     * 指定されたNPCに関連づけられたショップデータを完全に削除します[span_73](start_span)[span_73](end_span)。
     *
     * @param npcUuid  削除対象のNPCのUUID[span_74](start_span)[span_74](end_span)
     * @param serverId 対象のサーバーID[span_75](start_span)[span_75](end_span)
     * @return 正常に削除された場合は true、存在しないかエラーが発生した場合は false[span_76](start_span)[span_76](end_span)
     */
    public boolean deleteShop(UUID npcUuid, String serverId) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM fje_shops WHERE npc_uuid = ? AND server_id = ?")) {

            stmt.setString(1, npcUuid.toString());
            stmt.setString(2, serverId);

            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Shop delete error", e);
            return false;
        }
    }

    /**
     * 指定されたNPCが、対象サーバーでショップとして登録されているか存在チェックを行います[span_77](start_span)[span_77](end_span)。
     *
     * @param npcUuid  調査対象のNPCのUUID[span_78](start_span)[span_78](end_span)
     * @param serverId 対象のサーバーID[span_79](start_span)[span_79](end_span)
     * @return 存在する場合は true、存在しない場合は false[span_80](start_span)[span_80](end_span)
     */
    public boolean shopExists(UUID npcUuid, String serverId) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT 1 FROM fje_shops WHERE npc_uuid = ? AND server_id = ? LIMIT 1")) {

            stmt.setString(1, npcUuid.toString());
            stmt.setString(2, serverId);
            ResultSet rs = stmt.executeQuery();

            return rs.next();

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Shop exists check error", e);
            return false;
        }
    }

    /**
     * エンティティ（村人などのBukkit Entity）からそのUUIDを基にショップ情報を取得します[span_81](start_span)[span_81](end_span)。
     *
     * @param entity   調査対象のBukkit エンティティ[span_82](start_span)[span_82](end_span)
     * @param serverId 対象のサーバーID[span_83](start_span)[span_83](end_span)
     * @return 該当するショップが存在する場合は {@link Shop} インスタンス、なければ null[span_84](start_span)[span_84](end_span)
     */
    public Shop getShopByEntity(Entity entity, String serverId) {
        return getShop(entity.getUniqueId(), serverId);
    }

    /**
     * 特定のショップに関する情報を保持・表現するための不変（Immutable）データクラスです[span_85](start_span)[span_85](end_span)。
     */
    public static class Shop {
        private final UUID npcUuid;
        private final String serverId;
        private final UUID ownerUUID;
        private final String itemMaterial;
        private final String itemNBT;
        private final int price;
        private final int stock;

        /**
         * Shop オブジェクトの新しいインスタンスを構築します[span_86](start_span)[span_86](end_span)。
         *
         * @param npcUuid      NPCのUUID[span_87](start_span)[span_87](end_span)
         * @param serverId     サーバーID[span_88](start_span)[span_88](end_span)
         * @param ownerUUID    ショップオーナーのUUID[span_89](start_span)[span_89](end_span)
         * @param itemMaterial 販売するアイテムのMinecraftマテリアル名[span_90](start_span)[span_90](end_span)
         * @param itemNBT      アイテムに付与されているNBTのJSON文字列表現[span_91](start_span)[span_91](end_span)
         * @param price        販売価格[span_92](start_span)[span_92](end_span)
         * @param stock        残存在庫数[span_93](start_span)[span_93](end_span)
         */
        public Shop(UUID npcUuid, String serverId, UUID ownerUUID, String itemMaterial,
                   String itemNBT, int price, int stock) {
            this.npcUuid = npcUuid;
            this.serverId = serverId;
            this.ownerUUID = ownerUUID;
            this.itemMaterial = itemMaterial;
            this.itemNBT = itemNBT;
            this.price = price;
            this.stock = stock;
        }

        // Getters
        public UUID getNpcUuid() { return npcUuid; }
        public String getServerId() { return serverId; }
        public UUID getOwnerUUID() { return ownerUUID; }
        public String getItemMaterial() { return itemMaterial; }
        public String getItemNBT() { return itemNBT; }
        public int getPrice() { return price; }
        public int getStock() { return stock; }

        /**
         * ショップに指定された数以上の在庫が確保されているか確認します[span_94](start_span)[span_94](end_span)。
         *
         * @param quantity 必要とする在庫数量[span_95](start_span)[span_95](end_span)
         * @return 在庫が必要数以上の場合は true、不足している場合は false[span_96](start_span)[span_96](end_span)
         */
        public boolean hasStock(int quantity) {
            return stock >= quantity;
        }

        /**
         * チャットやデバッグ、UI表示などのための整形された文字列表現を取得します[span_97](start_span)[span_97](end_span)。
         *
         * @param currencySymbol 通貨記号（例: "$", "円" など）[span_98](start_span)[span_98](end_span)
         * @return 整形されたショップの概要情報[span_99](start_span)[span_99](end_span)
         */
        public String getDisplayInfo(String currencySymbol) {
            return String.format("%s: %s%d (在庫: %d個)", 
                    itemMaterial, currencySymbol, price, stock);
        }
    }
}
