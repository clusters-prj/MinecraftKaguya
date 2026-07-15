package com.clustersprj.fjeconomy.economy;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.UUID;
import java.util.logging.Level;
import java.util.List;
import java.util.ArrayList;

/**
 * サーバー内の経済システム（残高管理、送金、ショップ決済、税金計算）のコアロジックを統括するマネージャークラスです。
 * <p>
 * データベースへのアクセスを伴う残高の増減処理や、プレイヤー間の安全な資金移動、
 * デッドロックへの対策、およびショップ購入に伴う税金の自動徴収と政府台帳への連携を担当します。
 * </p>
 */
public class EconomyManager {

    private final FJEconomy plugin;
    private final DatabaseManager dbManager;
    private final ConfigManager configManager;

    /**
     * EconomyManager を構築します。
     *
     * @param plugin FJEconomy プラグインのメインクラスインスタンス
     */
    public EconomyManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
        this.configManager = plugin.getConfigManager();
    }

    /**
     * プレイヤー名（ゲーム内名前）から、対応するプレイヤーのUUIDをデータベースより取得します。
     *
     * @param playerName 対象のプレイヤー名
     * @return 該当するプレイヤーの {@link UUID}。データベースに存在しない場合やエラー時は {@code null}
     */
    public UUID getPlayerUUIDByName(String playerName) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT uuid FROM fje_balances WHERE player_name = ?")) {
            stmt.setString(1, playerName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return UUID.fromString(rs.getString("uuid"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "UUID query error", e);
        }
        return null;
    }
    
    /**
     * データベースの残高テーブル（{@code fje_balances}）に登録されているすべてのプレイヤー名を、
     * アルファベット順（重複なし）のリストで取得します。
     *
     * @return プレイヤー名のリスト（登録がない、またはエラー時は空のリスト）
     */
    public List<String> getAllPlayerNames() {
        List<String> names = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT DISTINCT player_name FROM fje_balances ORDER BY player_name");
            while (rs.next()) {
                names.add(rs.getString("player_name"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Get player names error", e);
        }
        return names;
    }

    /**
     * 既存のデータベース接続コネクションを利用して、指定されたプレイヤーの現在の残高を取得します。
     * <p>
     * このメソッドは、他のトランザクション処理の内部などで同一コネクションを使い回す場合に使用します。
     * </p>
     *
     * @param conn 使用するデータベースの接続コネクション
     * @param playerUUID 残高を確認するプレイヤーのUUID
     * @return プレイヤーの現在の残高（レコードが存在しない場合は {@code 0}）
     * @throws SQLException データベースのクエリ実行に失敗した場合
     */
    public long getBalance(Connection conn, UUID playerUUID) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT balance FROM fje_balances WHERE uuid = ?")) {
            stmt.setString(1, playerUUID.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("balance");
                }
            }
        }
        return 0;
    }

    /**
     * 新しいデータベース接続を確立し、指定されたプレイヤーの現在の残高を取得します。
     *
     * @param playerUUID 残高を確認するプレイヤーのUUID
     * @return プレイヤーの現在の残高（エラー時または存在しない場合は {@code 0}）
     */
    public long getBalance(UUID playerUUID) {
        try (Connection conn = dbManager.getConnection()) {
            return getBalance(conn, playerUUID);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Balance query error", e);
        }
        return 0;
    }

    /**
     * 既存のデータベース接続コネクションを利用して、指定されたプレイヤーの残高を任意の値に直接設定します。
     * <p>
     * 処理を実行する前に自動的に {@link #syncPlayerAccount(Connection, UUID, String)} を呼び出し、
     * プレイヤー名の一致とアカウントの存在を確認・保証します。
     * </p>
     *
     * @param conn 使用するデータベースの接続コネクション
     * @param playerUUID 対象プレイヤーのUUID
     * @param playerName 対象プレイヤーの現在のゲーム内名前
     * @param amount 設定する新しい残高の額
     * @return 残高の更新に成功した場合は {@code true}、設定でマイナス残高が禁止されているにもかかわらず
     * マイナスの値を指定した場合や更新に失敗した場合は {@code false}
     * @throws SQLException データベースの更新処理に失敗した場合
     */
    public boolean setBalance(Connection conn, UUID playerUUID, String playerName, long amount) throws SQLException {
        if (!configManager.isNegativeBalanceAllowed() && amount < 0) {
            return false;
        }

        // ★ 先に同期
        syncPlayerAccount(conn, playerUUID, playerName);

        String sql = "UPDATE fje_balances SET balance = ?, player_name = ?, last_update = CURRENT_TIMESTAMP WHERE uuid = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, amount);
            stmt.setString(2, playerName);
            stmt.setString(3, playerUUID.toString());
            return stmt.executeUpdate() > 0;
        }
    }
    
    /**
     * 新しいデータベース接続を確立し、指定されたプレイヤーの残高を任意の値に直接設定します。
     *
     * @param playerUUID 対象プレイヤーのUUID
     * @param playerName 対象プレイヤーの現在のゲーム内名前
     * @param amount 設定する新しい残高の額
     * @return 残高の更新に成功した場合は {@code true}、失敗した場合は {@code false}
     */
    public boolean setBalance(UUID playerUUID, String playerName, long amount) {
        try (Connection conn = dbManager.getConnection()) {
            return setBalance(conn, playerUUID, playerName, amount);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Balance update error", e);
            return false;
        }
    }

    /**
     * プレイヤーのアカウントがデータベース上に存在することを確認し、最新のプレイヤー名に同期します。
     * <p>
     * アカウントが存在しない場合は、設定ファイルから読み込まれた初期所持金（StartingBalance）を設定して
     * 新規にレコードを挿入（インサート）します。既に存在する場合はプレイヤー名と最終更新日時を更新します。
     * </p>
     *
     * @param conn 使用するデータベースの接続コネクション
     * @param playerUUID 同期するプレイヤーのUUID
     * @param playerName 同期するプレイヤーの最新の名前
     * @throws SQLException データベースへの挿入・更新処理に失敗した場合
     */
    private void syncPlayerAccount(Connection conn, UUID playerUUID, String playerName) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO fje_balances (uuid, player_name, balance) VALUES (?, ?, ?) " +
                 "ON DUPLICATE KEY UPDATE player_name = ?, last_update = CURRENT_TIMESTAMP")) {
            stmt.setString(1, playerUUID.toString());
            stmt.setString(2, playerName);
            stmt.setLong(3, configManager.getStartingBalance());
            stmt.setString(4, playerName);
            stmt.executeUpdate();
        }
    }

    /**
     * 外部から呼び出すための、アカウントの存在保証およびプレイヤー名同期メソッドです。
     * 新しいデータベース接続を開いて同期処理を実行します。
     *
     * @param playerUUID 同期するプレイヤーのUUID
     * @param playerName 同期するプレイヤーの最新の名前
     */
    public void ensurePlayerAccount(UUID playerUUID, String playerName) {
        try (Connection conn = dbManager.getConnection()) {
            syncPlayerAccount(conn, playerUUID, playerName);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Player account sync error", e);
        }
    }

    /**
     * 既存のデータベース接続コネクションを利用して、指定されたプレイヤーに資金を追加（入金）します。
     * <p>
     * 処理の中で自動的にアカウントの状態を最新に同期します。
     * </p>
     *
     * @param conn 使用するデータベースの接続コネクション
     * @param playerUUID 対象プレイヤーのUUID
     * @param playerName 対象プレイヤーの現在のゲーム内名前
     * @param amount 追加する資金額（0以下を指定した場合は処理をスキップします）
     * @return 資金の追加に成功した場合は {@code true}、失敗または引数が不正な場合は {@code false}
     * @throws SQLException データベースの更新処理に失敗した場合
     */
    public boolean giveMoney(Connection conn, UUID playerUUID, String playerName, long amount) throws SQLException {
        if (amount <= 0) return false;

        // ★ 必ずここで同期（ユーザー名更新も含む）
        syncPlayerAccount(conn, playerUUID, playerName);

        String sql = "UPDATE fje_balances SET balance = balance + ?, player_name = ?, last_update = CURRENT_TIMESTAMP WHERE uuid = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, amount);
            stmt.setString(2, playerName);
            stmt.setString(3, playerUUID.toString());
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * 新しいデータベース接続を確立し、指定されたプレイヤーに資金を追加（入金）します。
     *
     * @param playerUUID 対象プレイヤーのUUID
     * @param playerName 対象プレイヤーの現在のゲーム内名前
     * @param amount 追加する資金額
     * @return 資金の追加に成功した場合は {@code true}、失敗した場合は {@code false}
     */
    public boolean giveMoney(UUID playerUUID, String playerName, long amount) {
        try (Connection conn = dbManager.getConnection()) {
            return giveMoney(conn, playerUUID, playerName, amount);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Give money error", e);
            return false;
        }
    }

    /**
     * 既存のデータベース接続コネクションを利用して、指定されたプレイヤーから資金を引き出します（減算）。
     * <p>
     * アカウントの同期を行った後、残高が指定額以上あるか、または設定でマイナス残高（借金）が
     * 許可されている場合のみ引き出しを実行します。条件を満たさない場合は引き出せません。
     * </p>
     *
     * @param conn 使用するデータベースの接続コネクション
     * @param playerUUID 対象プレイヤーのUUID
     * @param playerName 対象プレイヤーの現在のゲーム内名前
     * @param amount 引き出す金額（0以下を指定した場合は処理をスキップします）
     * @return 正常に引き出せた場合は {@code true}、残高不足やエラーで引き出せなかった場合は {@code false}
     * @throws SQLException データベースの更新処理に失敗した場合
     */
    public boolean takeMoney(Connection conn, UUID playerUUID, String playerName, long amount) throws SQLException {
        if (amount <= 0) return false;

        // ★ 必ずここで同期
        syncPlayerAccount(conn, playerUUID, playerName);

        String sql = "UPDATE fje_balances SET balance = balance - ?, player_name = ?, last_update = CURRENT_TIMESTAMP " +
                     "WHERE uuid = ? AND (balance >= ? OR ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, amount);
            stmt.setString(2, playerName);
            stmt.setString(3, playerUUID.toString());
            stmt.setLong(4, amount);
            stmt.setBoolean(5, configManager.isNegativeBalanceAllowed());

            int affectedRows = stmt.executeUpdate();
            return affectedRows > 0;
        }
    }

    /**
     * 新しいデータベース接続を確立し、指定されたプレイヤーから資金を引き出します（減算）。
     *
     * @param playerUUID 対象プレイヤーのUUID
     * @param playerName 対象プレイヤーの現在のゲーム内名前
     * @param amount 引き出す金額
     * @return 正常に引き出せた場合は {@code true}、残高不足やエラーの場合は {@code false}
     */
    public boolean takeMoney(UUID playerUUID, String playerName, long amount) {
        try (Connection conn = dbManager.getConnection()) {
            return takeMoney(conn, playerUUID, playerName, amount);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Take money error", e);
            return false;
        }
    }

    /**
     * プレイヤー間で安全に資金を送金（送金コマンド等で使用）し、取引履歴をデータベースに記録します。
     * <p>
     * <strong>デッドロック対策:</strong> 2人以上のプレイヤーが同時に相互送金を行った際に
     * 行ロックの競合でデッドロックが発生するのを防ぐため、双方のUUID文字列を比較し、
     * 常に辞書順で若いアカウントから順に処理（ロック取得）を行うように固定化しています。
     * </p>
     * <p>
     * この処理はトランザクション制御されており、送金側の残高不足などによって一部でも失敗した場合は、
     * すべての処理がロールバックされます。また、取引ログは {@code PAY} 区分として {@code fje_transactions} に記録されます。
     * </p>
     *
     * @param senderUUID 送金元（支払う側）のプレイヤーのUUID
     * @param senderName 送金元の現在のゲーム内名前
     * @param receiverUUID 送金先（受け取る側）のプレイヤーのUUID
     * @param receiverName 送金先の現在のゲーム内名前
     * @param amount 送金する金額（0以下は無効、また自分自身への送金は弾かれます）
     * @return 送金処理が完全に成功した場合は {@code true}、残高不足やエラーで失敗した場合は {@code false}
     */
    public boolean sendMoney(UUID senderUUID, String senderName, 
                            UUID receiverUUID, String receiverName, long amount) {
        if (amount <= 0) return false;
        if (senderUUID.equals(receiverUUID)) return false; // 同一プレイヤー間の送金は弾く

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // デッドロック対策：UUIDの文字列比較で、ロックを取得する順番を常に一定に固定する
                boolean senderFirst = senderUUID.toString().compareTo(receiverUUID.toString()) < 0;

                if (senderFirst) {
                    if (!takeMoney(conn, senderUUID, senderName, amount)) { conn.rollback(); return false; }
                    if (!giveMoney(conn, receiverUUID, receiverName, amount)) { conn.rollback(); return false; }
                } else {
                    if (!giveMoney(conn, receiverUUID, receiverName, amount)) { conn.rollback(); return false; }
                    if (!takeMoney(conn, senderUUID, senderName, amount)) { conn.rollback(); return false; }
                }

                // Record transaction
                String serverId = configManager.getServerId();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO fje_transactions " +
                        "(timestamp, server_id, buyer_uuid, owner_uuid, item_id, amount, price_total, tax_amount, net_profit) " +
                        "VALUES (NOW(), ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    stmt.setString(1, serverId);
                    stmt.setString(2, senderUUID.toString());
                    stmt.setString(3, receiverUUID.toString());
                    stmt.setString(4, "PAY");
                    stmt.setInt(5, 1);
                    stmt.setLong(6, amount);
                    stmt.setLong(7, 0);
                    stmt.setLong(8, amount);
                    stmt.executeUpdate();
                }

                conn.commit();
                return true;

            } catch (Exception e) {
                conn.rollback();
                plugin.getLogger().log(Level.WARNING, "Transaction error", e);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Money transfer error", e);
            return false;
        }
    }

    /**
     * チェストショップ等での商品の売買決済処理（購入処理）を実行します。
     * <p>
     * <strong>一連の処理フロー:</strong>
     * <ol>
     * <li>購入総額、設定ファイルの税率、および端数処理方法（四捨五入/切り捨て等）に基づき、税金を算出します。</li>
     * <li>購入者の残高から購入総額（税込）を引き出します（残高不足の場合はロールバック）。</li>
     * <li>ショップオーナーに税引後の純利益（売上）を入金します。</li>
     * <li>政府アカウント（Government）に算出された税金を入金します。</li>
     * <li>取引全体の履歴を {@code fje_transactions} に、税金発生の履歴を政府台帳（{@code fje_government_ledger}）にそれぞれ記録します。</li>
     * </ol>
     * 全てのアクションは単一のトランザクションとして実行され、どこか一つで問題が発生した場合は全てキャンセルされます。
     * </p>
     *
     * @param buyerUUID 購入者のUUID
     * @param buyerName 購入者の現在のゲーム内名前
     * @param ownerUUID ショップオーナー（販売者）のUUID
     * @param ownerName ショップオーナーの現在のゲーム内名前
     * @param itemMaterial 取引されたアイテムの識別名（マテリアル名など）
     * @param quantity 取引された個数
     * @param unitPrice アイテム1個あたりの単価
     * @return 決済およびデータベースへの記録が完全に成功した場合は {@code true}、残高不足や何らかのエラーが発生した場合は {@code false}
     */
    public boolean processPurchase(UUID buyerUUID, String buyerName,
                                   UUID ownerUUID, String ownerName,
                                   String itemMaterial, int quantity, long unitPrice) {
        if (quantity <= 0 || unitPrice < 0) return false;

        long totalPrice = unitPrice * quantity;
        double taxRate = configManager.getTaxRate() / 100.0;
        RoundingMode rounding = RoundingMode.valueOf(configManager.getRoundingMethod());

        // Calculate tax
        BigDecimal taxDecimal = BigDecimal.valueOf(totalPrice)
                .multiply(BigDecimal.valueOf(taxRate))
                .setScale(0, rounding);
        long taxAmount = taxDecimal.longValue();

        long netProfit = totalPrice - taxAmount;

        // Government UUID
        UUID governmentUUID = UUID.fromString(configManager.getGovernmentUUID());
        String governmentName = configManager.getGovernmentName();

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // 【変更点】事前に getBalance する必要はなし！
                // takeMoney が自動で残高不足を判定して弾いてくれる
                if (!takeMoney(conn, buyerUUID, buyerName, totalPrice)) {
                    conn.rollback();
                    return false;
                }

                // Add to shop owner
                if (!giveMoney(conn, ownerUUID, ownerName, netProfit)) {
                    conn.rollback();
                    return false;
                }

                // Add tax to government
                if (!giveMoney(conn, governmentUUID, governmentName, taxAmount)) {
                    conn.rollback();
                    return false;
                }

                // Record transaction
                String serverId = configManager.getServerId();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO fje_transactions " +
                        "(timestamp, server_id, buyer_uuid, owner_uuid, item_id, amount, price_total, tax_amount, net_profit) " +
                        "VALUES (NOW(), ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    stmt.setString(1, serverId);
                    stmt.setString(2, buyerUUID.toString());
                    stmt.setString(3, ownerUUID.toString());
                    stmt.setString(4, itemMaterial);
                    stmt.setInt(5, quantity);
                    stmt.setLong(6, totalPrice);
                    stmt.setLong(7, taxAmount);
                    stmt.setLong(8, netProfit);
                    stmt.executeUpdate();
                }

                // Record government ledger
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO fje_government_ledger (timestamp, type, amount, description) " +
                        "VALUES (NOW(), ?, ?, ?)")) {
                    stmt.setString(1, "TAX_IN");
                    stmt.setLong(2, taxAmount);
                    stmt.setString(3, itemMaterial + " x" + quantity + " from " + buyerName);
                    stmt.executeUpdate();
                }

                conn.commit();
                return true;

            } catch (Exception e) {
                conn.rollback();
                plugin.getLogger().log(Level.WARNING, "Purchase error", e);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Purchase transaction error", e);
            return false;
        }
    }

    /**
     * 金額を設定ファイルで指定された通貨記号（例: 「$」等）を付与した文字列形式にフォーマットします。
     *
     * @param amount フォーマットする金額
     * @return 通貨記号が付与されたフォーマット済みの金額文字列
     */
    public String formatMoney(long amount) {
        return configManager.getCurrencySymbol() + amount;
    }

    /**
     * 設定ファイルに定義されている政府アカウント（Government）のUUIDを用いて、政府の現在の残高を取得します。
     *
     * @return 政府アカウントの現在残高
     */
    public long getGovernmentBalance() {
        UUID govUUID = UUID.fromString(configManager.getGovernmentUUID());
        return getBalance(govUUID);
    }
}
