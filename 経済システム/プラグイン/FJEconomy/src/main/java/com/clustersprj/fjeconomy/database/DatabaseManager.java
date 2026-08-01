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
        // ★ 先にドライバクラスを強制的にロードしてクラスローダーに認識させる
        try {
            Class.forName("com.clustersprj.fjeconomy.libs.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("MariaDB JDBC Driver が見つかりません。シャドウの設定を確認してください。");
            throw e;
        }

        HikariConfig config = new HikariConfig();
        
        // pom.xml の <shadedPattern> で指定した名前に合わせます
        config.setDriverClassName("com.clustersprj.fjeconomy.libs.mariadb.jdbc.Driver");

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
            // ★ HikariDataSourceのインスタンスを生成
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
                    "  player_name VARCHAR(255) NOT NULL," +
                    "  balance BIGINT NOT NULL DEFAULT 0," +
                    "  last_update TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "  INDEX idx_name (player_name)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // fje_shops (npc_id から npc_uuid UUID に変更)
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS fje_shops (" +
                    "  npc_uuid UUID NOT NULL," +
                    "  server_id VARCHAR(20) NOT NULL," +
                    "  owner_uuid UUID NOT NULL," +
                    "  item_material VARCHAR(255) NOT NULL," +
                    "  item_nbt TEXT," +
                    "  price INT NOT NULL DEFAULT 0," +
                    "  stock INT NOT NULL DEFAULT 0," +
                    "  PRIMARY KEY (npc_uuid, server_id)," +
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

            // fje_login_bonuses
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS fje_login_bonuses (" +
                    "  uuid UUID PRIMARY KEY," +
                    "  last_bonus_claim TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "  FOREIGN KEY (uuid) REFERENCES fje_balances(uuid) ON DELETE CASCADE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // link_codes（Webアカウント連携用ワンタイムコード。Web側(server.js)と共有するテーブル）
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS link_codes (" +
                    "  code VARCHAR(6) PRIMARY KEY," +
                    "  minecraft_uuid VARCHAR(36) NOT NULL," +
                    "  expires_at TIMESTAMP NOT NULL," +
                    "  INDEX idx_uuid (minecraft_uuid)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // fje_arena_events（アリーナ監視イベント。Web管理画面から作成される）
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS fje_arena_events (" +
                    "  id INT AUTO_INCREMENT PRIMARY KEY," +
                    "  name VARCHAR(100) NOT NULL," +
                    "  world VARCHAR(64) NOT NULL," +
                    "  center_x DOUBLE NOT NULL," +
                    "  center_y DOUBLE NOT NULL," +
                    "  center_z DOUBLE NOT NULL," +
                    "  radius DOUBLE NOT NULL," +
                    "  prize_amount BIGINT NOT NULL DEFAULT 0," +
                    "  status ENUM('ACTIVE','RESOLVED','CANCELLED') NOT NULL DEFAULT 'ACTIVE'," +
                    "  winner_uuid UUID NULL," +
                    "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "  resolved_at TIMESTAMP NULL DEFAULT NULL," +
                    "  INDEX idx_status (status)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // fje_arena_participants（アリーナイベントの対戦カード）
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS fje_arena_participants (" +
                    "  event_id INT NOT NULL," +
                    "  minecraft_uuid UUID NOT NULL," +
                    "  player_name VARCHAR(255) NOT NULL," +
                    "  PRIMARY KEY (event_id, minecraft_uuid)," +
                    "  FOREIGN KEY (event_id) REFERENCES fje_arena_events(id) ON DELETE CASCADE," +
                    "  FOREIGN KEY (minecraft_uuid) REFERENCES fje_balances(uuid)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // fje_arena_bets（優勝者予想ベット）
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS fje_arena_bets (" +
                    "  id INT AUTO_INCREMENT PRIMARY KEY," +
                    "  event_id INT NOT NULL," +
                    "  bettor_uuid UUID NOT NULL," +
                    "  predicted_uuid UUID NOT NULL," +
                    "  amount BIGINT NOT NULL," +
                    "  status ENUM('PLACED','WON','LOST','REFUNDED') NOT NULL DEFAULT 'PLACED'," +
                    "  payout_amount BIGINT NULL," +
                    "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "  INDEX idx_event (event_id)," +
                    "  INDEX idx_bettor (bettor_uuid)," +
                    "  FOREIGN KEY (event_id) REFERENCES fje_arena_events(id) ON DELETE CASCADE," +
                    "  FOREIGN KEY (bettor_uuid) REFERENCES fje_balances(uuid)," +
                    "  FOREIGN KEY (predicted_uuid) REFERENCES fje_balances(uuid)" +
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
     * Execute query asynchronously (Bukkit Scheduler を使用)
     */
    public void executeAsync(String sql, QueryCallback callback) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery(sql);
                callback.onResult(rs);
            } catch (SQLException e) {
                callback.onError(e);
            }
        });
    }

    /**
     * Execute update asynchronously (Bukkit Scheduler を使用)
     */
    public void executeUpdateAsync(String sql, UpdateCallback callback) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                int affectedRows = stmt.executeUpdate(sql);
                callback.onResult(affectedRows);
            } catch (SQLException e) {
                callback.onError(e);
            }
        });
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