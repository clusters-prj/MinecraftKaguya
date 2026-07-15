package com.clustersprj.fjeconomy.config;

import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * FJEconomyプラグインの設定ファイルを管理するクラスです。
 * YAML形式の設定ファイル（config.yml）の読み込み、値の取得、デフォルト設定の生成などを行います。
 * ドット区切り（例: "database.url"）による階層的な値の取得をサポートしています。
 */
public class ConfigManager {

    /** プラグインのインスタンス */
    private final JavaPlugin plugin;
    
    /** 設定ファイル（config.yml）の保存先パス */
    private final Path configPath;
    
    /** 現在読み込まれている設定データのマップ */
    private Map<String, Object> config = new HashMap<>();
    
    /** 前回読み込まれた設定データのマップ（変更検知に使用） */
    private Map<String, Object> previousConfig = new HashMap<>();

    /**
     * ConfigManagerの新しいインスタンスを構築します。
     *
     * @param plugin 対象のJavaPluginインスタンス
     */
    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.configPath = plugin.getDataFolder().toPath().resolve("config.yml");
    }

    /**
     * 設定ファイルを読み込みます（存在しない場合は新規作成します）。
     * 以前の設定は変更検知のために `previousConfig` に退避されます。
     *
     * @throws IOException ディレクトリの作成やファイルの読み込みに失敗した場合
     */
    public void loadConfig() throws IOException {
        // データフォルダが存在しない場合は作成
        Files.createDirectories(plugin.getDataFolder().toPath());

        // 設定ファイルが存在しない場合はリソースからコピー
        if (!Files.exists(configPath)) {
            createDefaultConfig();
        }

        // 比較用に前回の設定を保存
        this.previousConfig = new HashMap<>(config != null ? config : new HashMap<>());

        // 設定の読み込み
        try (InputStream is = Files.newInputStream(configPath)) {
            Yaml yaml = new Yaml();

            Object loaded = yaml.load(is);

            if (loaded instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) map;
                this.config = casted;
            } else {
                this.config = new HashMap<>();
            }
        }

        plugin.getLogger().info("設定を読み込みました: " + configPath);
    }

    /**
     * プラグインのJARリソースからデフォルトの config.yml を作成します。
     *
     * @throws IOException ファイルの書き出しに失敗した場合
     */
    private void createDefaultConfig() throws IOException {
        plugin.saveResource("config.yml", false);
        plugin.getLogger().info("デフォルト設定ファイルを作成しました");
    }

    /**
     * 指定されたパスから文字列の値を取得します。
     *
     * @param path キーのパス（例: "server.name"）
     * @param defaultValue 値が存在しない、または型が異なる場合のデフォルト値
     * @return 取得した文字列、またはデフォルト値
     */
    public String getString(String path, String defaultValue) {
        Object value = getByPath(path);
        return value instanceof String ? (String) value : defaultValue;
    }

    /**
     * 指定されたパスから整数の値を取得します。
     *
     * @param path キーのパス（例: "database.pool_size"）
     * @param defaultValue 値が存在しない、または型が異なる場合のデフォルト値
     * @return 取得した整数、またはデフォルト値
     */
    public int getInt(String path, int defaultValue) {
        Object value = getByPath(path);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    /**
     * 指定されたパスからLong型の値を取得します。
     *
     * @param path キーのパス（例: "login_bonus.amount"）
     * @param defaultValue 値が存在しない、または型が異なる場合のデフォルト値
     * @return 取得したLong値、またはデフォルト値
     */
    public long getLong(String path, long defaultValue) {
        Object value = getByPath(path);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }

    /**
     * 指定されたパスからDouble型の値を取得します。
     *
     * @param path キーのパス（例: "economy.tax_rate"）
     * @param defaultValue 値が存在しない、または型が異なる場合のデフォルト値
     * @return 取得したDouble値、またはデフォルト値
     */
    public double getDouble(String path, double defaultValue) {
        Object value = getByPath(path);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    /**
     * 指定されたパスから真偽値を取得します。
     *
     * @param path キーのパス（例: "economy.allow_negative"）
     * @param defaultValue 値が存在しない、または型が異なる場合のデフォルト値
     * @return 取得した真偽値、またはデフォルト値
     */
    public boolean getBoolean(String path, boolean defaultValue) {
        Object value = getByPath(path);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    /**
     * 指定されたパスからセクション（ネストされたマップ）を取得します。
     *
     * @param path キーのパス
     * @return 取得したセクションのマップ。存在しない場合は空のマップ
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSection(String path) {
        Object value = getByPath(path);
        return value instanceof Map ? (Map<String, Object>) value : new HashMap<>();
    }

    /**
     * ドット記法（例: "database.url"）を用いて、設定マップからオブジェクトを探索します。
     *
     * @param path 探索するキーのパス
     * @return 見つかったオブジェクト。存在しない場合は null
     */
    @SuppressWarnings("unchecked")
    private Object getByPath(String path) {
        if (config == null) {
            return null;
        }

        String[] keys = path.split("\\.");
        Object current = config;

        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
                if (current == null) {
                    return null;
                }
            } else {
                return null;
            }
        }

        return current;
    }

    /**
     * データベースへの接続設定（URL、ユーザー名）が変更されたかどうかを検証します。
     *
     * @return 設定が変更されている場合は true、変更がない場合は false
     */
    public boolean isDatabaseConfigChanged() {
        String oldUrl = getStringFromMap(previousConfig, "database.url");
        String newUrl = getString("database.url", "");

        String oldUser = getStringFromMap(previousConfig, "database.username");
        String newUser = getString("database.username", "");

        return !oldUrl.equals(newUrl)
                || !oldUser.equals(newUser);
    }

    /**
     * 指定されたマップから、ドット記法を用いて文字列を取得するヘルパーメソッドです。
     *
     * @param map 探索対象のマップ
     * @param path キーのパス
     * @return 取得した文字列。見つからない、または文字列でない場合は空文字 ("")
     */
    @SuppressWarnings("unchecked")
    private String getStringFromMap(Map<String, Object> map, String path) {
        String[] keys = path.split("\\.");
        Object current = map;

        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
            } else {
                return "";
            }
        }

        return current instanceof String ? (String) current : "";
    }

    // ==========================================
    // データベース設定 ゲッター
    // ==========================================

    /**
     * データベースの接続URLを取得します。
     *
     * @return JDBC接続URL（デフォルト: jdbc:mariadb://localhost:3306/fjeconomy）
     */
    public String getDatabaseUrl() {
        return getString("database.url", "jdbc:mariadb://localhost:3306/fjeconomy");
    }

    /**
     * データベースの接続ユーザー名を取得します。
     *
     * @return ユーザー名（デフォルト: root）
     */
    public String getDatabaseUsername() {
        return getString("database.username", "root");
    }

    /**
     * データベースの接続パスワードを取得します。
     *
     * @return パスワード（デフォルト: 空文字）
     */
    public String getDatabasePassword() {
        return getString("database.password", "");
    }

    /**
     * データベース接続プール（HikariCPなど）の最大サイズを取得します。
     *
     * @return プールサイズ（デフォルト: 10）
     */
    public int getDatabasePoolSize() {
        return getInt("database.pool_size", 10);
    }

    /**
     * データベース接続の最大生存期間（ミリ秒）を取得します。
     *
     * @return 最大生存期間（デフォルト: 1800000L / 30分）
     */
    public long getDatabaseMaxLifetime() {
        return getLong("database.max_lifetime", 1800000L);
    }

    // ==========================================
    // サーバー設定 ゲッター
    // ==========================================

    /**
     * サーバーの一意識別子（ID）を取得します。
     *
     * @return サーバーID（デフォルト: mc1）
     */
    public String getServerId() {
        return getString("server.id", "mc1");
    }

    /**
     * 表示用のサーバー名を取得します。
     *
     * @return サーバー名（デフォルト: Main Server）
     */
    public String getServerName() {
        return getString("server.name", "Main Server");
    }

    // ==========================================
    // 通貨設定 ゲッター
    // ==========================================

    /**
     * 通貨記号（例: "¥", "$", "G"）を取得します。
     *
     * @return 通貨記号（デフォルト: ¥）
     */
    public String getCurrencySymbol() {
        return getString("currency.symbol", "¥");
    }

    /**
     * 通貨の名称（例: "FJ Credits", "円"）を取得します。
     *
     * @return 通貨名（デフォルト: FJ Credits）
     */
    public String getCurrencyName() {
        return getString("currency.name", "FJ Credits");
    }

    /**
     * 通貨の小数点以下の桁数を取得します。
     *
     * @return 小数点以下の表示桁数（デフォルト: 0）
     */
    public int getDecimalPlaces() {
        return getInt("currency.decimal_places", 0);
    }

    // ==========================================
    // 経済システム設定 ゲッター
    // ==========================================

    /**
     * 取引等にかかる税率（パーセント）を取得します。
     *
     * @return 税率（デフォルト: 10.0%）
     */
    public double getTaxRate() {
        return getDouble("economy.tax_rate", 10.0);
    }

    /**
     * 端数処理の方法を取得します（例: "HALF_UP"）。
     *
     * @return 丸め方法の文字列（デフォルト: HALF_UP）
     */
    public String getRoundingMethod() {
        return getString("economy.rounding_method", "HALF_UP");
    }

    /**
     * 新規プレイヤー作成時の初期所持金（初期バランス）を取得します。
     *
     * @return 初期所持金（デフォルト: 1000）
     */
    public int getStartingBalance() {
        return getInt("economy.starting_balance", 1000);
    }

    /**
     * マイナス残高（借金）を許容するかどうかを取得します。
     *
     * @return マイナスを許可する場合は true、しない場合は false（デフォルト: false）
     */
    public boolean isNegativeBalanceAllowed() {
        return getBoolean("economy.allow_negative", false);
    }

    // ==========================================
    // ショップ設定 ゲッター
    // ==========================================

    /**
     * ショップアイテムのデフォルト初期在庫数を取得します。
     *
     * @return デフォルトの在庫数（デフォルト: 100）
     */
    public int getDefaultStock() {
        return getInt("shop.default_stock", 100);
    }

    /**
     * グローバルまたは他サーバー間でのショップ価格同期が有効かどうかを取得します。
     *
     * @return 同期が有効な場合は true、無効な場合は false（デフォルト: true）
     */
    public boolean isSyncPricesEnabled() {
        return getBoolean("shop.sync_prices", true);
    }

    /**
     * ショップ価格の同期処理の実行間隔（秒）を取得します。
     *
     * @return 同期インターバル（デフォルト: 300秒 / 5分）
     */
    public int getSyncInterval() {
        return getInt("shop.sync_interval", 300);
    }

    // ==========================================
    // 政府（公共）アカウント設定 ゲッター
    // ==========================================

    /**
     * 政府・公共機関用のアカウントのUUID（文字列）を取得します。
     *
     * @return 政府のUUID（デフォルト: 00000000-0000-0000-0000-000000000001）
     */
    public String getGovernmentUUID() {
        return getString("government.uuid", "00000000-0000-0000-0000-000000000001");
    }

    /**
     * 政府・公共機関用のアカウント表示名を取得します。
     *
     * @return 政府名（デフォルト: GOVERNMENT）
     */
    public String getGovernmentName() {
        return getString("government.name", "GOVERNMENT");
    }

    // ==========================================
    // Web API設定 ゲッター
    // ==========================================

    /**
     * Web API連携が有効かどうかを取得します。
     *
     * @return 有効な場合は true、無効な場合は false（デフォルト: true）
     */
    public boolean isWebAPIEnabled() {
        return getBoolean("web_api.enabled", true);
    }

    /**
     * Web APIの接続先ベースURLを取得します。
     *
     * @return APIのベースURL（デフォルト: https://clusters-prj.com/api/economy）
     */
    public String getWebAPIBaseUrl() {
        return getString("web_api.base_url", "https://clusters-prj.com/api/economy");
    }

    /**
     * Web API接続用の認証トークンを取得します。
     *
     * @return APIトークン（デフォルト: 空文字）
     */
    public String getWebAPIToken() {
        return getString("web_api.token", "");
    }

    // ==========================================
    // ログインボーナス設定 ゲッター
    // ==========================================

    /**
     * ログインボーナスシステムが有効かどうかを取得します。
     *
     * @return 有効な場合は true、無効な場合は false（デフォルト: false）
     */
    public boolean isLoginBonusEnabled() {
        return getBoolean("login_bonus.enabled", false);
    }

    /**
     * ログイン時に付与されるボーナス額を取得します。
     *
     * @return ボーナス額（デフォルト: 100）
     */
    public long getLoginBonusAmount() {
        return getLong("login_bonus.amount", 100);
    }

    /**
     * ログインボーナスの受け取りクールダウン（時間）を取得します。
     *
     * @return クールダウン時間（デフォルト: 24時間）
     */
    public int getLoginBonusCooldownHours() {
        return getInt("login_bonus.cooldown_hours", 24);
    }

    // ==========================================
    // ロギング設定 ゲッター
    // ==========================================

    /**
     * プラグインの出力ログレベル（INFO, DEBUGなど）を取得します。
     *
     * @return ログレベル（デフォルト: INFO）
     */
    public String getLogLevel() {
        return getString("logging.level", "INFO");
    }

    /**
     * ログを出力・保存するファイルパスを取得します。
     *
     * @return ログファイルのパス（デフォルト: plugins/FJEconomy/logs/economy.log）
     */
    public String getLogFile() {
        return getString("logging.file", "plugins/FJEconomy/logs/economy.log");
    }

    /**
     * プレイヤー間や店舗での取引ログを出力するかどうかを取得します。
     *
     * @return 取引ログを出力する場合は true、しない場合は false（デフォルト: true）
     */
    public boolean isTransactionLogging() {
        return getBoolean("logging.log_transactions", true);
    }

    // ==========================================
    // メッセージ＆言語設定 ゲッター
    // ==========================================

    /**
     * チャット等に表示するプラグイン固有のメッセージ接頭辞（カラーコード対応プレフィックス）を取得します。
     *
     * @return メッセージプレフィックス（デフォルト: &7[&bFJ Economy&7] &r）
     */
    public String getMessagePrefix() {
        return getString("messages.prefix", "&7[&bFJ Economy&7] &r");
    }

    /**
     * 指定されたキーに紐づく言語メッセージを取得します。
     *
     * @param key メッセージの識別キー（例: "no_permission"）
     * @param defaultValue 設定に見つからない場合のデフォルトメッセージ
     * @return 翻訳またはカスタマイズされたメッセージ文字列、またはデフォルト値
     */
    public String getMessage(String key, String defaultValue) {
        return getString("messages." + key, defaultValue);
    }
    
    // Account link config getters
    public int getAccountLinkCodeExpiryMinutes() {
        return getInt("account_link.code_expiry_minutes", 10);
    }
 
    public String getAccountLinkWebsiteUrl() {
        return getString("account_link.website_url", "https://fjew.clusters-prj.com/");
    }
}
