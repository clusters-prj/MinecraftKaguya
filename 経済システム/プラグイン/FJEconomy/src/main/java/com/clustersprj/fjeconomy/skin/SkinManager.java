package com.clustersprj.fjeconomy.skin;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * マーケットプレイス(Web/fjew)のスキン出品・購入・使用中状態を共有DB経由で参照するマネージャー。
 * <p>
 * Minecraftサーバー側はマーケットプレイスへHTTP APIを一切呼び出さず、Web側(server.js)と共有する
 * MariaDB（marketplace_listings, marketplace_nfts, fje_active_skins）を直接読み書きする。
 * 「未購入判定」はキャッシュや過去の呼び出し結果を信用せず、都度DBへ問い合わせて再検証する。
 * </p>
 */
public class SkinManager {

    private final FJEconomy plugin;
    private final DatabaseManager dbManager;

    public SkinManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
    }

    /** Java版クライアントへ適用する署名済みテクスチャ */
    public record SkinTexture(String model, String textureValue, String textureSignature) {
    }

    /** プレイヤーが所有しているスキンNFTの一覧表示用データ */
    public record OwnedSkin(int nftId, String title, String model, boolean active) {
    }

    /**
     * 指定プレイヤーが現在「使用中」に設定しているスキンの、Java用署名済みテクスチャを取得する。
     * fje_active_skins に記録が残っていても、marketplace_nfts.owner_uuid との一致を毎回このJOINで
     * 再検証するため、出品削除・所有権が変化した場合でも古い情報を返さない。
     *
     * @param uuid 対象プレイヤーのUUID（Floodgate経由のBedrockプレイヤーも同じUUID体系で扱える）
     */
    public Optional<SkinTexture> getActiveSkinTexture(UUID uuid) {
        String sql = "SELECT l.skin_model, l.skin_texture_value, l.skin_texture_signature " +
                "FROM fje_active_skins a " +
                "JOIN marketplace_nfts n ON n.id = a.nft_id AND n.owner_uuid = a.minecraft_uuid " +
                "JOIN marketplace_listings l ON l.id = n.listing_id AND l.item_type = 'skin' " +
                "WHERE a.minecraft_uuid = ? " +
                "AND l.skin_texture_value IS NOT NULL AND l.skin_texture_signature IS NOT NULL";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new SkinTexture(
                        rs.getString("skin_model"),
                        rs.getString("skin_texture_value"),
                        rs.getString("skin_texture_signature")));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "使用中スキンの取得に失敗しました (uuid=" + uuid + ")", e);
            return Optional.empty();
        }
    }

    /**
     * 指定プレイヤーが所有しているスキンNFTの一覧を返す（未所有のものは含まれない）。
     */
    public List<OwnedSkin> listOwnedSkins(UUID uuid) {
        List<OwnedSkin> result = new ArrayList<>();
        String sql = "SELECT n.id AS nft_id, l.title, l.skin_model, (a.nft_id IS NOT NULL) AS is_active " +
                "FROM marketplace_nfts n " +
                "JOIN marketplace_listings l ON l.id = n.listing_id AND l.item_type = 'skin' " +
                "LEFT JOIN fje_active_skins a ON a.minecraft_uuid = n.owner_uuid AND a.nft_id = n.id " +
                "WHERE n.owner_uuid = ? " +
                "ORDER BY n.minted_at DESC";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new OwnedSkin(
                            rs.getInt("nft_id"),
                            rs.getString("title"),
                            rs.getString("skin_model"),
                            rs.getBoolean("is_active")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "所有スキン一覧の取得に失敗しました (uuid=" + uuid + ")", e);
        }
        return result;
    }

    /**
     * 所有権を再検証したうえで「使用中」スキンを設定する。
     * クライアント入力（コマンド引数）由来のnftIdをそのまま信用せず、必ずここでDB照会して
     * 対象NFTがitem_type='skin'かつ本人所有であることを確認してから書き込む。
     *
     * @return 所有が確認でき設定に成功した場合 true。未所有・スキンでない場合は false
     */
    public boolean setActiveSkin(UUID uuid, int nftId) {
        String checkSql = "SELECT 1 FROM marketplace_nfts n " +
                "JOIN marketplace_listings l ON l.id = n.listing_id " +
                "WHERE n.id = ? AND n.owner_uuid = ? AND l.item_type = 'skin'";
        String upsertSql = "INSERT INTO fje_active_skins (minecraft_uuid, nft_id) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE nft_id = VALUES(nft_id)";

        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement check = conn.prepareStatement(checkSql)) {
                check.setInt(1, nftId);
                check.setString(2, uuid.toString());
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next()) return false;
                }
            }
            try (PreparedStatement upsert = conn.prepareStatement(upsertSql)) {
                upsert.setString(1, uuid.toString());
                upsert.setInt(2, nftId);
                upsert.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "使用中スキンの設定に失敗しました (uuid=" + uuid + ", nftId=" + nftId + ")", e);
            return false;
        }
    }
}
