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
 * 統合版(Geyser)ユーザーの操作性にも配慮したチェスト型ショップUI
 */
public class ShopUI implements Listener {

    private final FJEconomy plugin;
    private final ShopManager shopManager;
    private final EconomyManager economyManager;
    private final String GUI_TITLE = ChatColor.DARK_BLUE + "FJE ショップ";
    private final NamespacedKey shopNpcKey;

    // キャッシュ用: EntityのUUID -> キャッシュデータ(Shop情報と有効期限)
    private final Map<UUID, CachedShop> shopCache = new HashMap<>();

    private static class CachedShop {
        final ShopManager.Shop shop;
        final long expiry;
        CachedShop(ShopManager.Shop shop) {
            this.shop = shop;
            this.expiry = System.currentTimeMillis() + 10000; // 10秒間有効
        }
    }

    public ShopUI(FJEconomy plugin) {
        this.plugin = plugin;
        this.shopManager = plugin.getShopManager();
        this.economyManager = plugin.getEconomyManager(); // 既存のインスタンスを使用するように修正
        this.shopNpcKey = new NamespacedKey(plugin, "shop_npc_uuid");
    }

    /**
     * 村人を右クリックした際にショップを開く
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();

        // メインハンド（右クリック）のみを処理対象とし、オフハンドでの重複処理を防ぐ
        if (event.getHand() != EquipmentSlot.HAND) return;

        if (!(event.getRightClicked() instanceof Villager)) return;

        UUID entityUuid = event.getRightClicked().getUniqueId();

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

        long itemPrice = shop.getPrice();

        // 付与するアイテムは代金を動かす前に確定させる
        // （購入成立後に不正なマテリアルだと判明すると返金処理が必要になるため）
        Material itemMaterial = Material.matchMaterial(shop.getItemMaterial());
        if (itemMaterial == null) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix()
                    + "<red>このショップの商品設定が不正です。管理者に連絡してください。"));
            plugin.getLogger().warning("Invalid material '" + shop.getItemMaterial() + "' for shop " + npcUuid);
            return;
        }

        long playerBalance = economyManager.getBalance(player.getUniqueId());
        if (playerBalance < itemPrice) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>お金が足りません！ (必要: " + economyManager.formatMoney(itemPrice) + ", 所持: " + economyManager.formatMoney(playerBalance) + ")"));
            return;
        }

        // 先に在庫を確保する。removeStock は「stock >= quantity」の条件つき UPDATE なので、
        // 同時購入があっても在庫がマイナスになることはない。
        if (!shopManager.removeStock(npcUuid, serverId, 1)) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>現在、在庫がありません。"));
            return;
        }

        String ownerName = Bukkit.getOfflinePlayer(shop.getOwnerUUID()).getName();
        if (ownerName == null) ownerName = shop.getOwnerUUID().toString();

        // 代金の移動・納税・取引記録・国庫台帳への記帳は processPurchase が
        // 1つのトランザクションでまとめて行う（途中で失敗すれば全てロールバックされる）。
        // 以前はここで takeMoney / addTaxIncome / giveMoney / recordTransaction を
        // 個別のコネクションで実行しており、途中で失敗すると代金や売上が消える恐れがあった。
        boolean purchased = economyManager.processPurchase(
                player.getUniqueId(), player.getName(),
                shop.getOwnerUUID(), ownerName,
                shop.getItemMaterial(), 1, itemPrice);

        if (!purchased) {
            // 代金が動いていないので、確保した在庫を戻すだけでよい
            shopManager.addStock(npcUuid, serverId, 1);
            player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>購入処理に失敗しました。代金は引き落とされていません。"));
            plugin.getLogger().warning("Purchase transaction failed for shop " + npcUuid + " (buyer: " + player.getName() + "). Stock restored.");
            return;
        }

        // 表示用の税額。processPurchase と同じ計算を使う（config の rounding_method に従う）
        long taxAmount = economyManager.calculateTax(itemPrice);

        // アイテムをプレイヤーに付与（インベントリが満杯なら足元にドロップする）
        ItemStack purchasedItem = new ItemStack(itemMaterial, 1);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(purchasedItem);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        if (!leftover.isEmpty()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix()
                    + "<yellow>インベントリが満杯だったため、商品を足元にドロップしました。"));
        }

        player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
            "<green>" + economyManager.formatMoney(itemPrice) + " で " + itemMaterial.name() + " を購入しました！ " +
            "<gray>(内税" + plugin.getConfigManager().getTaxRate() + "%: " + economyManager.formatMoney(taxAmount) + " が政府に納められました)"));

        // UIを更新して最新の在庫数を表示
        ShopManager.Shop freshShop = shopManager.getShop(npcUuid, serverId);
        if (freshShop != null) {
            // 購入時は在庫が変動するため、キャッシュも最新情報で更新しておく
            shopCache.put(npcUuid, new CachedShop(freshShop));
            openShopGui(player, freshShop);
        }
    }
}