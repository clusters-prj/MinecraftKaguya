package com.clustersprj.fjeconomy;

import com.clustersprj.fjeconomy.command.CommandManager;
import com.clustersprj.fjeconomy.command.GovernmentCommand;
import com.clustersprj.fjeconomy.command.ShopCommand;
import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.economy.EconomyManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import com.clustersprj.fjeconomy.government.GovernmentManager;
import com.clustersprj.fjeconomy.link.LinkManager;
import com.clustersprj.fjeconomy.shop.ShopManager;
import com.clustersprj.fjeconomy.shop.ShopUI; // 追加
import com.clustersprj.fjeconomy.listener.PlayerListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class FJEconomy extends JavaPlugin {

    private static FJEconomy instance;
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private EconomyManager economyManager; // 追加
    private CommandManager commandManager;
    private ShopManager shopManager; // 追加
    private GovernmentManager governmentManager;
    private LinkManager linkManager; // 追加
    private LoginBonusManager loginBonusManager; // 追加

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

            // EconomyManager initialization
            this.economyManager = new EconomyManager(this); // 追加
            getLogger().info("✓ 経済システムを初期化しました");

            // Command registration
            this.commandManager = new CommandManager(this);
            commandManager.registerCommands();
            getLogger().info("✓ コマンドを登録しました");

            // Shop system initialization
            this.shopManager = new ShopManager(this); // 追加
            getLogger().info("✓ ショップシステムを初期化しました");

            // Shop command registration
            ShopCommand shopCommand = new ShopCommand(this, shopManager);
            getCommand("shop").setExecutor(shopCommand);
            getCommand("shop").setTabCompleter(shopCommand);
            getLogger().info("✓ ショップコマンドを登録しました");

            // Government system initialization
            this.governmentManager = new GovernmentManager(this);
            governmentManager.initialize();
            getLogger().info("✓ 政府システムを初期化しました");

            // Government command registration
            GovernmentCommand govCommand = new GovernmentCommand(this, governmentManager);
            getCommand("fjegovernment").setExecutor(govCommand);
            getCommand("fjegovernment").setTabCompleter(govCommand);
            getLogger().info("✓ 政府コマンドを登録しました");

            // LoginBonusManager initialization
            this.loginBonusManager = new LoginBonusManager(this); // 追加
            getLogger().info("✓ ログインボーナスシステムを初期化しました");

            // LinkManager initialization（Webアカウント連携）
            this.linkManager = new LinkManager(this);
            getLogger().info("✓ アカウント連携システムを初期化しました");

            // Event listener registration
            getServer().getPluginManager().registerEvents(new PlayerListener(this), this); // PlayerListenerのコンストラクタ変更に対応
            getServer().getPluginManager().registerEvents(new ShopUI(this), this); // ShopUIを登録
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

    public EconomyManager getEconomyManager() { // 追加
        return economyManager;
    }

    public ShopManager getShopManager() { // 追加
        return shopManager;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public GovernmentManager getGovernmentManager() {
        return governmentManager;
    }
    
    public LoginBonusManager getLoginBonusManager() { // 追加
        return loginBonusManager;
    }

    public LinkManager getLinkManager() {
        return linkManager;
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