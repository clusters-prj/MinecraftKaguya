package com.clustersprj.fjeconomy.government;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import com.clustersprj.fjeconomy.economy.EconomyManager;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * 政府アカウントの管理と、税金システムの統括を行うマネージャークラスです。
 * 
 * <p><strong>主な責務：</strong></p>
 * <ul>
 *   <li>政府アカウント（特殊なプレイヤー扱いのアカウント）の管理</li>
 *   <li>税金の自動集計および管理</li>
 *   <li>政府台帳（{@code fje_government_ledger} テーブル）への記録とクエリの実行</li>
 *   <li>政府資金の配分機能（将来的な給付金・公共工事などへの出資）</li>
 * </ul>
 */
public class GovernmentManager {

    private final FJEconomy plugin;
    private final DatabaseManager dbManager;
    private final ConfigManager configManager;
    private final EconomyManager economyManager;

    private UUID governmentUUID;
    private String governmentName;

    /**
     * GovernmentManager を構築します。
     * 設定ファイルから政府アカウントのUUIDおよびアカウント名を読み込みます。
     *
     * @param plugin FJEconomy プラグインのメインクラスインスタンス
     */
    public GovernmentManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
        this.configManager = plugin.getConfigManager();
        this.economyManager = new EconomyManager(plugin);

        this.governmentUUID = UUID.fromString(configManager.getGovernmentUUID());
        this.governmentName = configManager.getGovernmentName();
    }

    /**
     * 政府管理システムの初期化処理を行います。
     * <p>
     * 初回起動時など、データベース上に政府用のアカウントが存在しない場合は
     * 自動的にアカウントを新規作成（保証）します。
     * </p>
     */
    public void initialize() {
        try {
            economyManager.ensurePlayerAccount(governmentUUID, governmentName);
            plugin.getLogger().info("✓ 政府アカウントを初期化しました: " + governmentName);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "政府アカウント初期化エラー", e);
        }
    }

    /**
     * 政府アカウントの現在の残高を取得します。
     *
     * @return 政府アカウントの残高
     */
    public long getGovernmentBalance() {
        return economyManager.getBalance(governmentUUID);
    }

    /**
     * 政府アカウントの残高を直接任意の値に設定します（調整・リセット用）。
     *
     * @param amount 設定する残高の額
     * @return 残高の設定に成功した場合は {@code true}、失敗した場合は {@code false}
     */
    public boolean setGovernmentBalance(long amount) {
        return economyManager.setBalance(governmentUUID, governmentName, amount);
    }

    /**
     * 政府アカウントに指定された資金を追加し、その理由を台帳に記録します。
     *
     * @param amount 追加する資金の額（0より大きい必要があります）
     * @param reason 資金を追加する理由や名目
     * @return 資金の追加および台帳への記録が正常に完了した場合は {@code true}、それ以外は {@code false}
     */
    public boolean addGovernmentFunds(long amount, String reason) {
        if (amount <= 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // 資金追加
                economyManager.giveMoney(conn, governmentUUID, governmentName, amount);

                // 台帳に記録
                recordLedger(conn, "FUND_ADD", amount, reason);

                conn.commit();
                plugin.getLogger().info("政府資金を追加: " + amount + " - " + reason);
                return true;

            } catch (Exception e) {
                conn.rollback();
                plugin.getLogger().log(Level.WARNING, "政府資金追加エラー", e);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "政府資金追加時のDB接続エラー", e);
            return false;
        }
    }

    /**
     * 政府アカウントから指定された資金を引き出し、その理由を台帳に記録します。
     * <p>
     * 主に給付金の配布や、各種配分処理を行うための前段階として使用されます。
     * </p>
     *
     * @param amount 引き出す資金の額（0より大きい必要があります）
     * @param reason 資金を引き出す理由や名目
     * @return 資金の引き出しおよび台帳への記録が正常に完了した場合は {@code true}、
     *         残高不足やエラーが発生した場合は {@code false}
     */
    public boolean withdrawGovernmentFunds(long amount, String reason) {
        if (amount <= 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // 資金引き出し
                if (!economyManager.takeMoney(conn, governmentUUID, governmentName, amount)) {
                    conn.rollback();
                    return false;
                }

                // 台帳に記録
                recordLedger(conn, "FUND_WITHDRAW", amount, reason);

                conn.commit();
                plugin.getLogger().info("政府資金を引き出し: " + amount + " - " + reason);
                return true;

            } catch (Exception e) {
                conn.rollback();
                plugin.getLogger().log(Level.WARNING, "政府資金引き出しエラー", e);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "政府資金引き出し時のDB接続エラー", e);
            return false;
        }
    }

    /**
     * 税収を政府アカウントに加算し、区分を {@code "TAX_IN"} として台帳に記録します。
     * <p>
     * ショップでの商品購入時など、システム側で税金を徴収した際はこちらのメソッドを使用してください。
     * 手動の資金追加（{@code "FUND_ADD"}）と区別して記録されるため、後から税収のみを正確に集計できます。
     * </p>
     *
     * @param amount 徴収した税金の額（0より大きい必要があります）
     * @param description 税収に関する詳細な説明（購入された商品名など）
     * @return 税収の加算および台帳への記録が正常に完了した場合は {@code true}、それ以外は {@code false}
     */
    public boolean addTaxIncome(long amount, String description) {
        if (amount <= 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                economyManager.giveMoney(conn, governmentUUID, governmentName, amount);
                recordTax(conn, amount, description);

                conn.commit();
                return true;

            } catch (Exception e) {
                conn.rollback();
                plugin.getLogger().log(Level.WARNING, "税収記録エラー", e);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "税収記録時のDB接続エラー", e);
            return false;
        }
    }

    /**
     * 政府台帳（{@code fje_government_ledger}）に取引レコードを直接書き込みます。
     *
     * @param conn データベースの接続コネクション
     * @param type 取引タイプ（例: {@code "FUND_ADD"}, {@code "FUND_WITHDRAW"}, {@code "TAX_IN"} など）
     * @param amount 取引された金額
     * @param description 取引の内容説明
     * @throws SQLException データベースへの書き込み処理に失敗した場合
     */
    private void recordLedger(Connection conn, String type, long amount, String description) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO fje_government_ledger (timestamp, type, amount, description) " +
                "VALUES (NOW(), ?, ?, ?)")) {
            stmt.setString(1, type);
            stmt.setLong(2, amount);
            stmt.setString(3, description);
            stmt.executeUpdate();
        }
    }

    /**
     * 税金の発生を政府台帳に {@code "TAX_IN"} 区分として記録します。
     *
     * @param conn データベースの接続コネクション
     * @param taxAmount 徴収した税金額
     * @param itemDescription 対象のアイテムや取引などの詳細情報
     * @throws SQLException データベースへの書き込み処理に失敗した場合
     */
    public void recordTax(Connection conn, long taxAmount, String itemDescription) throws SQLException {
        recordLedger(conn, "TAX_IN", taxAmount, "Sale: " + itemDescription);
    }

    /**
     * 現在時刻から指定された分数の範囲内における、税金（{@code "TAX_IN"}）の総額を取得します。
     *
     * @param minutesAgo 遡る時間（分単位）
     * @return 指定された期間内における税金総額（データがない、またはエラー時は {@code 0}）
     */
    public long getTaxIncome(long minutesAgo) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COALESCE(SUM(amount), 0) as total FROM fje_government_ledger " +
                     "WHERE type = 'TAX_IN' AND timestamp >= DATE_SUB(NOW(), INTERVAL ? MINUTE)")) {

            stmt.setLong(1, minutesAgo);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getLong("total");
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Tax income query error", e);
        }

        return 0;
    }

    /**
     * 本日（今日一日）徴収された税金（{@code "TAX_IN"}）の総額を取得します。
     *
     * @return 本日の税収総額（データがない、またはエラー時は {@code 0}）
     */
    public long getTodayTaxIncome() {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COALESCE(SUM(amount), 0) as total FROM fje_government_ledger " +
                     "WHERE type = 'TAX_IN' AND DATE(timestamp) = CURDATE()")) {

            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getLong("total");
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Today tax income query error", e);
        }

        return 0;
    }

    /**
     * 政府台帳の取引履歴を、最新の日時から指定された最大件数分だけ取得します。
     *
     * @param limit 取得するレコードの最大件数
     * @return 台帳エントリー（{@link LedgerEntry}）のリスト（エラー時は空のリスト）
     */
    public List<LedgerEntry> getLedgerHistory(int limit) {
        List<LedgerEntry> entries = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, timestamp, type, amount, description FROM fje_government_ledger " +
                     "ORDER BY timestamp DESC LIMIT ?")) {

            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                entries.add(new LedgerEntry(
                        rs.getInt("id"),
                        rs.getTimestamp("timestamp"),
                        rs.getString("type"),
                        rs.getLong("amount"),
                        rs.getString("description")
                ));
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Ledger history query error", e);
        }

        return entries;
    }

    /**
     * 指定された特定の取引タイプ（例: {@code "TAX_IN"} など）に絞り込んで、
     * 台帳履歴を最新の日時から指定最大件数分だけ取得します。
     *
     * @param type 抽出する取引タイプ
     * @param limit 取得するレコードの最大件数
     * @return 条件に合致する台帳エントリー（{@link LedgerEntry}）のリスト（エラー時は空のリスト）
     */
    public List<LedgerEntry> getLedgerByType(String type, int limit) {
        List<LedgerEntry> entries = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, timestamp, type, amount, description FROM fje_government_ledger " +
                     "WHERE type = ? ORDER BY timestamp DESC LIMIT ?")) {

            stmt.setString(1, type);
            stmt.setInt(2, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                entries.add(new LedgerEntry(
                        rs.getInt("id"),
                        rs.getTimestamp("timestamp"),
                        rs.getString("type"),
                        rs.getLong("amount"),
                        rs.getString("description")
                ));
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Ledger type query error", e);
        }

        return entries;
    }

    /**
     * 政府資金を特定のプレイヤーに配分（給付金を送金）し、その記録を台帳に書き込みます。
     * <p>
     * この処理はトランザクションで制御されており、政府からの引き出し、プレイヤーへの入金、
     * 台帳への書き込みのいずれか一つでも失敗した場合は自動的にロールバックされます。
     * </p>
     * 
     * @param recipientUUID 給付を受け取るプレイヤーのUUID
     * @param recipientName 給付を受け取るプレイヤーのゲーム内名前
     * @param amount 配分する金額（0より大きい必要があります）
     * @param reason 給付の理由や名目
     * @return すべての処理が正常に完了した場合は {@code true}、何らかの理由で処理が中断された場合は {@code false}
     */
    public boolean distributeGovernmentFunds(UUID recipientUUID, String recipientName, 
                                            long amount, String reason) {
        if (amount <= 0) {
            return false;
        }

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // 政府から引き出し
                if (!economyManager.takeMoney(conn, governmentUUID, governmentName, amount)) {
                    conn.rollback();
                    return false;
                }

                // プレイヤーに給付
                if (!economyManager.giveMoney(conn, recipientUUID, recipientName, amount)) {
                    conn.rollback();
                    return false;
                }

                // 台帳に記録
                recordLedger(conn, "FUND_DISTRIBUTE", amount, 
                           "To: " + recipientName + " - " + reason);

                conn.commit();
                plugin.getLogger().info("政府資金配分: " + recipientName + " に " + amount + 
                                      " (" + reason + ")");
                return true;

            } catch (Exception e) {
                conn.rollback();
                plugin.getLogger().log(Level.WARNING, "Government distribution error", e);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Distribution transaction error", e);
            return false;
        }
    }

    /**
     * 政府アカウントのUUIDを取得します。
     *
     * @return 政府のUUID
     */
    public UUID getGovernmentUUID() {
        return governmentUUID;
    }

    /**
     * 政府アカウント名を取得します。
     *
     * @return 政府のアカウント名
     */
    public String getGovernmentName() {
        return governmentName;
    }

    /**
     * 政府台帳（{@code fje_government_ledger}）に記録される1件の取引データを表すイミュータブルなデータクラスです。
     */
    public static class LedgerEntry {
        private final int id;
        private final Timestamp timestamp;
        private final String type;
        private final long amount;
        private final String description;

        /**
         * 台帳エントリーオブジェクトを構築します。
         *
         * @param id 台帳レコードの一意なID
         * @param timestamp 取引の記録日時
         * @param type 取引のタイプ（例: "TAX_IN"）
         * @param amount 取引の金額
         * @param description 取引に関する詳細なテキスト説明
         */
        public LedgerEntry(int id, Timestamp timestamp, String type, long amount, String description) {
            this.id = id;
            this.timestamp = timestamp;
            this.type = type;
            this.amount = amount;
            this.description = description;
        }

        /**
         * 台帳のレコードIDを取得します。
         *
         * @return レコードID
         */
        public int getId() { return id; }

        /**
         * 取引が記録された日時を取得します。
         *
         * @return 取引日時
         */
        public Timestamp getTimestamp() { return timestamp; }

        /**
         * 取引のタイプを取得します。
         *
         * @return 取引タイプ（例: {@code "TAX_IN"}, {@code "FUND_ADD"} 等）
         */
        public String getType() { return type; }

        /**
         * 取引された金額を取得します。
         *
         * @return 取引金額
         */
        public long getAmount() { return amount; }

        /**
         * 取引の追加説明を取得します。
         *
         * @return 説明テキスト
         */
        public String getDescription() { return description; }

        /**
         * 台帳レコードの内容を読みやすい文字列表現にして返します。
         *
         * @return フォーマット済みの台帳エントリー情報文字列
         */
        @Override
        public String toString() {
            return String.format("[%s] %s: %d - %s", timestamp, type, amount, description);
        }
    }
}
