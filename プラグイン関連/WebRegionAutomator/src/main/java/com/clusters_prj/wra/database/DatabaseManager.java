package com.clusters_prj.wra.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

// Bukkitのコンフィグセクション用
import org.bukkit.configuration.ConfigurationSection;

// WorldGuardのフラグシステム用
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;

import java.sql.*;
import java.util.*;

public class DatabaseManager {

    private final JavaPlugin plugin;
    private final HikariDataSource dataSource;

    public DatabaseManager(JavaPlugin plugin) throws SQLException {
        this.plugin = plugin;

        String host = plugin.getConfig().getString("database.host", "localhost");
        int port = plugin.getConfig().getInt("database.port", 3306);
        String database = plugin.getConfig().getString("database.database", "minecraft_network");
        String username = plugin.getConfig().getString("database.username", "root");
        String password = plugin.getConfig().getString("database.password", "");
        int maxPoolSize = plugin.getConfig().getInt("database.maximum-pool-size", 5);
        int minIdle = plugin.getConfig().getInt("database.minimum-idle", 2);
        long connectionTimeout = plugin.getConfig().getLong("database.connection-timeout", 10000);
        long idleTimeout = plugin.getConfig().getLong("database.idle-timeout", 600000);
        long maxLifetime = plugin.getConfig().getLong("database.max-lifetime", 1800000);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&serverTimezone=UTC");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(connectionTimeout);
        config.setIdleTimeout(idleTimeout);
        config.setMaxLifetime(maxLifetime);
        config.setAutoCommit(true);

        this.dataSource = new HikariDataSource(config);

        plugin.getLogger().info("HikariCP接続プール初期化: " + host + ":" + port + "/" + database);
    }

    /**
     * 未処理の保護申請を取得
     */
    public List<Map<String, Object>> fetchUnprocessedRequests(String serverId, int limit) {
        List<Map<String, Object>> requests = new ArrayList<>();
        String query = "SELECT id, player_uuid, region_id, world_name, x1, y1, z1, x2, y2, z2 " +
                       "FROM protection_requests " +
                       "WHERE server_id = ? AND status = 0 " +
                       "ORDER BY created_at ASC " +
                       "LIMIT ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, serverId);
            stmt.setInt(2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("player_uuid", rs.getString("player_uuid"));
                    row.put("region_id", rs.getString("region_id"));
                    row.put("world_name", rs.getString("world_name"));
                    row.put("x1", rs.getInt("x1"));
                    row.put("y1", rs.getInt("y1"));
                    row.put("z1", rs.getInt("z1"));
                    row.put("x2", rs.getInt("x2"));
                    row.put("y2", rs.getInt("y2"));
                    row.put("z2", rs.getInt("z2"));
                    requests.add(row);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB読み込みエラー: " + e.getMessage());
        }

        return requests;
    }

    /**
     * ステータスを更新（成功）
     */
    public void updateRequestStatusSuccess(int requestId) {
        String query = "UPDATE protection_requests SET status = 1, error_message = NULL WHERE id = ?";
        executeUpdate(query, stmt -> stmt.setInt(1, requestId));
    }

    /**
     * ステータスを更新（エラー）
     */
    public void updateRequestStatusError(int requestId, String errorMessage) {
        String query = "UPDATE protection_requests SET status = 2, error_message = ? WHERE id = ?";
        executeUpdate(query, stmt -> {
            stmt.setString(1, errorMessage);
            stmt.setInt(2, requestId);
        });
    }

    /**
     * 汎用UPDATE実行メソッド
     */
    private void executeUpdate(String query, StatementSetter setter) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            setter.setStatement(stmt);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("DB更新エラー: " + e.getMessage());
        }
    }

    /**
     * コネクションプールをクローズ
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @FunctionalInterface
    private interface StatementSetter {
        void setStatement(PreparedStatement stmt) throws SQLException;
    }
}