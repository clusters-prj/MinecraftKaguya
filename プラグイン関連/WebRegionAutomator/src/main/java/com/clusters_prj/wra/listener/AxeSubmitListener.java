package com.clusters_prj.wra.listener;

import com.clusters_prj.wra.Main;
import com.clusters_prj.wra.database.DatabaseManager;
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

public class AxeSubmitListener implements Listener {

    private final Main plugin;
    private final DatabaseManager databaseManager;

    public AxeSubmitListener(Main plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null) return;

        Material mat = item.getType();
        // WorldEditのデフォルトツールは木の斧。必要であれば他の斧も許可できます。
        if (mat != Material.WOODEN_AXE) return;

        // 右クリック時に斧で選択範囲をDBへ申請として登録する
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR:
            case RIGHT_CLICK_BLOCK:
                try {
                    LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
                    if (session == null) {
                        player.sendMessage("§cWorldEdit セッションが見つかりません。");
                        return;
                    }

                    Region sel = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
                    if (sel == null) {
                        player.sendMessage("§e選択範囲がありません。斧で二点を選んでください。");
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

                    String serverId = plugin.getConfig().getString("server-id", "server1");
                    String playerUuid = player.getUniqueId().toString();
                    String regionId = "req_" + player.getName() + "_" + System.currentTimeMillis();

                    boolean inserted = databaseManager.insertProtectionRequest(
                            serverId, playerUuid, regionId,
                            player.getWorld().getName(),
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
