package com.clustersprj.fjeconomy.arena;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import com.clustersprj.fjeconomy.economy.EconomyManager;
import com.clustersprj.fjeconomy.government.GovernmentManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * アリーナ監視イベント（Webで事前登録された対戦カードが指定座標・半径内でキルされたら
 * 政府から賞金を付与し、優勝者予想ベットを精算する）を扱うマネージャークラスです。
 */
public class ArenaManager {

    private final FJEconomy plugin;
    private final DatabaseManager dbManager;
    private final EconomyManager economyManager;
    private final GovernmentManager governmentManager;

    public ArenaManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
        this.economyManager = plugin.getEconomyManager();
        this.governmentManager = plugin.getGovernmentManager();
    }

    private static final class ActiveEvent {
        int id;
        String name;
        String world;
        double x, y, z, radius;
        long prizeAmount;
    }

    /**
     * killer が victim を倒した際に呼び出され、ACTIVEなアリーナイベントの対戦カード・
     * 発生位置に一致するものがあれば、そのイベントを解決（賞金付与＋ベット精算）します。
     *
     * @param killer キルしたプレイヤー（nullの場合は何もしない）
     * @param victim 死亡したプレイヤー
     */
    public void checkKillTrigger(Player killer, Player victim) {
        if (killer == null || victim == null) return;
        if (killer.getUniqueId().equals(victim.getUniqueId())) return;

        try (Connection conn = dbManager.getConnection()) {
            List<ActiveEvent> events = findMatchingActiveEvents(conn, killer.getUniqueId(), victim.getUniqueId());
            for (ActiveEvent event : events) {
                Location deathLoc = victim.getLocation();
                if (!isWithinRadius(event, deathLoc)) continue;

                resolveEvent(event, killer.getUniqueId(), killer.getName());
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "アリーナ判定中のDBエラー", e);
        }
    }

    private List<ActiveEvent> findMatchingActiveEvents(Connection conn, UUID killerUuid, UUID victimUuid) throws SQLException {
        List<ActiveEvent> matches = new ArrayList<>();
        String sql =
                "SELECT e.id, e.name, e.world, e.center_x, e.center_y, e.center_z, e.radius, e.prize_amount " +
                "FROM fje_arena_events e " +
                "WHERE e.status = 'ACTIVE' " +
                "AND EXISTS (SELECT 1 FROM fje_arena_participants p WHERE p.event_id = e.id AND p.minecraft_uuid = ?) " +
                "AND EXISTS (SELECT 1 FROM fje_arena_participants p WHERE p.event_id = e.id AND p.minecraft_uuid = ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, killerUuid.toString());
            stmt.setString(2, victimUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ActiveEvent e = new ActiveEvent();
                    e.id = rs.getInt("id");
                    e.name = rs.getString("name");
                    e.world = rs.getString("world");
                    e.x = rs.getDouble("center_x");
                    e.y = rs.getDouble("center_y");
                    e.z = rs.getDouble("center_z");
                    e.radius = rs.getDouble("radius");
                    e.prizeAmount = rs.getLong("prize_amount");
                    matches.add(e);
                }
            }
        }
        return matches;
    }

    private boolean isWithinRadius(ActiveEvent event, Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().getName().equals(event.world)) return false;
        double dx = loc.getX() - event.x;
        double dy = loc.getY() - event.y;
        double dz = loc.getZ() - event.z;
        return (dx * dx + dy * dy + dz * dz) <= (event.radius * event.radius);
    }

    /**
     * イベントを解決し、勝者に政府から賞金を付与、ベットを精算します。
     */
    private void resolveEvent(ActiveEvent event, UUID winnerUuid, String winnerName) {
        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE fje_arena_events SET status = 'RESOLVED', winner_uuid = ?, resolved_at = NOW() " +
                    "WHERE id = ? AND status = 'ACTIVE'")) {
                stmt.setString(1, winnerUuid.toString());
                stmt.setInt(2, event.id);
                int updated = stmt.executeUpdate();
                if (updated == 0) {
                    // 既に他のスレッド/イベントで解決済み
                    return;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "アリーナイベント解決エラー", e);
            return;
        }

        if (event.prizeAmount > 0) {
            governmentManager.distributeGovernmentFunds(
                    winnerUuid, winnerName, event.prizeAmount,
                    "アリーナ優勝賞金: " + event.name);
        }

        settleBets(event.id, winnerUuid);

        plugin.getServer().broadcastMessage(
                "§6[アリーナ] §f「" + event.name + "」の勝者は §a" + winnerName + " §fです！");
    }

    /**
     * ベットを精算します。的中者がいればパリミュチュエル方式（掛け金比率）で全額プールを分配、
     * いなければ全ベッターへ払い戻します。
     */
    private void settleBets(int eventId, UUID winnerUuid) {
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<Integer> betIds = new ArrayList<>();
                List<UUID> bettorUuids = new ArrayList<>();
                List<UUID> predictedUuids = new ArrayList<>();
                List<Long> amounts = new ArrayList<>();

                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT id, bettor_uuid, predicted_uuid, amount FROM fje_arena_bets " +
                        "WHERE event_id = ? AND status = 'PLACED'")) {
                    stmt.setInt(1, eventId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            betIds.add(rs.getInt("id"));
                            bettorUuids.add(UUID.fromString(rs.getString("bettor_uuid")));
                            predictedUuids.add(UUID.fromString(rs.getString("predicted_uuid")));
                            amounts.add(rs.getLong("amount"));
                        }
                    }
                }

                if (betIds.isEmpty()) {
                    conn.commit();
                    return;
                }

                long totalPool = 0;
                long winningPool = 0;
                for (int i = 0; i < betIds.size(); i++) {
                    totalPool += amounts.get(i);
                    if (predictedUuids.get(i).equals(winnerUuid)) {
                        winningPool += amounts.get(i);
                    }
                }

                for (int i = 0; i < betIds.size(); i++) {
                    int betId = betIds.get(i);
                    UUID bettorUuid = bettorUuids.get(i);
                    long amount = amounts.get(i);
                    boolean isWinner = predictedUuids.get(i).equals(winnerUuid);

                    if (winningPool > 0) {
                        if (isWinner) {
                            // パリミュチュエル配当: 全額プール × (自分の掛け金 / 的中者の掛け金合計)
                            long payout = Math.floorDiv(totalPool * amount, winningPool);
                            String bettorName = resolvePlayerName(conn, bettorUuid);
                            economyManager.giveMoney(conn, bettorUuid, bettorName, payout);
                            markBet(conn, betId, "WON", payout);
                            recordArenaTransaction(conn, bettorUuid, "ARENA_PAYOUT", payout);
                        } else {
                            markBet(conn, betId, "LOST", 0);
                        }
                    } else {
                        // 的中者なし: 全額払い戻し
                        String bettorName = resolvePlayerName(conn, bettorUuid);
                        economyManager.giveMoney(conn, bettorUuid, bettorName, amount);
                        markBet(conn, betId, "REFUNDED", amount);
                        recordArenaTransaction(conn, bettorUuid, "ARENA_REFUND", amount);
                    }
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                plugin.getLogger().log(Level.WARNING, "ベット精算エラー", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "ベット精算トランザクションエラー", e);
        }
    }

    private String resolvePlayerName(Connection conn, UUID uuid) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT player_name FROM fje_balances WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getString("player_name");
            }
        }
        return uuid.toString();
    }

    private void markBet(Connection conn, int betId, String status, long payoutAmount) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE fje_arena_bets SET status = ?, payout_amount = ? WHERE id = ?")) {
            stmt.setString(1, status);
            stmt.setLong(2, payoutAmount);
            stmt.setInt(3, betId);
            stmt.executeUpdate();
        }
    }

    private void recordArenaTransaction(Connection conn, UUID recipientUuid, String itemId, long amount) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO fje_transactions " +
                "(timestamp, server_id, buyer_uuid, owner_uuid, item_id, amount, price_total, tax_amount, net_profit) " +
                "VALUES (NOW(), 'ARENA', ?, ?, ?, 1, ?, 0, ?)")) {
            stmt.setString(1, recipientUuid.toString());
            stmt.setString(2, recipientUuid.toString());
            stmt.setString(3, itemId);
            stmt.setLong(4, amount);
            stmt.setLong(5, amount);
            stmt.executeUpdate();
        }
    }
}
