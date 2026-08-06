package com.clustersprj.fjeconomy.arena;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import com.clustersprj.fjeconomy.economy.EconomyManager;
import com.clustersprj.fjeconomy.government.GovernmentManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    // ゾーン監視用キャッシュ（PlayerMoveEvent/EntityDamageByEntityEvent は高頻度なのでDBを毎回叩かない）
    private final Map<Integer, CachedEvent> activeEventsCache = new ConcurrentHashMap<>();
    // 現在サバイバル化・インベントリ退避中のプレイヤー
    private final Map<UUID, ParticipantState> participantStates = new ConcurrentHashMap<>();

    public ArenaManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
        this.economyManager = plugin.getEconomyManager();
        this.governmentManager = plugin.getGovernmentManager();
    }

    /**
     * アリーナイベントのキャッシュを定期更新するスケジューラを開始します。
     * Web管理画面での新規作成・キャンセルを検知し、ゾーン監視の状態を最新に保ちます。
     */
    public void startZoneWatcher() {
        refreshActiveEventsCache();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshActiveEventsCache, 100L, 100L); // 5秒ごと
    }

    private static final class ActiveEvent {
        int id;
        String name;
        String world;
        double x, y, z, radius;
        long prizeAmount;
    }

    private static final class CachedEvent {
        int id;
        String name;
        String world;
        double x, y, z, radius;
        Set<UUID> participants = new HashSet<>();
    }

    private static final class ParticipantState {
        final int eventId;
        final ItemStack[] mainContents;
        final ItemStack[] armorContents;
        final ItemStack offHand;
        final GameMode originalGameMode;

        ParticipantState(int eventId, ItemStack[] mainContents, ItemStack[] armorContents,
                          ItemStack offHand, GameMode originalGameMode) {
            this.eventId = eventId;
            this.mainContents = mainContents;
            this.armorContents = armorContents;
            this.offHand = offHand;
            this.originalGameMode = originalGameMode;
        }
    }

    /**
     * ACTIVEなアリーナイベント一覧をDBから取得し、ゾーン監視用キャッシュを更新します。
     * キャッシュから外れた（解決/キャンセルされた）イベントの参加者は自動的に元の状態へ復元されます。
     */
    private void refreshActiveEventsCache() {
        Map<Integer, CachedEvent> latest = new HashMap<>();
        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, name, world, center_x, center_y, center_z, radius FROM fje_arena_events WHERE status = 'ACTIVE'");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    CachedEvent e = new CachedEvent();
                    e.id = rs.getInt("id");
                    e.name = rs.getString("name");
                    e.world = rs.getString("world");
                    e.x = rs.getDouble("center_x");
                    e.y = rs.getDouble("center_y");
                    e.z = rs.getDouble("center_z");
                    e.radius = rs.getDouble("radius");
                    latest.put(e.id, e);
                }
            }

            if (!latest.isEmpty()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT event_id, minecraft_uuid FROM fje_arena_participants WHERE event_id IN (" +
                        String.join(",", latest.keySet().stream().map(String::valueOf).toArray(String[]::new)) + ")");
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        CachedEvent e = latest.get(rs.getInt("event_id"));
                        if (e != null) {
                            e.participants.add(UUID.fromString(rs.getString("minecraft_uuid")));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "アリーナイベントキャッシュ更新エラー", e);
            return;
        }

        // キャッシュから消えた（解決/キャンセルされた）イベントの参加者を復元する
        for (Integer oldEventId : activeEventsCache.keySet()) {
            if (!latest.containsKey(oldEventId)) {
                restoreParticipantsForEvent(oldEventId);
            }
        }

        activeEventsCache.clear();
        activeEventsCache.putAll(latest);
    }

    private void restoreParticipantsForEvent(int eventId) {
        for (Map.Entry<UUID, ParticipantState> entry : new HashMap<>(participantStates).entrySet()) {
            if (entry.getValue().eventId == eventId) {
                restorePlayer(entry.getKey());
            }
        }
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
        return isWithinRadius(loc, event.world, event.x, event.y, event.z, event.radius);
    }

    private boolean isWithinRadius(CachedEvent event, Location loc) {
        return isWithinRadius(loc, event.world, event.x, event.y, event.z, event.radius);
    }

    private boolean isWithinRadius(Location loc, String world, double x, double y, double z, double radius) {
        if (loc.getWorld() == null || !loc.getWorld().getName().equals(world)) return false;
        double dx = loc.getX() - x;
        double dy = loc.getY() - y;
        double dz = loc.getZ() - z;
        return (dx * dx + dy * dy + dz * dz) <= (radius * radius);
    }

    // ==========================================
    // ゾーン監視: 立ち入り制限・サバイバル化・インベントリ退避
    // ==========================================

    /**
     * プレイヤーの移動を監視し、アリーナ範囲(円形)への出入りを制御します。
     * <p>
     * 対戦参加者が範囲内に入った場合はサバイバル化＋インベントリ退避を行い、
     * 参加者以外（かつ {@code fj.arena.bypass} 権限を持たない者）の立ち入りは押し戻します。
     * </p>
     *
     * @param event Bukkitのプレイヤー移動イベント。立ち入り拒否時は直接キャンセルします。
     */
    public void handlePlayerMove(PlayerMoveEvent event) {
        if (activeEventsCache.isEmpty()) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        // ブロック座標が変わらない移動（見回し等）は判定不要
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        for (CachedEvent arenaEvent : activeEventsCache.values()) {
            boolean wasInside = isWithinRadius(arenaEvent, from);
            boolean isInside = isWithinRadius(arenaEvent, to);
            if (wasInside || !isInside) continue; // 新規侵入時のみ判定

            if (arenaEvent.participants.contains(player.getUniqueId())) {
                enterMatch(player, arenaEvent);
            } else if (!player.hasPermission("fj.arena.bypass") && !player.isOp()) {
                event.setTo(from);
                player.sendMessage("§c[アリーナ] §f対戦中のため立ち入りできません（「" + arenaEvent.name + "」）");
            }
            return;
        }
    }

    /**
     * プレイヤー同士のダメージを監視し、アリーナ範囲(円形)内では対戦者同士のPvPを
     * WorldGuard等の地域フラグに関係なく許可します（WorldGuardは円形リージョンを扱えないため）。
     *
     * @param event Bukkitのエンティティダメージイベント
     */
    public void handleEntityDamage(EntityDamageByEntityEvent event) {
        if (!event.isCancelled()) return; // 元々許可されているならそのまま
        if (activeEventsCache.isEmpty()) return;
        if (!(event.getEntity() instanceof Player) || !(event.getDamager() instanceof Player)) return;

        Player victim = (Player) event.getEntity();
        Player damager = (Player) event.getDamager();

        for (CachedEvent arenaEvent : activeEventsCache.values()) {
            if (!arenaEvent.participants.contains(victim.getUniqueId())) continue;
            if (!arenaEvent.participants.contains(damager.getUniqueId())) continue;
            if (!isWithinRadius(arenaEvent, victim.getLocation())) continue;
            if (!isWithinRadius(arenaEvent, damager.getLocation())) continue;

            event.setCancelled(false);
            return;
        }
    }

    private void enterMatch(Player player, CachedEvent event) {
        if (participantStates.containsKey(player.getUniqueId())) return; // 既に退避済み

        PlayerInventory inv = player.getInventory();
        ParticipantState state = new ParticipantState(
                event.id,
                inv.getContents().clone(),
                inv.getArmorContents().clone(),
                inv.getItemInOffHand().clone(),
                player.getGameMode()
        );
        participantStates.put(player.getUniqueId(), state);

        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(null);
        player.setGameMode(GameMode.SURVIVAL);
        player.sendMessage("§6[アリーナ] §f「" + event.name + "」の対戦エリアに入りました。持ち物は試合終了後に返却されます。");
    }

    private void restorePlayer(UUID uuid) {
        ParticipantState state = participantStates.remove(uuid);
        if (state == null) return;

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            plugin.getLogger().warning("アリーナ退避したプレイヤー(" + uuid + ")がオフラインのため持ち物を復元できませんでした");
            return;
        }

        PlayerInventory inv = player.getInventory();
        inv.setContents(state.mainContents);
        inv.setArmorContents(state.armorContents);
        inv.setItemInOffHand(state.offHand);
        player.setGameMode(state.originalGameMode);
        player.sendMessage("§6[アリーナ] §f試合が終了しました。持ち物を返却しました。");
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
        restoreParticipantsForEvent(event.id);
        activeEventsCache.remove(event.id);

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
