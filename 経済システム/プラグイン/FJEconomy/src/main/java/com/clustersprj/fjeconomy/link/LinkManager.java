package com.clustersprj.fjeconomy.link;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Webアカウント連携用のワンタイムコード管理
 *
 * link_codes テーブルは Web 側 (server.js) と共有しており、
 * プレイヤーが /fj link で発行したコードを Web サイト側で入力することで
 * account_links テーブルに Minecraft UUID と Web アカウントが紐づけられる。
 */
public class LinkManager {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_GENERATE_ATTEMPTS = 5;

    private final FJEconomy plugin;
    private final DatabaseManager dbManager;
    private final ConfigManager configManager;

    public LinkManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
        this.configManager = plugin.getConfigManager();
    }

    /**
     * 指定プレイヤー用の連携コードを新規発行する。
     * 同じプレイヤーが持っていた古いコードは削除してから発行する。
     *
     * @param playerUUID 連携対象のマイクラUUID
     * @return 発行された6桁のコード。失敗した場合は null
     */
    public String generateLinkCode(UUID playerUUID) {
        int expiryMinutes = configManager.getAccountLinkCodeExpiryMinutes();

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // 期限切れのコードを掃除する。
                // Web側 (POST /api/auth/link) は消費時にしか削除しないため、
                // 入力されなかったコードが残り続ける。code は6桁のPRIMARY KEYで
                // 空間が90万通りしかなく、溜まると採番の衝突リトライが増えて
                // 最終的にコード発行そのものが失敗するようになる。
                try (PreparedStatement expired = conn.prepareStatement(
                        "DELETE FROM link_codes WHERE expires_at <= NOW()")) {
                    expired.executeUpdate();
                }

                // 同じプレイヤーの古いコードを一旦削除（複数コードが溜まらないように）
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM link_codes WHERE minecraft_uuid = ?")) {
                    del.setString(1, playerUUID.toString());
                    del.executeUpdate();
                }

                String code = insertNewCode(conn, playerUUID, expiryMinutes);

                conn.commit();
                return code;

            } catch (Exception e) {
                conn.rollback();
                plugin.getLogger().log(Level.WARNING, "連携コード発行エラー", e);
                return null;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "連携コード発行時のDB接続エラー", e);
            return null;
        }
    }

    /**
     * 6桁のコードを生成してINSERTする。code は PRIMARY KEY なので、
     * まれな衝突が起きた場合は数回まで再試行する。
     */
    private String insertNewCode(Connection conn, UUID playerUUID, int expiryMinutes) throws SQLException {
        SQLException lastError = null;

        for (int attempt = 0; attempt < MAX_GENERATE_ATTEMPTS; attempt++) {
            String code = generateRandomCode();

            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO link_codes (code, minecraft_uuid, expires_at) " +
                    "VALUES (?, ?, DATE_ADD(NOW(), INTERVAL ? MINUTE))")) {
                stmt.setString(1, code);
                stmt.setString(2, playerUUID.toString());
                stmt.setInt(3, expiryMinutes);
                stmt.executeUpdate();
                return code;
            } catch (SQLException e) {
                // 主キー重複（同じコードが既に存在）した場合のみ再試行、それ以外は即座に投げる
                if (isDuplicateKeyError(e)) {
                    lastError = e;
                    continue;
                }
                throw e;
            }
        }

        throw lastError != null ? lastError : new SQLException("コードの生成に失敗しました");
    }

    private boolean isDuplicateKeyError(SQLException e) {
        // MariaDB/MySQLの重複キーエラー (SQLState: 23000)
        return "23000".equals(e.getSQLState());
    }

    private String generateRandomCode() {
        int number = 100000 + RANDOM.nextInt(900000); // 100000〜999999
        return Integer.toString(number);
    }
}