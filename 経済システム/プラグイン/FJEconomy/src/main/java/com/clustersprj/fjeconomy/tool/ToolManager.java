package com.clustersprj.fjeconomy.tool;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * マーケットプレイス(Web/fjew)の便利アイテム(item_type='tool')出品・購入状態を共有DB経由で参照するマネージャー。
 * <p>
 * skin/SkinManager と同じ方針で、Minecraftサーバー側はマーケットプレイスへHTTP APIを一切呼び出さず、
 * Web側(server.js)と共有するMariaDB（marketplace_listings, marketplace_nfts）を直接読み書きする。
 * ツールアイテムはスキンと異なり「使用中/未使用」の状態を持たず、所有しているNFTからは
 * 何度でも実アイテムのコピーを払い出せる（消耗品的挙動）ため、専用の状態テーブルは持たない。
 * </p>
 */
public class ToolManager {

    private final FJEconomy plugin;
    private final DatabaseManager dbManager;

    public ToolManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
    }

    /** プレイヤーが所有しているツールアイテムNFTの一覧表示・払い出し用データ */
    public record OwnedTool(int nftId, String title, String toolJson) {
    }

    /**
     * NFTの実所有者(owner_uuid)と、これから使おうとしているキャラクターのUUIDが
     * 「同じWebアカウント(account_links.web_user_id)にリンクされているか」を検証するJOIN条件。
     * skin/SkinManager の OWNERSHIP_VIA_WEB_ACCOUNT_JOIN と同じ考え方（購入時に選んだアカウントに限らず、
     * 同じWebアカウントにリンクされた別のMinecraftアカウントでも使えるようにする）。
     */
    private static final String OWNERSHIP_VIA_WEB_ACCOUNT_JOIN =
            "JOIN account_links owner_link ON owner_link.minecraft_uuid = n.owner_uuid " +
            "JOIN account_links my_link ON my_link.web_user_id = owner_link.web_user_id ";

    /**
     * 指定キャラクターと同じWebアカウントにリンクされているツールアイテムNFTの一覧を返す。
     */
    public List<OwnedTool> listOwnedTools(UUID uuid) {
        List<OwnedTool> result = new ArrayList<>();
        String sql = "SELECT n.id AS nft_id, l.title, l.tool_json " +
                "FROM account_links my_link " +
                "JOIN account_links owner_link ON owner_link.web_user_id = my_link.web_user_id " +
                "JOIN marketplace_nfts n ON n.owner_uuid = owner_link.minecraft_uuid " +
                "JOIN marketplace_listings l ON l.id = n.listing_id AND l.item_type = 'tool' " +
                "WHERE my_link.minecraft_uuid = ? AND l.tool_json IS NOT NULL " +
                "GROUP BY n.id, l.title, l.tool_json " +
                "ORDER BY MAX(n.minted_at) DESC";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new OwnedTool(
                            rs.getInt("nft_id"),
                            rs.getString("title"),
                            rs.getString("tool_json")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "所有ツールアイテム一覧の取得に失敗しました (uuid=" + uuid + ")", e);
        }
        return result;
    }

    /**
     * 所有権を再検証したうえで、指定NFTに対応するツールアイテムの実ItemStackを1つ組み立てる。
     * クライアント入力(GUIクリック)由来のnftIdをそのまま信用せず、必ずここでDB照会して
     * 対象NFTがitem_type='tool'かつ、指定キャラクターと同じWebアカウントにリンクされた
     * Minecraftアカウントの所有物であることを確認してから組み立てる。
     *
     * @return 所有が確認でき、tool_jsonの内容が有効な場合のみ ItemStack を返す（不正な場合は空）
     */
    public Optional<ItemStack> buildItemStack(UUID uuid, int nftId) {
        String sql = "SELECT l.tool_json FROM marketplace_nfts n " +
                "JOIN marketplace_listings l ON l.id = n.listing_id AND l.item_type = 'tool' " +
                OWNERSHIP_VIA_WEB_ACCOUNT_JOIN +
                "AND my_link.minecraft_uuid = ? " +
                "WHERE n.id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setInt(2, nftId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String toolJson = rs.getString("tool_json");
                if (toolJson == null) return Optional.empty();
                return parseItemStack(toolJson);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "ツールアイテムの組み立てに失敗しました (uuid=" + uuid + ", nftId=" + nftId + ")", e);
            return Optional.empty();
        }
    }

    /**
     * Web側(server.js)で検証済みのJSON({@code material}, {@code amount}, {@code display_name},
     * {@code lore}, {@code custom_model_data}, {@code enchantments})からItemStackを組み立てる。
     * material が不正な場合はfail-closedで空を返す（Barrier等へのフォールバックはしない）。
     */
    private Optional<ItemStack> parseItemStack(String toolJson) {
        JsonObject obj;
        try {
            JsonElement parsed = JsonParser.parseString(toolJson);
            if (!parsed.isJsonObject()) return Optional.empty();
            obj = parsed.getAsJsonObject();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "tool_jsonのパースに失敗しました", e);
            return Optional.empty();
        }

        if (!obj.has("material") || !obj.get("material").isJsonPrimitive()) return Optional.empty();
        Material material = Material.matchMaterial(obj.get("material").getAsString());
        if (material == null) {
            plugin.getLogger().warning("tool_jsonに不正なmaterialが指定されています: " + obj.get("material").getAsString());
            return Optional.empty();
        }

        int amount = 1;
        if (obj.has("amount") && obj.get("amount").isJsonPrimitive()) {
            amount = Math.max(1, Math.min(64, obj.get("amount").getAsInt()));
        }

        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (obj.has("display_name") && obj.get("display_name").isJsonPrimitive()) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', obj.get("display_name").getAsString()));
            }
            if (obj.has("lore") && obj.get("lore").isJsonArray()) {
                List<String> lore = new ArrayList<>();
                obj.get("lore").getAsJsonArray().forEach(e -> {
                    if (e.isJsonPrimitive()) {
                        lore.add(ChatColor.translateAlternateColorCodes('&', e.getAsString()));
                    }
                });
                meta.setLore(lore);
            }
            if (obj.has("custom_model_data") && obj.get("custom_model_data").isJsonPrimitive()) {
                meta.setCustomModelData(obj.get("custom_model_data").getAsInt());
            }
            if (obj.has("enchantments") && obj.get("enchantments").isJsonObject()) {
                Map<String, Integer> enchantments = new LinkedHashMap<>();
                obj.get("enchantments").getAsJsonObject().entrySet().forEach(e -> {
                    if (e.getValue().isJsonPrimitive()) {
                        enchantments.put(e.getKey(), e.getValue().getAsInt());
                    }
                });
                for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
                    Enchantment enchantment = Enchantment.getByKey(
                            org.bukkit.NamespacedKey.minecraft(entry.getKey().toLowerCase()));
                    if (enchantment == null) {
                        plugin.getLogger().warning("tool_jsonに不明なenchantmentが指定されています: " + entry.getKey());
                        continue;
                    }
                    meta.addEnchant(enchantment, entry.getValue(), true);
                }
            }
            item.setItemMeta(meta);
        }
        return Optional.of(item);
    }
}
