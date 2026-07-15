package com.clustersprj.fjeconomy;

import com.clustersprj.fjeconomy.command.CommandManager;
import com.clustersprj.fjeconomy.command.GovernmentCommand;
import com.clustersprj.fjeconomy.command.ShopCommand;
import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.economy.EconomyManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import com.clustersprj.fjeconomy.government.GovernmentManager;
import com.clustersprj.fjeconomy.shop.ShopManager;
import com.clustersprj.fjeconomy.shop.ShopUI; // 追加
import com.clustersprj.fjeconomy.listener.PlayerListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * FJ Economy プラグインのメインクラスです。
 * プラグインの有効化（起動）、無効化（終了）、および各管理マネージャーの初期化と提供を担当します。
 * * @author clustersprj
 * @version 1.0.0
 */
public class FJEconomy extends JavaPlugin {

    /** プラグインのシングルトンインスタンス */
    private static FJEconomy instance;

    /** 設定ファイルを管理するマネージャー */
    private ConfigManager configManager;

    /** データベースへの接続と初期化を管理するマネージャー */
    private DatabaseManager databaseManager;

    /** 経済（お金）システムを処理するマネージャー */
    private EconomyManager economyManager; // 追加

    /** コマンドの登録を統括するマネージャー */
    private CommandManager commandManager;

    /** ショップ機能を管理するマネージャー */
    private ShopManager shopManager; // 追加

    /** 政府システムを管理するマネージャー */
    private GovernmentManager governmentManager;

    /** ログインボーナスシステムを管理するマネージャー */
    private LoginBonusManager loginBonusManager; // 追加

    /**
     * プラグインが有効化（サーバー起動時またはリロード時）された際に呼び出されます。
     * 各マネージャーのインスタンス生成、データベース接続、コマンドやイベントリスナーの登録を行います。
     */
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

    /**
     * プラグインが無効化（サーバー停止時またはリロード時）された際に呼び出されます。
     * データベース接続の安全な切断などの後処理を行います。
     */
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

    /**
     * プラグインのシングルトンインスタンスを取得します。
     *
     * @return FJEconomy プラグインのインスタンス
     */
    public static FJEconomy getInstance() {
        return instance;
    }

    /**
     * 設定マネージャーを取得します。
     *
     * @return ConfigManagerのインスタンス
     */
    public ConfigManager getConfigManager() {
        return configManager;
    }

    /**
     * データベースマネージャーを取得します。
     *
     * @return DatabaseManagerのインスタンス
     */
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    /**
     * 経済システムマネージャーを取得します。
     *
     * @return EconomyManagerのインスタンス
     */
    public EconomyManager getEconomyManager() { // 追加
        return economyManager;
    }

    /**
     * ショップマネージャーを取得します。
     *
     * @return ShopManagerのインスタンス
     */
    public ShopManager getShopManager() { // 追加
        return shopManager;
    }

    /**
     * コマンドマネージャーを取得します。
     *
     * @return CommandManagerのインスタンス
     */
    public CommandManager getCommandManager() {
        return commandManager;
    }

    /**
     * 政府システムマネージャーを取得します。
     *
     * @return GovernmentManagerのインスタンス
     */
    public GovernmentManager getGovernmentManager() {
        return governmentManager;
    }
    
    /**
     * ログインボーナスマネージャーを取得します。
     *
     * @return LoginBonusManagerのインスタンス
     */
    public LoginBonusManager getLoginBonusManager() { // 追加
        return loginBonusManager;
    }

    /**
     * プラグインの設定ファイルとデータベース接続をリロード（再読み込み）します。
     * 設定に変更があった場合は、データベースへの再接続も自動で行います。
     * * @throws RuntimeException リロード処理中にエラーが発生した場合
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
