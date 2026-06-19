package com.clustersprj.fjeconomy.config;

import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ConfigManager {

    private final JavaPlugin plugin;
    private final Path configPath;
    private Map<String, Object> config = new HashMap<>();
    private Map<String, Object> previousConfig = new HashMap<>();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.configPath = plugin.getDataFolder().toPath().resolve("config.yml");
    }

    /**
     * Load or create configuration
     */
    public void loadConfig() throws IOException {
        // Create data folder if it doesn't exist
        Files.createDirectories(plugin.getDataFolder().toPath());

        // If config doesn't exist, copy default from resources
        if (!Files.exists(configPath)) {
            createDefaultConfig();
        }

        // Store previous config for comparison
        this.previousConfig = new HashMap<>(config != null ? config : new HashMap<>());

        // Load config
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
     * Create default config from plugin resources
     */
    private void createDefaultConfig() throws IOException {
        plugin.saveResource("config.yml", false);
        plugin.getLogger().info("デフォルト設定ファイルを作成しました");
    }

    /**
     * Get a string value
     */
    public String getString(String path, String defaultValue) {
        Object value = getByPath(path);
        return value instanceof String ? (String) value : defaultValue;
    }

    /**
     * Get an integer value
     */
    public int getInt(String path, int defaultValue) {
        Object value = getByPath(path);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    /**
     * Get a long value
     */
    public long getLong(String path, long defaultValue) {
        Object value = getByPath(path);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }

    /**
     * Get a double value
     */
    public double getDouble(String path, double defaultValue) {
        Object value = getByPath(path);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    /**
     * Get a boolean value
     */
    public boolean getBoolean(String path, boolean defaultValue) {
        Object value = getByPath(path);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    /**
     * Get a section (nested map)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSection(String path) {
        Object value = getByPath(path);
        return value instanceof Map ? (Map<String, Object>) value : new HashMap<>();
    }

    /**
     * Navigate to a value using dot notation (e.g., "database.url")
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
     * Check if database configuration has changed
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
     * Helper to get value from a map using dot notation
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

    // Database config getters
    public String getDatabaseUrl() {
        return getString("database.url", "jdbc:mariadb://localhost:3306/fjeconomy");
    }

    public String getDatabaseUsername() {
        return getString("database.username", "root");
    }

    public String getDatabasePassword() {
        return getString("database.password", "");
    }

    public int getDatabasePoolSize() {
        return getInt("database.pool_size", 10);
    }

    public long getDatabaseMaxLifetime() {
        return getLong("database.max_lifetime", 1800000L);
    }

    // Server config getters
    public String getServerId() {
        return getString("server.id", "mc1");
    }

    public String getServerName() {
        return getString("server.name", "Main Server");
    }

    // Currency config getters
    public String getCurrencySymbol() {
        return getString("currency.symbol", "¥");
    }

    public String getCurrencyName() {
        return getString("currency.name", "FJ Credits");
    }

    public int getDecimalPlaces() {
        return getInt("currency.decimal_places", 0);
    }

    // Economy config getters
    public double getTaxRate() {
        return getDouble("economy.tax_rate", 10.0);
    }

    public String getRoundingMethod() {
        return getString("economy.rounding_method", "HALF_UP");
    }

    public int getStartingBalance() {
        return getInt("economy.starting_balance", 1000);
    }

    public boolean isNegativeBalanceAllowed() {
        return getBoolean("economy.allow_negative", false);
    }

    // Shop config getters
    public int getDefaultStock() {
        return getInt("shop.default_stock", 100);
    }

    public boolean isSyncPricesEnabled() {
        return getBoolean("shop.sync_prices", true);
    }

    public int getSyncInterval() {
        return getInt("shop.sync_interval", 300);
    }

    // Government config getters
    public String getGovernmentUUID() {
        return getString("government.uuid", "00000000-0000-0000-0000-000000000001");
    }

    public String getGovernmentName() {
        return getString("government.name", "GOVERNMENT");
    }

    // Web API config getters
    public boolean isWebAPIEnabled() {
        return getBoolean("web_api.enabled", true);
    }

    // Login bonus config getters
    public boolean isLoginBonusEnabled() {
        return getBoolean("login_bonus.enabled", false);
    }

    public long getLoginBonusAmount() {
        return getLong("login_bonus.amount", 100);
    }

    public int getLoginBonusCooldownHours() {
        return getInt("login_bonus.cooldown_hours", 24);
    }

    public String getWebAPIBaseUrl() {
        return getString("web_api.base_url", "https://clusters-prj.com/api/economy");
    }

    public String getWebAPIToken() {
        return getString("web_api.token", "");
    }

    // Logging config getters
    public String getLogLevel() {
        return getString("logging.level", "INFO");
    }

    public String getLogFile() {
        return getString("logging.file", "plugins/FJEconomy/logs/economy.log");
    }

    public boolean isTransactionLogging() {
        return getBoolean("logging.log_transactions", true);
    }

    // Message getters
    public String getMessagePrefix() {
        return getString("messages.prefix", "&7[&bFJ Economy&7] &r");
    }

    public String getMessage(String key, String defaultValue) {
        return getString("messages." + key, defaultValue);
    }
}
