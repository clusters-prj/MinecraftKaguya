package com.clustersprj.fjeconomy.shop;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.economy.EconomyManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventPriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 統合版(Geyser)ユーザーの操作性にも配慮したチェスト型ショップUIを提供するリスナークラスです[span_2](start_span)[span_2](end_span)。
 * 村人NPCへのインタラクト時のカスタムUIの表示や、GUI内での取引処理（購入、納税、売上送金など）を管理します[span_3](start_span)[span_3](end_span)。
 */
public class ShopUI implements Listener {

    /** プラグインのメインクラスのインスタンス */
    private final FJEconomy plugin;

    /** ショップ情報を処理・管理するマネージャー */
    private final ShopManager shopManager;

    /** お金（経済）システムを制御するマネージャー */
    private final EconomyManager economyManager;

    /** ショップGUIのタイトル表示名 */
    private final String GUI_TITLE = ChatColor.DARK_BLUE + "FJE ショップ";

    /** NPCのUUID情報をItemStackのメタデータに保持するための永続化キー */
    private final NamespacedKey shopNpcKey;

    /**
     * EntityのUUIDをキーとしたショップ情報のキャッシュ用マップです[span_4](start_span)[span_4](end_span)。
     * データベースへの頻繁なクエリ問い合わせを防ぎ、サーバー負荷を軽減します[span_5](start_span)[span_5](end_span)。
     */
    private final Map<UUID, CachedShop> shopCache = new HashMap<>();

    /**
     * キャッシュされたショップデータと、その有効期限を保持するインナークラスです[span_6](start_span)[span_6](end_span)。
     */
    private static class CachedShop {
        /** キャッシュされたショップオブジェクト[span_7](start_span)[span_7](end_span) */
        final ShopManager.Shop shop;
        /** キャッシュの有効期限（ミリ秒）[span_8](start_span)[span_8](end_span) */
        final long expiry;

        /**
         * 新しいショップキャッシュインスタンスを生成します[span_9](start_span)[span_9](end_span)。
         * デフォルトで10秒間有効です[span_10](start_span)[span_10](end_span)。
         *
         * @param shop キャッシュ対象のショップデータ[span_11](start_span)[span_11](end_span)
         */
        CachedShop(ShopManager.Shop shop) {
            this.shop = shop;
            this.expiry = System.currentTimeMillis() + 10000; // 10秒間有効
        }
    }

    /**
     * ShopUI を初期化するためのコンストラクタです[span_12](start_span)[span_12](end_span)。
     *
     * @param plugin FJEconomy プラグインのメインクラス[span_13](start_span)[span_13](end_span)
     */
    public ShopUI(FJEconomy plugin) {
        this.plugin = plugin;
        this.shopManager = plugin.getShopManager();
        this.economyManager = plugin.getEconomyManager(); // 既存のインスタンスを使用するように修正
        this.shopNpcKey = new NamespacedKey(plugin, "shop_npc_uuid");
    }

    /**
     * プレイヤーがエンティティ（村人NPCなど）を右クリックした際に呼び出されるイベントハンドラです[span_14](start_span)[span_14](end_span)。
     * 該当エンティティにショップデータが紐づいている場合、通常の取引画面をキャンセルして独自のチェスト型GUIを開きます[span_15](start_span)[span_15](end_span)。
     *
     * @param event エンティティインタラクトイベント[span_16](start_span)[span_16](end_span)
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();

        // 最優先デバッグ: そもそもイベントが動いているか
        // player.sendMessage(ChatColor.YELLOW + "[Debug] Event Triggered! Entity: " + event.getRightClicked().getType());

        // メインハンド（右クリック）のみを処理対象とし、オフハンドでの重複処理を防ぐ
        if (event.getHand() != EquipmentSlot.HAND) return;

        // 村人以外（CitizensのNPC等）の場合も考慮し、一旦型チェック前にログを出す
        UUID entityUuid = event.getRightClicked().getUniqueId();
        player.sendMessage(ChatColor.GRAY + "[Debug] Clicked UUID: " + entityUuid.toString() + " (Type: " + event.getRightClicked().getType() + ")");

        if (!(event.getRightClicked() instanceof Villager)) return;

        String serverId = plugin.getConfigManager().getServerId(); // config.ymlの値
        
        // キャッシュを確認
        ShopManager.Shop shop;
        CachedShop cached = shopCache.get(entityUuid);

        if (cached != null && System.currentTimeMillis() < cached.expiry) {
            shop = cached.shop;
        } else {
            // キャッシュがない、または期限切れの場合はDBから取得してキャッシュを更新
            shop = shopManager.getShopByEntity(event.getRightClicked(), serverId);
            shopCache.put(entityUuid, new CachedShop(shop));
        }

        if (shop != null) {
            // サーバーIDが一致しているか最終確認（デバッグ用）
            if (!shop.getServerId().equals(serverId)) {
                return; 
            }
            // データベースにショップデータが存在する場合のみ、通常の取引画面をキャンセルして独自GUIを開く
            event.setCancelled(true);
            openShopGui(player, shop);
        }
    }

    /**
     * 対象のプレイヤーに対してショップGUIを開きます[span_17](start_span)[span_17](end_span)。
     * 誤操作を防止するため、空のスロットは灰色ステンドグラス板で埋められます[span_18](start_span)[span_18](end_span)。
     *
     * @param player GUIを開く対象のプレイヤー[span_19](start_span)[span_19](end_span)
     * @param shop   表示するショップの情報[span_20](start_span)[span_20](end_span)
     */
    private void openShopGui(Player player, ShopManager.Shop shop) {
        // 3列(27スロット)のチェスト型GUIを作成
        Inventory gui = Bukkit.createInventory(null, 27, GUI_TITLE);

        // 背景埋め: 統合版での誤操作防止とデザイン向上のため
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.setDisplayName(" ");
            filler.setItemMeta(fillerMeta);
        }
        for (int i = 0; i < 27; i++) {
            gui.setItem(i, filler);
        }

        // 販売アイテムの生成
        Material mat = Material.matchMaterial(shop.getItemMaterial());
        if (mat == null) mat = Material.BARRIER;

        ItemStack shopItem = new ItemStack(mat);
        ItemMeta meta = shopItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "商品を注文する");
            
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "アイテム: " + ChatColor.WHITE + mat.name());
            lore.add(""); // 空行を追加
            lore.add(ChatColor.WHITE + "価格: " + ChatColor.YELLOW + economyManager.formatMoney(shop.getPrice())); // formatMoneyで表示
            lore.add(ChatColor.WHITE + "在庫: " + ChatColor.GREEN + shop.getStock() + " 個");
            lore.add("");
            lore.add(ChatColor.AQUA + "▶ クリックで購入を確定");
            
            // アイテムにNPCのUUIDをタグとして埋め込む (購入時にDBから最新情報を引くため)
            meta.getPersistentDataContainer().set(shopNpcKey, PersistentDataType.STRING, shop.getNpcUuid().toString());
            
            meta.setLore(lore);
            shopItem.setItemMeta(meta);
        }

        // 中央のスロット(13)に配置
        gui.setItem(13, shopItem);
        player.openInventory(gui);
    }

    /**
     * ショップGUI内のインベントリクリックイベントを処理します[span_21](start_span)[span_21](end_span)。
     * アイテムの持ち出し防止、在庫チェック、残高チェック、引き落とし、
     * 税金の計算および国庫への送金、オーナーへの売上送金、アイテム付与を行います[span_22](start_span)[span_22](end_span)。
     *
     * @param event インベントリクリックイベント[span_23](start_span)[span_23](end_span)
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) return;

        // 全ての操作をキャンセル (アイテムを持ち出せないようにする)
        event.setCancelled(true);

        // クリックされたアイテムの有効性チェック
        if (event.getRawSlot() != 13) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        Player player = (Player) event.getWhoClicked();
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        String uuidStr = meta.getPersistentDataContainer().get(shopNpcKey, PersistentDataType.STRING);
        if (uuidStr == null) return;

        UUID npcUuid = UUID.fromString(uuidStr);
        String serverId = plugin.getConfigManager().getServerId();
        ShopManager.Shop shop = shopManager.getShop(npcUuid, serverId);

        if (shop == null) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>ショップが見つかりません。"));
            return;
        }

        if (!shop.hasStock(1)) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>現在、在庫がありません。"));
            return;
        }

        // プレイヤーの残高を確認
        long playerBalance = economyManager.getBalance(player.getUniqueId());
        long itemPrice = shop.getPrice();
        
        // 税金の計算 (config.ymlの economy.tax_rate を使用)
        double taxRate = plugin.getConfigManager().getTaxRate() / 100.0;
        long taxAmount = Math.round(itemPrice * taxRate);
        long sellerProfit = itemPrice - taxAmount;

        if (playerBalance < itemPrice) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>お金が足りません！ (必要: " + economyManager.formatMoney(itemPrice) + ", 所持: " + economyManager.formatMoney(playerBalance) + ")"));
            return;
        }

        // お金の引き落としと在庫の減少
        if (economyManager.takeMoney(player.getUniqueId(), player.getName(), itemPrice)) {
            if (shopManager.removeStock(npcUuid, serverId, 1)) {
                // 【納税処理】政府資金(国庫)に税金を加算し、TAX_INとして記録する
                // (addGovernmentFundsだとFUND_ADD扱いになり、/fjegovernment tax の集計に反映されないため注意)
                plugin.getGovernmentManager().addTaxIncome(taxAmount, 
                    "SHOP_PURCHASE - Item: " + shop.getItemMaterial() + ", Buyer: " + player.getName());

                // 【売上送金】税抜き価格をショップオーナーに送金
                String ownerName = Bukkit.getOfflinePlayer(shop.getOwnerUUID()).getName();
                if (ownerName == null) ownerName = shop.getOwnerUUID().toString();
                economyManager.giveMoney(shop.getOwnerUUID(), ownerName, sellerProfit);

                // アイテムをプレイヤーに付与
                Material itemMaterial = Material.matchMaterial(shop.getItemMaterial());
                if (itemMaterial != null) {
                    player.getInventory().addItem(new ItemStack(itemMaterial, 1));
                    player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + 
                        "<green>" + economyManager.formatMoney(itemPrice) + " で " + itemMaterial.name() + " を購入しました！ " +
                        "<gray>(内税10%: " + economyManager.formatMoney(taxAmount) + " が政府に納められました)"));
                } else {
                    player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>アイテムの取得に失敗しました。お金は返金されました。管理者に連絡してください。"));
                    // アイテム付与失敗時はお金を返金
                    economyManager.giveMoney(player.getUniqueId(), player.getName(), itemPrice);
                    plugin.getLogger().warning("Failed to give item " + shop.getItemMaterial() + " to player " + player.getName() + " after purchase. Money refunded.");
                }
                
                // UIを更新して最新の在庫数を表示
                ShopManager.Shop freshShop = shopManager.getShop(npcUuid, serverId);
                if (freshShop != null) {
                    // 購入時は在庫が変動するため、キャッシュも最新情報で更新しておく
                    shopCache.put(npcUuid, new CachedShop(freshShop));
                    openShopGui(player, freshShop);
                }
            } else {
                // 在庫更新失敗時はお金を返金
                economyManager.giveMoney(player.getUniqueId(), player.getName(), itemPrice);
                player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>在庫の更新に失敗しました。お金は返金されました。管理者に連絡してください。"));
                plugin.getLogger().warning("Failed to remove stock for shop " + npcUuid + " after money deduction. Money refunded to " + player.getName());
            }
        } else {
            player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>お金の引き落としに失敗しました。"));
        }
    }
}
