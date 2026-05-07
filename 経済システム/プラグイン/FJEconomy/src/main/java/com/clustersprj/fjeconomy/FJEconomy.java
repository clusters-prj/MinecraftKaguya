package com.clustersprj.fjeconomy;

import com.clustersprj.fjeconomy.command.CommandManager;
import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import com.clustersprj.fjeconomy.listener.PlayerListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class FJEconomy extends JavaPlugin {

    private static FJEconomy instance;
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private CommandManager commandManager;

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("===================================");
        getLogger().info("FJ Economy v" + getDescription().getVersion() + " を読み込み中...");
        getLogger().info("===================================");

        try {
            // Config loading
            this.configManager = new ConfigManager(this);
            configManager.loadConfig();
            getLogger().info("✓ 設定ファイルを読み込みました");

            // Database initialization
            this.databaseManager = new DatabaseManager(this, configManager);
            databaseManager.initialize();
            getLogger().info("✓ データベースに接続しました");

            // Create tables
            databaseManager.createTables();
            getLogger().info("✓ テーブルを作成/確認しました");

            // Command registration
            this.commandManager = new CommandManager(this);
            commandManager.registerCommands();
            getLogger().info("✓ コマンドを登録しました");

            // Event listener registration
            getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
            getLogger().info("✓ イベントリスナーを登録しました");

            getLogger().info("===================================");
            getLogger().info("FJ Economy が有効になりました");
            getLogger().info("===================================");

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "プラグイン初期化エラー", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("===================================");
        getLogger().info("FJ Economy を無効化しています...");

        if (databaseManager != null) {
            databaseManager.shutdown();
            getLogger().info("✓ データベース接続を切断しました");
        }

        getLogger().info("===================================");
        getLogger().info("FJ Economy が無効になりました");
        getLogger().info("===================================");
    }

    // Static accessor
    public static FJEconomy getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    /**
     * Reload plugin configuration and database
     */
    public void reloadPlugin() {
        try {
            getLogger().info("FJ Economy をリロード中...");

            // Reload config
            configManager.loadConfig();
            getLogger().info("✓ 設定ファイルを再読み込みしました");

            // Reconnect database if needed
            if (configManager.isDatabaseConfigChanged()) {
                if (databaseManager != null) {
                    databaseManager.shutdown();
                }
                databaseManager = new DatabaseManager(this, configManager);
                databaseManager.initialize();
                getLogger().info("✓ データベース接続を再確立しました");
            }

            getLogger().info("✓ FJ Economy をリロードしました");

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "リロード中にエラーが発生しました", e);
            throw new RuntimeException("Reload failed", e);
        }
    }
}
