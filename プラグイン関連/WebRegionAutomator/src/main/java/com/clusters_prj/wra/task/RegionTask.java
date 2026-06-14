package com.clusters_prj.wra.task;

import com.clusters_prj.wra.Main;
import com.clusters_prj.wra.database.DatabaseManager;
import com.clusters_prj.wra.worldguard.WorldGuardHandler;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Map;

public class RegionTask extends BukkitRunnable {

    private final Main plugin;
    private final DatabaseManager databaseManager;
    private final WorldGuardHandler wgHandler;

    public RegionTask(Main plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.wgHandler = new WorldGuardHandler(plugin);
    }

    @Override
    public void run() {
        // 非同期でDBから未処理申請を取得
        String serverId = plugin.getConfig().getString("server-id", "life1");
        int maxRequests = plugin.getConfig().getInt("settings.max-requests-per-tick", 5);

        List<Map<String, Object>> requests = databaseManager.fetchUnprocessedRequests(serverId, maxRequests);

        if (requests.isEmpty()) {
            return; // 申請がない
        }

        plugin.getLogger().info("処理待ち申請: " + requests.size() + " 件");

        // メインスレッドに同期して処理を実行
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Map<String, Object> request : requests) {
                processRequest(request);
            }
        });
    }

    /**
     * 1件の申請を処理
     */
    private void processRequest(Map<String, Object> request) {
        int id = (int) request.get("id");
        String playerUuid = (String) request.get("player_uuid");
        String regionId = (String) request.get("region_id");
        String worldName = (String) request.get("world_name");

        int x1 = (int) request.get("x1");
        int y1 = (int) request.get("y1");
        int z1 = (int) request.get("z1");
        int x2 = (int) request.get("x2");
        int y2 = (int) request.get("y2");
        int z2 = (int) request.get("z2");

        try {
            // WorldGuard側で領域を作成
            boolean success = wgHandler.createRegion(
                    worldName,
                    regionId,
                    playerUuid,
                    x1, y1, z1,
                    x2, y2, z2
            );

            if (success) {
                // 非同期でDB更新（成功）
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    databaseManager.updateRequestStatusSuccess(id);
                    plugin.getLogger().info("✓ 領域作成完了: " + regionId);
                });
            } else {
                // 非同期でDB更新（エラー）
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    databaseManager.updateRequestStatusError(id, "WorldGuard: Failed to create region");
                    plugin.getLogger().warning("✗ 領域作成失敗: " + regionId);
                });
            }
        } catch (Exception e) {
            // 非同期でDB更新（エラー）
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                databaseManager.updateRequestStatusError(id, e.getMessage());
                plugin.getLogger().warning("✗ 例外発生: " + e.getMessage());
            });
        }
    }
}