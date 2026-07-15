package com.clustersprj.fjeconomy.database;

import com.clustersprj.fjeconomy.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.logging.Level;

/**
 * プラグインで使用するデータベース（MariaDB/MySQL）への接続管理およびテーブル制御を行うマネージャークラスです。
 * <p>
 * 高性能な接続プール（HikariCP）の維持管理、起動時の自動テーブル構築、
 * およびサーバーのメインスレッドをブロックしないための非同期クエリ実行用ヘルパーを提供します。
 * </p>
 */
public class DatabaseManager {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private HikariDataSource dataSource;

    /**
     * DatabaseManager を構築します。
     *
     * @param plugin        JavaPlugin プラグインのメインクラスインスタンス
     * @param configManager 設定ファイルを管理する ConfigManager のインスタンス
     */
    public DatabaseManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    /**
     * データベース接続プール（HikariCP）の初期化処理を行います。
     * <p>
     * 内部でクラスローダー対策として MariaDB JDBC ドライバを強制的にロードしたのち、
     * 設定ファイルから取得したURL、ユーザー名、パスワード、プールサイズ等の設定を適用します。
     * 初期化の最後に、実際に疎通確認用のテスト接続（{@link #testConnection()}）を行います。
     * </p>
     *
     * @throws Exception ドライバクラスが見つからない場合や、データベースへの初回接続に失敗した場合
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
     * 接続プールからテストコネクションを取得し、疎通確認用のクエリ（{@code SELECT 1}）を実行します。
     *
     * @throws SQLException データベースへの接続テストに失敗した場合
     */
    private void testConnection() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
        }
    }

    /**
     * プラグインの動作に必要なデータベーステーブル群を自動的に検証・作成します。
     * <p>
     * 以下の5つのテーブルが対象です：
     * </p>
     * <ul>
     * <li>{@code fje_balances} - プレイヤーの経済残高管理テーブル</li>
     * <li>{@code fje_shops} - チェストショップ（NPCショップ）のデータ管理テーブル</li>
     * <li>{@code fje_transactions} - プレイヤー間の取引や売買履歴のログテーブル</li>
     * <li>{@code fje_government_ledger} - 政府の資金増減・税収記録台帳テーブル</li>
     * <li>{@code fje_login_bonuses} - プレイヤーの最終ログインボーナス受取日時記録テーブル</li>
     * </ul>
     * <p>
     * 各テーブルには適切なプライマリキー、外部キー制約、およびパフォーマンス向上のためのインデックスが定義されています。
     * </p>
     *
     * @throws SQLException テーブルの作成処理（DDL）の実行中にエラーが発生した場合
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

            plugin.getLogger().info("✓ テーブルを確認/作成しました");

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "テーブル作成エラー", e);
            throw e;
        }
    }

    /**
     * 与えられた接続コネクションを使用して、更新系SQL（{@code CREATE}, {@code UPDATE}, {@code DELETE}等）を同期的に実行します。
     *
     * @param conn 使用するデータベースの接続コネクション
     * @param sql  実行するSQL文
     * @throws SQLException データベースの操作に失敗した場合
     */
    private void executeUpdate(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /**
     * 接続プール（HikariCP）から有効なデータベース接続コネクションを1つ取得して返します。
     * <p>
     * <strong>重要:</strong> 取得した {@link Connection} は、使用後に必ず {@code close()} するか、
     * try-with-resources 文を使用してプールへ返却してください。返却漏れが発生すると接続リークの原因になります。
     * </p>
     *
     * @return 取得された {@link Connection} オブジェクト
     * @throws SQLException 接続プールが閉じられている場合、または接続の取得に失敗した場合
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("DataSource is closed");
        }
        return dataSource.getConnection();
    }

    /**
     * Bukkitの非同期スケジューラー（Asynchronous Task）を利用して、データ取得クエリ（{@code SELECT}）をバックグラウンドで実行します。
     * <p>
     * サーバーのメインスレッドをブロックしないため、重い集計処理や統計コマンド等の実行に適しています。
     * クエリの結果または発生した例外は、引数で渡されたコールバックインターフェースを介して返されます。
     * </p>
     *
     * @param sql      実行する検索用SQL文
     * @param callback 結果の処理またはエラーハンドリングを行うためのコールバック関数（{@link QueryCallback}）
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
     * Bukkitの非同期スケジューラー（Asynchronous Task）を利用して、データ更新クエリ（{@code UPDATE}, {@code INSERT}等）をバックグラウンドで実行します。
     * <p>
     * クエリによって影響を受けた行数（マニピュレーションカウント）または発生した例外は、
     * 引数で渡されたコールバックインターフェースを介して返されます。
     * </p>
     *
     * @param sql      実行する更新用SQL文
     * @param callback 実行結果の行数確認またはエラーハンドリングを行うためのコールバック関数（{@link UpdateCallback}）
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
     * データベースの接続プールを安全にシャットダウンし、保持しているすべてのコネクションを解放します。
     * <p>
     * プラグインの無効化時（{@code onDisable()}）に必ず呼び出してください。
     * </p>
     */
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("データベース接続を閉じました");
        }
    }

    /**
     * 非同期でのデータ取得クエリ（{@code SELECT}）実行結果を受け取るためのコールバックインターフェースです。
     */
    public interface QueryCallback {
        /**
         * クエリが正常に実行された際に呼び出されます。
         *
         * @param rs 取得されたデータを含む {@link ResultSet}。呼出側で処理した後は自動的にクローズされます。
         * @throws SQLException 結果セットの操作中にデータベースエラーが発生した場合
         */
        void onResult(ResultSet rs) throws SQLException;

        /**
         * クエリ実行中にデータベースエラーが発生した際に呼び出されます。
         *
         * @param e 発生した {@link SQLException}
         */
        void onError(SQLException e);
    }

    /**
     * 非同期でのデータ更新クエリ（{@code INSERT}, {@code UPDATE}, {@code DELETE}）実行結果を受け取るためのコールバックインターフェースです。
     */
    public interface UpdateCallback {
        /**
         * クエリが正常に実行された際に呼び出されます。
         *
         * @param affectedRows クエリによって影響を受けた行数
         */
        void onResult(int affectedRows);

        /**
         * クエリ実行中にデータベースエラーが発生した際に呼び出されます。
         *
         * @param e 発生した {@link SQLException}
         */
        void onError(SQLException e);
    }
}
