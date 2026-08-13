package com.clustersprj.fjeconomy.build;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.config.ConfigManager;
import com.clustersprj.fjeconomy.database.DatabaseManager;
import com.clustersprj.fjeconomy.economy.EconomyManager;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * CoreProtect のブロックログを集計し、建築量に応じたポイント（ふじゅ〜ポイント）を付与するマネージャークラスです。
 * <p>
 * 役割は2つあります。
 * </p>
 * <ul>
 *   <li>{@code build_reward.interval_hours} ごと（0時起点の区切り）に直前の期間を集計してポイントを付与する定期処理</li>
 *   <li>Web管理画面から {@code fje_build_queries} に登録された任意期間の集計リクエストを処理する
 *       ポーリング処理（こちらはランキング表示専用で<strong>ポイントは付与しない</strong>）</li>
 * </ul>
 * <p>
 * WorldEdit による設置・破壊も、CoreProtect 側の設定で {@code worldedit: true} になっていれば
 * 実行したプレイヤー名で記録されるため、そのまま集計対象になります。
 * </p>
 */
public class BuildRewardManager {

    /** CoreProtect へ1回の問い合わせで要求する最大件数。 */
    private static final int LOOKUP_BATCH = 5000;

    /** 1回の集計で読み込むログの安全上限（これを超えたら打ち切って警告する）。 */
    private static final int MAX_LOOKUP_ROWS = 1000000;

    /** CoreProtect のアクションID: ブロック破壊。 */
    private static final int ACTION_BREAK = 0;

    /** CoreProtect のアクションID: ブロック設置。 */
    private static final int ACTION_PLACE = 1;

    /** 建築ボーナスを fje_transactions に記録するときの item_id。 */
    private static final String TRANSACTION_ITEM_ID = "BUILD_REWARD";

    private final FJEconomy plugin;
    private final DatabaseManager dbManager;
    private final EconomyManager economyManager;
    private final ConfigManager configManager;

    /** CoreProtect のルックアップを同時に走らせないためのロック。 */
    private final Object lookupLock = new Object();

    /** Web集計リクエストの処理が走っている間、次のポーリングを空振りさせるためのフラグ。 */
    private final AtomicBoolean queryRunning = new AtomicBoolean(false);

    private CoreProtectAPI coreProtect;

    public BuildRewardManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
        this.economyManager = plugin.getEconomyManager();
        this.configManager = plugin.getConfigManager();
    }

    /**
     * 定期集計とWeb集計リクエストのポーリングを開始します。
     * <p>
     * CoreProtect が導入されていない場合は何もせず、警告ログのみ出力します。
     * </p>
     */
    public void start() {
        if (!configManager.isBuildRewardEnabled()) {
            plugin.getLogger().info("建築量ポイントは無効に設定されています (config.yml)");
            return;
        }

        this.coreProtect = resolveCoreProtect();
        if (coreProtect == null) {
            plugin.getLogger().warning("CoreProtect が利用できないため、建築量ポイントを無効化しました");
            return;
        }

        scheduleNextAggregation();

        long pollTicks = Math.max(1, configManager.getBuildRewardQueryPollSeconds()) * 20L;
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::pollQueries, pollTicks, pollTicks);

        // 再起動でスケジュールを跨いだ期間を取りこぼさないよう、直前の期間を起動時に集計しておく。
        // 付与済みの期間は fje_build_rewards のユニークキーで弾かれるため二重付与にはならない。
        LocalDateTime lastBoundary = previousBoundary(LocalDateTime.now());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> runAggregation(lastBoundary));

        plugin.getLogger().info("建築量ポイントを開始しました（" + configManager.getBuildRewardIntervalHours() + "時間ごと）");
    }

    /**
     * サーバー上の CoreProtect プラグインから API を取得します。
     *
     * @return 利用可能な {@link CoreProtectAPI}。導入されていない・APIが古い場合は null
     */
    private CoreProtectAPI resolveCoreProtect() {
        Plugin target = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (!(target instanceof CoreProtect)) {
            return null;
        }

        CoreProtectAPI api = ((CoreProtect) target).getAPI();
        if (api == null || !api.isEnabled()) {
            return null;
        }
        if (api.APIVersion() < 9) {
            plugin.getLogger().warning("CoreProtect の API バージョンが古すぎます: " + api.APIVersion());
            return null;
        }
        return api;
    }

    // ==========================================
    // 定期集計
    // ==========================================

    /**
     * 次の集計区切り（0時起点で {@code interval_hours} ごと）に集計タスクを予約します。
     * 実行のたびに現在時刻から次回を計算し直すため、サーバーのラグによるズレが蓄積しません。
     */
    private void scheduleNextAggregation() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextBoundary = nextBoundary(now);
        long delayTicks = Math.max(1L, Duration.between(now, nextBoundary).toMillis() / 50L);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> runAggregation(nextBoundary));
            scheduleNextAggregation();
        }, delayTicks);
    }

    /**
     * 指定時刻の直後にくる集計区切りを求めます。
     *
     * @param from 基準時刻
     * @return 次の集計区切り
     */
    private LocalDateTime nextBoundary(LocalDateTime from) {
        int intervalHours = normalizedIntervalHours();
        LocalDate day = from.toLocalDate();
        LocalDateTime dayStart = day.atStartOfDay();

        for (int hour = 0; hour < 24; hour += intervalHours) {
            LocalDateTime candidate = dayStart.plusHours(hour);
            if (candidate.isAfter(from)) {
                return candidate;
            }
        }
        return dayStart.plusDays(1);
    }

    /**
     * 指定時刻の直前にある集計区切りを求めます。
     *
     * @param from 基準時刻
     * @return 直前の集計区切り
     */
    private LocalDateTime previousBoundary(LocalDateTime from) {
        return nextBoundary(from).minusHours(normalizedIntervalHours());
    }

    /** 0時起点で1日を均等に割り切れる集計間隔（24の約数）。 */
    private static final int[] VALID_INTERVAL_HOURS = {1, 2, 3, 4, 6, 8, 12, 24};

    /**
     * 集計間隔を 24 の約数に丸めて返します。
     * <p>
     * 区切りは 0時起点で {@code interval_hours} ずつ進めるため、24 の約数でないと
     * 日をまたぐ最後の区切りだけ間隔が変わり、直前期間が前の期間と重なります。
     * （例: 5時間なら区切りは 0/5/10/15/20時。20時の次は翌0時の4時間後になるが、
     * previousBoundary は 5時間引くので 19時となり、15〜20時の期間と1時間重複して
     * ポイントが二重付与される。）
     * config.yml にも約数を指定するよう明記しているが、コード側でも保証する。
     * </p>
     *
     * @return 集計間隔（時間）。24 の約数のいずれか
     */
    private int normalizedIntervalHours() {
        int configured = Math.min(24, Math.max(1, configManager.getBuildRewardIntervalHours()));

        for (int valid : VALID_INTERVAL_HOURS) {
            if (valid == configured) {
                return configured;
            }
        }

        // 24を割り切れない値なら、それ以下で最大の約数まで切り下げる
        int fallback = 1;
        for (int valid : VALID_INTERVAL_HOURS) {
            if (valid < configured) {
                fallback = valid;
            }
        }
        plugin.getLogger().warning("build_reward.interval_hours は24の約数(1/2/3/4/6/8/12/24)である必要があります: "
                + configured + " -> " + fallback + " として扱います");
        return fallback;
    }

    /**
     * 指定した区切り時刻で終わる1期間を集計し、ポイントを付与します。非同期スレッドから呼び出してください。
     *
     * @param periodEnd 期間の終端（この時刻ちょうどのログは次の期間に含める）
     */
    private void runAggregation(LocalDateTime periodEnd) {
        LocalDateTime periodStart = periodEnd.minusHours(normalizedIntervalHours());

        try {
            CollectResult result = collectStats(periodStart, periodEnd);
            if (result.stats().isEmpty()) {
                return;
            }
            grantPoints(periodStart, periodEnd, result.stats());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "建築量の定期集計に失敗しました: " + periodStart + " 〜 " + periodEnd, e);
        }
    }

    /**
     * 集計結果をもとにポイントを付与し、{@code fje_build_rewards} に記録します。
     * <p>
     * 1プレイヤーごとにトランザクションを張り、記録の挿入・国庫からの支出・残高への入金を
     * まとめてコミットします。同じ期間の記録が既に存在する場合（再起動直後の取りこぼし集計など）は
     * ユニークキー違反になるため、そのプレイヤーだけスキップします。
     * </p>
     *
     * @param periodStart 期間の始端
     * @param periodEnd 期間の終端
     * @param stats プレイヤーごとの集計結果
     */
    private void grantPoints(LocalDateTime periodStart, LocalDateTime periodEnd, Map<UUID, BuildStat> stats) {
        String serverId = configManager.getServerId();
        long pointsPerBlock = configManager.getBuildRewardPointsPerBlock();
        long maxPoints = configManager.getBuildRewardMaxPointsPerPeriod();
        int minBlocks = configManager.getBuildRewardMinBlocks();
        boolean fromGovernment = configManager.isBuildRewardFromGovernment();
        UUID governmentUUID = UUID.fromString(configManager.getGovernmentUUID());
        String governmentName = configManager.getGovernmentName();

        for (Map.Entry<UUID, BuildStat> entry : stats.entrySet()) {
            UUID playerUUID = entry.getKey();
            BuildStat stat = entry.getValue();

            long points = 0;
            if (stat.score() >= minBlocks) {
                points = stat.score() * pointsPerBlock;
                if (maxPoints > 0) {
                    points = Math.min(points, maxPoints);
                }
            }

            try (Connection conn = dbManager.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    insertRewardRecord(conn, serverId, periodStart, periodEnd, playerUUID, stat, points);

                    if (points > 0) {
                        if (fromGovernment
                                && !economyManager.takeMoney(conn, governmentUUID, governmentName, points)) {
                            conn.rollback();
                            plugin.getLogger().warning("国庫の残高が不足しているため建築ポイントを付与できませんでした: "
                                    + stat.playerName());
                            continue;
                        }
                        if (!economyManager.giveMoney(conn, playerUUID, stat.playerName(), points)) {
                            conn.rollback();
                            continue;
                        }
                        recordTransaction(conn, serverId, governmentUUID, playerUUID, points);
                        if (fromGovernment) {
                            recordLedger(conn, points, stat.playerName(), stat.placed());
                        }
                    }

                    conn.commit();
                } catch (SQLIntegrityConstraintViolationException e) {
                    // 同じ期間の記録が既にある = 集計済み。二重付与しないようスキップする
                    conn.rollback();
                    continue;
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "建築ポイントの付与に失敗しました: " + stat.playerName(), e);
                continue;
            }

            if (points > 0) {
                notifyPlayer(playerUUID, stat, points);
            }
        }
    }

    private void insertRewardRecord(Connection conn, String serverId, LocalDateTime periodStart,
                                    LocalDateTime periodEnd, UUID playerUUID, BuildStat stat, long points)
            throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO fje_build_rewards "
                + "(server_id, period_start, period_end, minecraft_uuid, player_name, "
                + "blocks_placed, blocks_broken, score, points_granted) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            stmt.setString(1, serverId);
            stmt.setTimestamp(2, Timestamp.valueOf(periodStart));
            stmt.setTimestamp(3, Timestamp.valueOf(periodEnd));
            stmt.setString(4, playerUUID.toString());
            stmt.setString(5, stat.playerName());
            stmt.setInt(6, stat.placed());
            stmt.setInt(7, stat.broken());
            stmt.setInt(8, stat.score());
            stmt.setLong(9, points);
            stmt.executeUpdate();
        }
    }

    private void recordTransaction(Connection conn, String serverId, UUID governmentUUID,
                                   UUID playerUUID, long points) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO fje_transactions "
                + "(timestamp, server_id, buyer_uuid, owner_uuid, item_id, amount, price_total, tax_amount, net_profit) "
                + "VALUES (NOW(), ?, ?, ?, ?, ?, ?, ?, ?)")) {
            stmt.setString(1, serverId);
            stmt.setString(2, governmentUUID.toString());
            stmt.setString(3, playerUUID.toString());
            stmt.setString(4, TRANSACTION_ITEM_ID);
            stmt.setInt(5, 1);
            stmt.setLong(6, points);
            stmt.setLong(7, 0);
            stmt.setLong(8, points);
            stmt.executeUpdate();
        }
    }

    private void recordLedger(Connection conn, long points, String playerName, int placed) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO fje_government_ledger (timestamp, type, amount, description) VALUES (NOW(), ?, ?, ?)")) {
            stmt.setString(1, "BUILD_REWARD");
            stmt.setLong(2, points);
            stmt.setString(3, "To: " + playerName + " - 建築ボーナス（設置 " + placed + " ブロック）");
            stmt.executeUpdate();
        }
    }

    /**
     * オンラインなら付与内容をチャットで通知します（メインスレッドで実行されます）。
     *
     * @param playerUUID 対象プレイヤーのUUID
     * @param stat 集計結果
     * @param points 付与したポイント
     */
    private void notifyPlayer(UUID playerUUID, BuildStat stat, long points) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                player.sendMessage("§a建築ボーナスとして " + economyManager.formatMoney(points)
                        + " を受け取りました！（設置 " + stat.placed() + " / 破壊 " + stat.broken() + " ブロック）");
            }
        });
    }

    // ==========================================
    // Web管理画面からの任意期間集計（ポイント不加算）
    // ==========================================

    /**
     * 自サーバー宛の未処理な集計リクエストを取得し、順に処理します。
     * 非同期スケジューラから定期的に呼び出されます。
     */
    private void pollQueries() {
        if (!queryRunning.compareAndSet(false, true)) {
            return; // 前回の集計がまだ走っている
        }

        try {
            for (PendingQuery query : claimPendingQueries()) {
                executeQuery(query);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "建築集計リクエストの取得に失敗しました", e);
        } finally {
            queryRunning.set(false);
        }
    }

    /**
     * 自サーバー宛の PENDING なリクエストを RUNNING に更新して確保します。
     *
     * @return 処理対象のリクエスト一覧
     * @throws SQLException DBアクセスに失敗した場合
     */
    private List<PendingQuery> claimPendingQueries() throws SQLException {
        List<PendingQuery> claimed = new ArrayList<>();

        try (Connection conn = dbManager.getConnection()) {
            List<PendingQuery> candidates = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, range_start, range_end FROM fje_build_queries "
                    + "WHERE status = 'PENDING' AND server_id = ? ORDER BY id LIMIT 5")) {
                stmt.setString(1, configManager.getServerId());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        candidates.add(new PendingQuery(
                                rs.getInt("id"),
                                rs.getTimestamp("range_start").toLocalDateTime(),
                                rs.getTimestamp("range_end").toLocalDateTime()));
                    }
                }
            }

            for (PendingQuery query : candidates) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE fje_build_queries SET status = 'RUNNING' WHERE id = ? AND status = 'PENDING'")) {
                    stmt.setInt(1, query.id());
                    if (stmt.executeUpdate() > 0) {
                        claimed.add(query);
                    }
                }
            }
        }

        return claimed;
    }

    /**
     * 集計リクエストを実行し、結果を {@code fje_build_query_results} に書き込みます。
     * ランキング表示専用のため、ここではポイントを付与しません。
     *
     * @param query 処理するリクエスト
     */
    private void executeQuery(PendingQuery query) {
        try {
            if (!query.rangeEnd().isAfter(query.rangeStart())) {
                finishQuery(query.id(), "ERROR", "開始日時より後の終了日時を指定してください");
                return;
            }

            int maxDays = configManager.getBuildRewardMaxLookupDays();
            if (query.rangeStart().isBefore(LocalDateTime.now().minusDays(maxDays))) {
                finishQuery(query.id(), "ERROR", "遡れるのは最大 " + maxDays + " 日前までです");
                return;
            }

            CollectResult result = collectStats(query.rangeStart(), query.rangeEnd());
            saveQueryResults(query.id(), result.stats());
            finishQuery(query.id(), "DONE",
                    result.truncated() ? "ログ件数の上限に達したため、一部のみの集計結果です" : null);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "建築集計リクエストの処理に失敗しました: id=" + query.id(), e);
            try {
                finishQuery(query.id(), "ERROR", e.getMessage());
            } catch (SQLException inner) {
                plugin.getLogger().log(Level.WARNING, "集計リクエストの状態更新に失敗しました: id=" + query.id(), inner);
            }
        }
    }

    private void saveQueryResults(int queryId, Map<UUID, BuildStat> stats) throws SQLException {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO fje_build_query_results "
                     + "(query_id, minecraft_uuid, player_name, blocks_placed, blocks_broken, score) "
                     + "VALUES (?, ?, ?, ?, ?, ?)")) {
            for (Map.Entry<UUID, BuildStat> entry : stats.entrySet()) {
                stmt.setInt(1, queryId);
                stmt.setString(2, entry.getKey().toString());
                stmt.setString(3, entry.getValue().playerName());
                stmt.setInt(4, entry.getValue().placed());
                stmt.setInt(5, entry.getValue().broken());
                stmt.setInt(6, entry.getValue().score());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void finishQuery(int queryId, String status, String message) throws SQLException {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE fje_build_queries SET status = ?, error_message = ?, completed_at = NOW() WHERE id = ?")) {
            stmt.setString(1, status);
            stmt.setString(2, message);
            stmt.setInt(3, queryId);
            stmt.executeUpdate();
        }
    }

    // ==========================================
    // CoreProtect 集計
    // ==========================================

    /**
     * 指定期間の建築ログを CoreProtect から取得し、プレイヤーごとに集計します。
     * <p>
     * CoreProtect の API は「何秒前まで遡るか」しか指定できないため、
     * 開始時刻から現在までを取得したうえで終了時刻より新しいログを捨てています。
     * また、ユーザー無指定の全体検索は API 側で拒否されるため、
     * {@code fje_balances} に登録されているプレイヤー名を対象として渡します。
     * </p>
     *
     * @param start 集計期間の始端
     * @param end 集計期間の終端（この時刻ちょうどは含まない）
     * @return プレイヤーごとの集計結果（設置・破壊がどちらも0のプレイヤーは含まない）
     * @throws SQLException 対象プレイヤーの取得に失敗した場合
     */
    private CollectResult collectStats(LocalDateTime start, LocalDateTime end) throws SQLException {
        if (Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("CoreProtect のルックアップはメインスレッドから実行できません");
        }

        Map<String, TargetPlayer> players = loadTargetPlayers();
        if (players.isEmpty()) {
            return new CollectResult(new LinkedHashMap<>(), false);
        }

        long startMillis = toMillis(start);
        long endMillis = toMillis(end);
        long nowMillis = System.currentTimeMillis();
        int secondsBack = (int) Math.min(Integer.MAX_VALUE, Math.max(1, (nowMillis - startMillis) / 1000L) + 1);

        List<String> playerNames = new ArrayList<>();
        for (TargetPlayer target : players.values()) {
            playerNames.add(target.name());
        }
        List<Object> excludedBlocks = parseExcludedMaterials();
        List<Integer> actions = List.of(ACTION_BREAK, ACTION_PLACE);

        Map<String, int[]> counts = new HashMap<>(); // 小文字のプレイヤー名 -> [設置数, 破壊数]
        int offset = 0;
        boolean truncated = false;

        // CoreProtect の結果は新しい順で、ページングは OFFSET 指定になる。
        // 集計中に新しいログが入るとページの境目が数件ズレるため、厳密な件数ではなく
        // 「おおよその建築量」として扱う（1ページを大きめに取ってズレを小さくしている）。
        synchronized (lookupLock) {
            while (true) {
                List<String[]> rows = coreProtect.performPartialLookup(
                        secondsBack, playerNames, null, null, excludedBlocks, actions, 0, null, offset, LOOKUP_BATCH);
                if (rows == null || rows.isEmpty()) {
                    break;
                }

                for (String[] row : rows) {
                    CoreProtectAPI.ParseResult result = coreProtect.parseResult(row);
                    if (result.isRolledBack()) {
                        continue; // ロールバック済みの変更は建築量に数えない
                    }
                    long timestamp = result.getTimestamp();
                    if (timestamp < startMillis || timestamp >= endMillis) {
                        continue;
                    }

                    int[] counter = counts.computeIfAbsent(
                            result.getPlayer().toLowerCase(Locale.ROOT), key -> new int[2]);
                    if (result.getActionId() == ACTION_PLACE) {
                        counter[0]++;
                    } else if (result.getActionId() == ACTION_BREAK) {
                        counter[1]++;
                    }
                }

                offset += rows.size();
                if (rows.size() < LOOKUP_BATCH) {
                    break;
                }
                if (offset >= MAX_LOOKUP_ROWS) {
                    truncated = true;
                    plugin.getLogger().warning("建築ログが多すぎるため " + MAX_LOOKUP_ROWS + " 件で集計を打ち切りました");
                    break;
                }
            }
        }

        boolean subtractBreaks = configManager.isBuildRewardSubtractBreaks();
        Map<UUID, BuildStat> stats = new LinkedHashMap<>();
        for (Map.Entry<String, TargetPlayer> player : players.entrySet()) {
            int[] counter = counts.get(player.getKey());
            if (counter == null || (counter[0] == 0 && counter[1] == 0)) {
                continue;
            }
            int score = subtractBreaks ? Math.max(0, counter[0] - counter[1]) : counter[0];
            stats.put(player.getValue().uuid(), new BuildStat(player.getValue().name(), counter[0], counter[1], score));
        }
        return new CollectResult(stats, truncated);
    }

    /**
     * 集計対象にするプレイヤーを {@code fje_balances} から取得します。
     * 最終更新が新しい順に {@code build_reward.max_players} 件までを対象とします。
     *
     * @return 小文字のプレイヤー名 -> プレイヤー情報 のマップ
     * @throws SQLException DBアクセスに失敗した場合
     */
    private Map<String, TargetPlayer> loadTargetPlayers() throws SQLException {
        Map<String, TargetPlayer> players = new LinkedHashMap<>();
        int limit = Math.max(1, configManager.getBuildRewardMaxPlayers());

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT uuid, player_name FROM fje_balances WHERE uuid != ? ORDER BY last_update DESC LIMIT ?")) {
            stmt.setString(1, configManager.getGovernmentUUID());
            stmt.setInt(2, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("player_name");
                    if (name == null || name.isEmpty()) {
                        continue;
                    }
                    players.put(name.toLowerCase(Locale.ROOT),
                            new TargetPlayer(UUID.fromString(rs.getString("uuid")), name));
                }
            }
        }
        return players;
    }

    /**
     * config の {@code excluded_materials} を CoreProtect へ渡せる形（{@link Material} のリスト）に変換します。
     *
     * @return 除外するマテリアルのリスト
     */
    private List<Object> parseExcludedMaterials() {
        List<Object> excluded = new ArrayList<>();
        for (String name : configManager.getBuildRewardExcludedMaterials()) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                plugin.getLogger().warning("build_reward.excluded_materials の値が不正です: " + name);
                continue;
            }
            excluded.add(material);
        }
        return excluded;
    }

    private static long toMillis(LocalDateTime dateTime) {
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 集計対象のプレイヤーです。
     *
     * @param uuid プレイヤーのUUID
     * @param name {@code fje_balances} に登録されているプレイヤー名
     */
    private record TargetPlayer(UUID uuid, String name) {
    }

    /**
     * 集計結果と、ログ件数の上限で打ち切られたかどうかのフラグです。
     *
     * @param stats プレイヤーUUIDごとの集計結果
     * @param truncated 上限に達して一部のみの結果になっている場合は true
     */
    private record CollectResult(Map<UUID, BuildStat> stats, boolean truncated) {
    }

    /**
     * プレイヤー1人分の集計結果です。
     *
     * @param playerName プレイヤー名
     * @param placed 設置したブロック数
     * @param broken 破壊したブロック数
     * @param score ポイント算出に使うスコア
     */
    private record BuildStat(String playerName, int placed, int broken, int score) {
    }

    /**
     * Web管理画面から登録された集計リクエストです。
     *
     * @param id リクエストID
     * @param rangeStart 集計開始日時
     * @param rangeEnd 集計終了日時
     */
    private record PendingQuery(int id, LocalDateTime rangeStart, LocalDateTime rangeEnd) {
    }
}
