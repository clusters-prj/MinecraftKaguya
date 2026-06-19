package com.clusters_prj.wra.listener;

import com.clusters_prj.wra.Main;
import com.clusters_prj.wra.database.DatabaseManager;
import com.clusters_prj.wra.worldguard.WorldGuardHandler;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.LocalSession;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;

public class AxeSubmitListener implements Listener {

    private final Main plugin;
    private final DatabaseManager databaseManager;
    private final WorldGuardHandler wgHandler;

    public AxeSubmitListener(Main plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.wgHandler = new WorldGuardHandler(plugin);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // 右手（メインハンド）のクリックのみに限定
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null) return;

        // ★変更：トリガーアイテムを「ひまわり」に変更
        Material mat = item.getType();
        if (mat != Material.SUNFLOWER) return;

        // シフトチェックは不要になったので削除

        switch (event.getAction()) {
            case RIGHT_CLICK_AIR:
            case RIGHT_CLICK_BLOCK:
                try {
                    // ひまわりを持っていても、WorldEditのセッションから現在の選択範囲を問題なく取得できます
                    LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
                    if (session == null) {
                        player.sendMessage("§cWorldEdit セッションが見つかりません。");
                        return;
                    }

                    Region sel;
                    try {
                        sel = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
                    } catch (com.sk89q.worldedit.IncompleteRegionException e) {
                        player.sendMessage("§e選択範囲が不完全です。木の斧で左クリックと右クリックをして2点を選択してください。");
                        return;
                    }

                    if (sel == null) {
                        player.sendMessage("§e選択範囲がありません。木の斧で二点を選んでから、ひまわりで右クリックしてください。");
                        return;
                    }

                    BlockVector3 min = sel.getMinimumPoint();
                    BlockVector3 max = sel.getMaximumPoint();

                    int x1 = min.getBlockX();
                    int y1 = min.getBlockY();
                    int z1 = min.getBlockZ();
                    int x2 = max.getBlockX();
                    int y2 = max.getBlockY();
                    int z2 = max.getBlockZ();
                    String worldName = player.getWorld().getName();

                    // DB登録前の重複チェック
                    if (wgHandler.hasOverlap(worldName, x1, y1, z1, x2, y2, z2)) {
                        player.sendMessage("§c指定された範囲は、既に他の保護領域と重複しています！");
                        return;
                    }

                    String serverId = plugin.getConfig().getString("server-id", "server1");
                    String playerUuid = player.getUniqueId().toString();
                    String regionId = "req_" + player.getName() + "_" + System.currentTimeMillis();

                    boolean inserted = databaseManager.insertProtectionRequest(
                            serverId, playerUuid, regionId, worldName,
                            x1, y1, z1, x2, y2, z2
                    );

                    if (inserted) {
                        player.sendMessage("§a申請を送信しました: " + regionId);
                    } else {
                        player.sendMessage("§c申請の送信に失敗しました。管理者に連絡してください。");
                    }

                } catch (Exception e) {
                    plugin.getLogger().warning("WorldEdit選択取得エラー: " + e.getMessage());
                    player.sendMessage("§c選択の読み取りに失敗しました。");
                    e.printStackTrace();
                }
                break;
            default:
                break;
        }
    }
}
