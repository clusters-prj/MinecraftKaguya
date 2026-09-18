package com.clustersprj.fjeconomy.tool;

import com.clustersprj.fjeconomy.FJEconomy;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * マーケットプレイスで購入した便利アイテム(ツールアイテム)の一覧表示・払い出しを行うチェスト型GUI。
 * shop/ShopUI と同じ構造で実装する。GUIを開く入口は {@code /tools} コマンド
 * (command/ToolsCommand)で、実際にプレイヤーへこのコマンドを実行させる導線(ツールバー連携)は
 * サーバー上のSkriptスクリプト側が担当する。
 */
public class ToolGUI implements Listener {

    private static final int GUI_SIZE = 54;

    private final FJEconomy plugin;
    private final ToolManager toolManager;
    private final String guiTitle;
    private final NamespacedKey nftIdKey;

    public ToolGUI(FJEconomy plugin) {
        this.plugin = plugin;
        this.toolManager = plugin.getToolManager();
        this.guiTitle = ChatColor.DARK_BLUE + "購入アイテム";
        this.nftIdKey = new NamespacedKey(plugin, "tool_nft_id");
    }

    public void openGui(Player player) {
        List<ToolManager.OwnedTool> owned = toolManager.listOwnedTools(player.getUniqueId());

        Inventory gui = Bukkit.createInventory(null, GUI_SIZE, guiTitle);

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.setDisplayName(" ");
            filler.setItemMeta(fillerMeta);
        }
        for (int i = 0; i < GUI_SIZE; i++) {
            gui.setItem(i, filler);
        }

        if (owned.isEmpty()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<gray>購入済みのツールアイテムはありません。"));
        }

        // 表示上限(MVP範囲外のページング未実装のため、先頭54件のみ表示)
        int slot = 0;
        for (ToolManager.OwnedTool tool : owned) {
            if (slot >= GUI_SIZE) break;

            Optional<ItemStack> preview = toolManager.buildItemStack(player.getUniqueId(), tool.nftId());
            if (preview.isEmpty()) continue;

            ItemStack icon = preview.get();
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(nftIdKey, PersistentDataType.INTEGER, tool.nftId());
                List<String> lore = meta.hasLore() ? meta.getLore() : new java.util.ArrayList<>();
                if (lore == null) lore = new java.util.ArrayList<>();
                lore.add("");
                lore.add(ChatColor.AQUA + "▶ クリックで受け取る");
                meta.setLore(lore);
                icon.setItemMeta(meta);
            }

            gui.setItem(slot, icon);
            slot++;
        }

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(guiTitle)) return;

        // 全ての操作をキャンセル (アイテムを持ち出せないようにする)
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        ItemMeta clickedMeta = clicked.getItemMeta();
        if (clickedMeta == null) return;

        Integer nftId = clickedMeta.getPersistentDataContainer().get(nftIdKey, PersistentDataType.INTEGER);
        if (nftId == null) return;

        Player player = (Player) event.getWhoClicked();

        Optional<ItemStack> granted = toolManager.buildItemStack(player.getUniqueId(), nftId);
        if (granted.isEmpty()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>このアイテムは受け取れません(所有権を確認できませんでした)。"));
            return;
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(granted.get());
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        if (!leftover.isEmpty()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<yellow>インベントリが満杯だったため、足元にドロップしました。"));
        }

        player.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfigManager().getMessagePrefix() + "<green>アイテムを受け取りました。"));
    }
}
