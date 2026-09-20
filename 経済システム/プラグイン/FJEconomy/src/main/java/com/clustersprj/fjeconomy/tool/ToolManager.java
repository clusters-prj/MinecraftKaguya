package com.clustersprj.fjeconomy.tool;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * マーケットプレイス(Web/fjew)の便利アイテム(item_type='tool')購入状態と、その中身を定義する
 * 固定カタログ(fje_tool_catalog)を扱うマネージャー。
 * <p>
 * ツールの中身(見た目)は出品者が持ち込むのではなく、このプラグインが管理者だけが書き込める
 * {@code plugins/FJEconomy/tools/*.yml} を起動時にスキャンして {@code fje_tool_catalog} へ同期する。
 * Web側の出品はこのカタログから {@code tool_code} を選ぶだけで、中身(実行コードやNBT)を
 * 一切持ち込めない(marketplace経由の任意コード実行・不正NBT混入を防ぐため)。
 * </p>
 * <p>
 * 所有権はskin/SkinManagerと同じ方針で、Minecraftサーバー側はマーケットプレイスへHTTP APIを
 * 一切呼び出さず、Web側(server.js)と共有するMariaDBを直接読み書きする。ツールアイテムは
 * スキンと異なり「使用中/未使用」の状態を持たず、所有しているNFTからは何度でも実アイテムの
 * コピーを払い出せる（消耗品的挙動）。
 * </p>
 */
public class ToolManager {

    private final FJEconomy plugin;
    private final DatabaseManager dbManager;

    public ToolManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
    }

    /** プレイヤーが所有しているツールアイテムNFT */
    public record OwnedTool(int nftId, String toolCode) {
    }

    /** fje_tool_catalog の1行分のデータ */
    public record ToolCatalogEntry(String toolCode, String material, String displayName,
                                    List<String> lore, Integer customModelData) {
    }

    /**
     * NFTの実所有者(owner_uuid)と、これから使おうとしているキャラクターのUUIDが
     * 「同じWebアカウント(account_links.web_user_id)にリンクされているか」を検証するJOIN条件。
     * skin/SkinManager の OWNERSHIP_VIA_WEB_ACCOUNT_JOIN と同じ考え方。
     */
    private static final String OWNERSHIP_VIA_WEB_ACCOUNT_JOIN =
            "JOIN account_links owner_link ON owner_link.minecraft_uuid = n.owner_uuid " +
            "JOIN account_links my_link ON my_link.web_user_id = owner_link.web_user_id ";

    /**
     * 指定キャラクターと同じWebアカウントにリンクされているツールアイテムNFTの一覧を返す。
     */
    public List<OwnedTool> listOwnedTools(UUID uuid) {
        List<OwnedTool> result = new ArrayList<>();
        String sql = "SELECT n.id AS nft_id, l.tool_code " +
                "FROM account_links my_link " +
                "JOIN account_links owner_link ON owner_link.web_user_id = my_link.web_user_id " +
                "JOIN marketplace_nfts n ON n.owner_uuid = owner_link.minecraft_uuid " +
                "JOIN marketplace_listings l ON l.id = n.listing_id AND l.item_type = 'tool' " +
                "WHERE my_link.minecraft_uuid = ? AND l.tool_code IS NOT NULL " +
                "GROUP BY n.id, l.tool_code " +
                "ORDER BY MAX(n.minted_at) DESC";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new OwnedTool(rs.getInt("nft_id"), rs.getString("tool_code")));
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
     * Minecraftアカウントの所有物であることを確認したうえで、fje_tool_catalog(管理者が
     * YAMLで登録した固定カタログ)から中身を組み立てる。
     *
     * @return 所有が確認でき、対応するカタログエントリが存在する場合のみ ItemStack を返す
     */
    public Optional<ItemStack> buildItemStack(UUID uuid, int nftId) {
        String sql = "SELECT l.tool_code FROM marketplace_nfts n " +
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
                String toolCode = rs.getString("tool_code");
                if (toolCode == null) return Optional.empty();
                return getCatalogEntry(toolCode).map(this::toItemStack);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "ツールアイテムの組み立てに失敗しました (uuid=" + uuid + ", nftId=" + nftId + ")", e);
            return Optional.empty();
        }
    }

    /** fje_tool_catalog から指定tool_codeの定義を取得する */
    public Optional<ToolCatalogEntry> getCatalogEntry(String toolCode) {
        String sql = "SELECT tool_code, material, display_name, lore, custom_model_data " +
                "FROM fje_tool_catalog WHERE tool_code = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, toolCode);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String loreText = rs.getString("lore");
                List<String> lore = loreText == null || loreText.isEmpty()
                        ? List.of()
                        : List.of(loreText.split("\n"));
                Integer customModelData = (Integer) rs.getObject("custom_model_data");
                return Optional.of(new ToolCatalogEntry(
                        rs.getString("tool_code"), rs.getString("material"),
                        rs.getString("display_name"), lore, customModelData));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "ツールカタログの取得に失敗しました (tool_code=" + toolCode + ")", e);
            return Optional.empty();
        }
    }

    private ItemStack toItemStack(ToolCatalogEntry entry) {
        Material material = Material.matchMaterial(entry.material());
        if (material == null) {
            plugin.getLogger().warning("fje_tool_catalogに不正なmaterialが登録されています: "
                    + entry.toolCode() + " -> " + entry.material());
            material = Material.BARRIER;
        }
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', entry.displayName()));
            if (!entry.lore().isEmpty()) {
                List<String> lore = new ArrayList<>();
                for (String line : entry.lore()) {
                    lore.add(ChatColor.translateAlternateColorCodes('&', line));
                }
                meta.setLore(lore);
            }
            if (entry.customModelData() != null) {
                meta.setCustomModelData(entry.customModelData());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * {@code plugins/FJEconomy/tools/*.yml} をスキャンし、fje_tool_catalog を同期する。
     * ファイル名(拡張子抜き)を tool_code とする。壊れたファイルが1つあっても他のファイルの
     * 読み込みやプラグイン起動自体は止めず、そのファイルだけスキップしてログに警告を出す。
     * <p>
     * 置けるのはサーバーのファイルシステムに直接アクセスできる管理者だけであり、
     * マーケットプレイスの出品者(Web側)がここに書き込む経路は存在しない。
     * </p>
     */
    public void syncCatalogFromFolder() {
        File dir = new File(plugin.getDataFolder(), "tools");
        if (!dir.exists()) {
            dir.mkdirs();
            return;
        }
        File[] files = dir.listFiles((f, name) -> name.endsWith(".yml") || name.endsWith(".yaml"));
        if (files == null) return;

        int synced = 0;
        for (File file : files) {
            String toolCode = file.getName().replaceFirst("\\.ya?ml$", "");
            try {
                if (syncCatalogFile(toolCode, file)) synced++;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "ツールカタログファイルの読み込みに失敗しました: " + file.getName(), e);
            }
        }
        plugin.getLogger().info("✓ ツールカタログを同期しました (" + synced + "件, " + dir.getPath() + ")");
    }

    @SuppressWarnings("unchecked")
    private boolean syncCatalogFile(String toolCode, File file) throws Exception {
        Map<String, Object> data;
        try (InputStream is = Files.newInputStream(file.toPath())) {
            Object loaded = new Yaml().load(is);
            if (!(loaded instanceof Map<?, ?> map)) {
                plugin.getLogger().warning("ツールカタログファイルの形式が不正です(マップではありません): " + file.getName());
                return false;
            }
            data = (Map<String, Object>) map;
        }

        Object materialObj = data.get("material");
        if (!(materialObj instanceof String materialName) || materialName.isBlank()) {
            plugin.getLogger().warning("ツールカタログファイルにmaterialがありません: " + file.getName());
            return false;
        }
        if (Material.matchMaterial(materialName) == null) {
            plugin.getLogger().warning("ツールカタログファイルのmaterialが不正です: " + file.getName() + " -> " + materialName);
            return false;
        }

        Object displayNameObj = data.get("display_name");
        String displayName = displayNameObj instanceof String s ? s : toolCode;

        String lore = null;
        Object loreObj = data.get("lore");
        if (loreObj instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object line : list) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(String.valueOf(line));
            }
            lore = sb.toString();
        }

        Integer customModelData = null;
        Object cmdObj = data.get("custom_model_data");
        if (cmdObj instanceof Number n) {
            customModelData = n.intValue();
        }

        Object descriptionObj = data.get("description");
        String description = descriptionObj instanceof String s ? s : null;

        String sql = "INSERT INTO fje_tool_catalog (tool_code, material, display_name, lore, custom_model_data, description) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE material = VALUES(material), display_name = VALUES(display_name), " +
                "lore = VALUES(lore), custom_model_data = VALUES(custom_model_data), description = VALUES(description)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, toolCode);
            stmt.setString(2, materialName);
            stmt.setString(3, displayName);
            stmt.setString(4, lore);
            if (customModelData != null) {
                stmt.setInt(5, customModelData);
            } else {
                stmt.setNull(5, java.sql.Types.INTEGER);
            }
            stmt.setString(6, description);
            stmt.executeUpdate();
        }
        return true;
    }
}
