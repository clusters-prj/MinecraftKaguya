package com.clustersprj.fjeconomy.database;

import com.clustersprj.fjeconomy.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.logging.Level;

public class DatabaseManager {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private HikariDataSource dataSource;

    public DatabaseManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    /**
     * Initialize database connection pool
     */
    public void initialize() throws Exception {
        HikariConfig config = new HikariConfig();
        
        // JDBC URL から自動でドライバを検出させる
        // setDriverClassName を削除してドライバの自動検出を有効化
        config.setJdbcUrl(configManager.getDatabaseUrl());
        config.setUsername(configManager.getDatabaseUsername());
        config.setPassword(configManager.getDatabasePassword());
        config.setMaximumPoolSize(configManager.getDatabasePoolSize());
        config.setMaxLifetime(configManager.getDatabaseMaxLifetime());
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(600000);
        config.setAutoCommit(true);

        // Connection test
        try {
            this.dataSource = new HikariDataSource(config);
            testConnection();
            plugin.getLogger().info("✓ データベース接続に成功しました");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "データベース接続エラー", e);
            throw e;
        }
    }

    /**
     * Test database connection
     */
    private void testConnection() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
        }
    }

    /**
     * Create or verify database tables
     */
    public void createTables() throws SQLException {
        try (Connection conn = getConnection()) {
            // fje_balances
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS fje_balances (" +
                    "  uuid UUID PRIMARY KEY DEFAULT '00000000-0000-0000-0000-000000000000'," +
                    "  name VARCHAR(255) NOT NULL," +
                    "  balance BIGINT NOT NULL DEFAULT 0," +
                    "  last_update TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "  INDEX idx_name (name)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // fje_shops
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS fje_shops (" +
                    "  npc_id INT NOT NULL," +
                    "  server_id VARCHAR(20) NOT NULL," +
                    "  owner_uuid UUID NOT NULL," +
                    "  item_material VARCHAR(255) NOT NULL," +
                    "  item_nbt TEXT," +
                    "  price INT NOT NULL DEFAULT 0," +
                    "  stock INT NOT NULL DEFAULT 0," +
                    "  PRIMARY KEY (npc_id, server_id)," +
                    "  INDEX idx_owner (owner_uuid)," +
                    "  INDEX idx_item (item_material)," +
                    "  FOREIGN KEY (owner_uuid) REFERENCES fje_balances(uuid)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // fje_transactions
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS fje_transactions (" +
                    "  id INT AUTO_INCREMENT PRIMARY KEY," +
                    "  timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "  server_id VARCHAR(20) NOT NULL," +
                    "  buyer_uuid UUID NOT NULL," +
                    "  owner_uuid UUID NOT NULL," +
                    "  item_id VARCHAR(255) NOT NULL," +
                    "  amount INT NOT NULL DEFAULT 1," +
                    "  price_total INT NOT NULL DEFAULT 0," +
                    "  tax_amount INT NOT NULL DEFAULT 0," +
                    "  net_profit INT NOT NULL DEFAULT 0," +
                    "  INDEX idx_timestamp (timestamp)," +
                    "  INDEX idx_buyer (buyer_uuid)," +
                    "  INDEX idx_owner (owner_uuid)," +
                    "  FOREIGN KEY (buyer_uuid) REFERENCES fje_balances(uuid)," +
                    "  FOREIGN KEY (owner_uuid) REFERENCES fje_balances(uuid)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // fje_government_ledger
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS fje_government_ledger (" +
                    "  id INT AUTO_INCREMENT PRIMARY KEY," +
                    "  timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "  type VARCHAR(20) NOT NULL," +
                    "  amount BIGINT NOT NULL DEFAULT 0," +
                    "  description TEXT," +
                    "  INDEX idx_timestamp (timestamp)," +
                    "  INDEX idx_type (type)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            plugin.getLogger().info("✓ テーブルを確認/作成しました");

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "テーブル作成エラー", e);
            throw e;
        }
    }

    /**
     * Execute an update statement
     */
    private void executeUpdate(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /**
     * Get a database connection
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("DataSource is closed");
        }
        return dataSource.getConnection();
    }

    /**
     * Execute query asynchronously
     */
    public void executeAsync(String sql, QueryCallback callback) {
        new Thread(() -> {
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery(sql);
                callback.onResult(rs);
            } catch (SQLException e) {
                callback.onError(e);
            }
        }).start();
    }

    /**
     * Execute update asynchronously
     */
    public void executeUpdateAsync(String sql, UpdateCallback callback) {
        new Thread(() -> {
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                int affectedRows = stmt.executeUpdate(sql);
                callback.onResult(affectedRows);
            } catch (SQLException e) {
                callback.onError(e);
            }
        }).start();
    }

    /**
     * Shutdown database connection pool
     */
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("データベース接続を閉じました");
        }
    }

    /**
     * Callback interfaces for async queries
     */
    public interface QueryCallback {
        void onResult(ResultSet rs) throws SQLException;
        void onError(SQLException e);
    }

    public interface UpdateCallback {
        void onResult(int affectedRows);
        void onError(SQLException e);
    }
}