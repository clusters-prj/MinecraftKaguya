package com.clusters_prj.wra;

import com.clusters_prj.wra.database.DatabaseManager;
import com.clusters_prj.wra.task.RegionTask;
import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin {

    private DatabaseManager databaseManager;
    private RegionTask regionTask;

    @Override
    public void onEnable() {
        // config.ymlの生成・読み込み
        saveDefaultConfig();
        reloadConfig();

        getLogger().info("========================================");
        getLogger().info("WebRegionAutomator が起動しています...");
        getLogger().info("========================================");

        // DatabaseManagerの初期化
        try {
            databaseManager = new DatabaseManager(this);
            getLogger().info("✓ データベース接続プール初期化完了");
        } catch (Exception e) {
            getLogger().severe("✗ データベース接続に失敗しました！");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // RegionTaskの開始
        long checkIntervalTicks = getConfig().getLong("settings.check-interval-ticks", 200);
        regionTask = new RegionTask(this, databaseManager);
        regionTask.runTaskTimerAsynchronously(this, 0, checkIntervalTicks);
        getLogger().info("✓ 領域チェックタスク開始（間隔: " + checkIntervalTicks + " ticks）");

        // プレイヤーの斧 (WorldEdit) による申請イベントを登録
        getServer().getPluginManager().registerEvents(
            new com.clusters_prj.wra.listener.AxeSubmitListener(this, databaseManager),
            this
        );
        getLogger().info("========================================");
        getLogger().info("WebRegionAutomator 起動完了！");
        getLogger().info("========================================");
    }

    @Override
    public void onDisable() {
        getLogger().info("WebRegionAutomator をシャットダウンしています...");

        // RegionTaskのキャンセル
        if (regionTask != null) {
            regionTask.cancel();
        }

        // DatabaseManagerのクローズ
        if (databaseManager != null) {
            databaseManager.close();
            getLogger().info("✓ データベース接続プール クローズ");
        }

        getLogger().info("WebRegionAutomator シャットダウン完了");
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}