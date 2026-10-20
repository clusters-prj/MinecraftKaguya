package trigger.tukuyomiWeapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ウルトの共通処理: チャージ(経験値バーに表示)・発動可否の判定・効果中の状態管理(古狩人の遺骨・唐傘弾)。
 * チャージは、対象の武器でダメージを与えるたびに、武器ごとの割合(%)だけ増える。100%で発動できる。
 * チャージはプレイヤーのデータに保存する(ログアウトしても残る。死亡・スペクテイターでリセット)。
 */
public final class UltimateManager {
    /** 発動に必要なチャージ(%) */
    static final double FULL = 100.0;
    /** true: ウルトアイテムをホットバーに持っているあいだの攻撃だけ、チャージに数える */
    static final boolean COUNT_ONLY_WHILE_IN_HOTBAR = true;
    /** true: プレイヤーに加えて、敵対モブ(Enemy)への攻撃も数える。false: プレイヤーへの攻撃だけ */
    static final boolean COUNT_MOBS = true;
    /** ウルトを発動してから、チャージの加算を止める時間(ミリ秒) */
    static final long LOCKOUT_AFTER_ACTIVATION_MS = 20_000L;

    /**
     * 武器ごとの、1回ダメージを与えるたびに増えるチャージ(%)。
     * ここにない武器(ウルトのアイテムなど)は、チャージを増やさない。
     */
    static final Map<Weapon, Double> CHARGE_PER_HIT = new EnumMap<>(Weapon.class);
    static {
        CHARGE_PER_HIT.put(Weapon.LIGHT_SWORD, 10.0);        // スーパーノヴァ
        CHARGE_PER_HIT.put(Weapon.SAW_CLEAVER, 3.0);         // ノコギリ鉈(両形態)
        CHARGE_PER_HIT.put(Weapon.SAW_CLEAVER_DRAWN, 3.0);
        CHARGE_PER_HIT.put(Weapon.REJECTION, 1.0);           // 拒絶(吹き飛ばした相手ごと)
        CHARGE_PER_HIT.put(Weapon.BEAST_CLEAVER, 4.0);       // 獣肉断ち(両形態)
        CHARGE_PER_HIT.put(Weapon.BEAST_CLEAVER_DRAWN, 4.0);
        CHARGE_PER_HIT.put(Weapon.PILE_HAMMER, 20.0);        // パイルハンマー(強攻撃のみ)
        CHARGE_PER_HIT.put(Weapon.CANE_SHEATHED, 2.0);       // 仕込み杖(両形態)
        CHARGE_PER_HIT.put(Weapon.CANE_DRAWN, 2.0);
        CHARGE_PER_HIT.put(Weapon.HUNTING_HORN, 3.0);        // 狩猟笛
        CHARGE_PER_HIT.put(Weapon.GUNLANCE, 6.0);            // 帝(ガンランス「MIKA-D0」)
        CHARGE_PER_HIT.put(Weapon.GUITAR, 7.0);              // ギター
        CHARGE_PER_HIT.put(Weapon.FUSHI_BOMB, 2.0);          // FUSHI爆弾
        CHARGE_PER_HIT.put(Weapon.TSUKIBITO, 4.0);           // 月人
        CHARGE_PER_HIT.put(Weapon.PANCAKE, 1.0);             // パンケーキ(効果を与えた相手ごと)
        CHARGE_PER_HIT.put(Weapon.TIMED_BOMB, 5.0);          // ダムダム弾
    }

    /** パイルハンマーの弱攻撃(ダメージ1)で増えるチャージ(%) */
    static final double PILE_HAMMER_WEAK_CHARGE = 0.2;
    /** 素手(拳)で殴ったときに増えるチャージ(%) */
    static final double FIST_CHARGE = 0.1;
    /** バニラの武器(このプラグイン以外のアイテム)で殴ったとき、見込みダメージにかける割合。増えるチャージ(%) = 見込みダメージ × この値 */
    static final double VANILLA_DAMAGE_CHARGE_RATIO = 1.0 / 3.0;

    // 古狩人の遺骨
    static final int BONE_DURATION_TICKS = 16 * 20;
    static final double LUNGE_HORIZONTAL_SPEED = 1.0; // 突進の水平速度(ブロック/tick)。調整用
    static final double LUNGE_MIN_VERTICAL = 0.42;    // 通常のジャンプの初速
    static final long MOVE_SAMPLE_FRESH_MS = 250;     // キー入力が取れないとき、これ以内の移動の向きを使う
    static final double MOVE_SAMPLE_MIN_STEP = 0.03;  // これ未満の移動は、向きとして記録しない

    private final TukuyomiWeapons plugin;
    private final NamespacedKey chargeKey;
    private final Set<UUID> showingGauge = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Float> gaugeProgress = new ConcurrentHashMap<>(); // ゲージに出している割合(0〜1)。毎tickの送り直しで使う
    private final Map<UUID, Long> boneUntil = new ConcurrentHashMap<>();
    private final Map<UUID, MoveSample> lastMove = new ConcurrentHashMap<>(); // 直近の水平方向の移動(キー入力が取れないとき用)
    private final Set<UUID> karakasaReady = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lockoutUntil = new ConcurrentHashMap<>(); // 発動後の加算停止の終了時刻(死亡・ログアウトでは消さない)

    /** 直近の水平方向の移動の向き(長さ1)と、その時刻 */
    private static final class MoveSample {
        final Vector dir;
        final long at;
        MoveSample(Vector dir, long at) { this.dir = dir; this.at = at; }
    }

    UltimateManager(TukuyomiWeapons plugin) {
        this.plugin = plugin;
        this.chargeKey = new NamespacedKey(plugin, "ult_charge");
    }

    // ===== チャージ =====

    /** いまのチャージ(0〜100%) */
    double getCharge(Player p) {
        Double v = p.getPersistentDataContainer().get(chargeKey, PersistentDataType.DOUBLE);
        return v == null ? 0 : v;
    }

    private void setCharge(Player p, double percent) {
        p.getPersistentDataContainer().set(chargeKey, PersistentDataType.DOUBLE, Math.max(0, Math.min(FULL, percent)));
    }

    boolean hasUltInHotbar(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            Weapon w = plugin.getWeapon(it);
            if (w != null && w.isUltimate()) return true;
        }
        return false;
    }

    /**
     * attacker が weapon で victim にダメージを与える(効果を与える)たびに呼ぶ。
     * 武器ごとの割合だけチャージが増える。攻撃した本人のスレッドで処理する(Folia 対応)。
     */
    void addCharge(Player attacker, Weapon weapon, LivingEntity victim) {
        Double percent = CHARGE_PER_HIT.get(weapon);
        if (percent == null) return;
        addChargeFor(attacker, percent, victim);
    }

    /** 割合を直接指定して、victim への攻撃ぶんのチャージを増やす(弱攻撃・素手など、武器の表にない攻撃用) */
    void addChargeFor(Player attacker, double percent, LivingEntity victim) {
        if (victim instanceof ArmorStand || victim.equals(attacker)) return;
        if (!(victim instanceof Player) && !(COUNT_MOBS && victim instanceof Enemy)) return;
        addChargePercent(attacker, percent);
    }

    /** チャージを percent(%)ぶん増やす。発動後の加算停止中・ウルトを持っていないときは、何もしない */
    void addChargePercent(Player p, double percent) {
        plugin.onEntity(p, () -> {
            Long lock = lockoutUntil.get(p.getUniqueId());
            if (lock != null) {
                if (lock > System.currentTimeMillis()) return; // 発動後20秒は加算しない
                lockoutUntil.remove(p.getUniqueId(), lock);
            }
            if (COUNT_ONLY_WHILE_IN_HOTBAR && !hasUltInHotbar(p)) return;
            double before = getCharge(p);
            if (before >= FULL) return;
            double after = Math.min(FULL, before + percent);
            setCharge(p, after);
            if (before < FULL && after >= FULL) {
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f);
            }
            if (hasUltInHotbar(p)) showGauge(p);
        });
    }

    /** チャージが満タンか(消費はしない) */
    boolean isFull(Player p) {
        return getCharge(p) >= FULL - 1.0E-9;
    }

    /** 発動に必要なチャージがあれば消費して true。足りなければ不足を知らせて false */
    boolean consumeCharge(Player p) {
        double c = getCharge(p);
        if (c < FULL - 1.0E-9) {
            p.sendActionBar(Component.text("ウルトのチャージ: " + (int) Math.floor(c) + "%", NamedTextColor.RED));
            return false;
        }
        setCharge(p, 0);
        lockoutUntil.put(p.getUniqueId(), System.currentTimeMillis() + LOCKOUT_AFTER_ACTIVATION_MS); // 発動後20秒は加算しない
        showGauge(p);
        return true;
    }

    // ===== 経験値バーのゲージ表示 =====

    /** 経験値バーに、チャージ量を見た目だけ送る(本物の経験値には触らない。レベルの数字は本物のまま) */
    private void showGauge(Player p) {
        float progress = (float) Math.min(1.0, getCharge(p) / FULL);
        gaugeProgress.put(p.getUniqueId(), progress);
        showingGauge.add(p.getUniqueId());
        p.sendExperienceChange(progress, p.getLevel());
    }

    /**
     * 毎tick(0.05秒ごと)呼ぶ。ゲージを出しているプレイヤーに、ゲージを送り直す。
     * 経験値を拾うなどして、サーバーが本物の経験値を送ってくると、クライアントの表示が一瞬本物に戻る。
     * それを次のtickの頭で上書きするので、本物の表示が見えるのは最大でも1tick。送るのは数バイトのパケット1つだけ。
     */
    void onFastTick(Player p) {
        UUID id = p.getUniqueId();
        if (!showingGauge.contains(id)) return;
        Float progress = gaugeProgress.get(id);
        if (progress == null) return;
        p.sendExperienceChange(progress, p.getLevel());
    }

    /** プレイヤーごとの見回り(0.1秒ごと)から呼ぶ。t は見回りの回数 */
    void onTick(Player p, int t) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long until = boneUntil.get(id);
        if (until != null && until <= now) {
            boneUntil.remove(id, until);
            lastMove.remove(id);
        }
        if (hasUltInHotbar(p)) {
            // ゲージを出す。表示中は、毎tickの onFastTick が送り直すので、ここでは割合を更新するだけ(出し始めた時だけ送る)
            float progress = (float) Math.min(1.0, getCharge(p) / FULL);
            gaugeProgress.put(id, progress);
            if (showingGauge.add(id)) p.sendExperienceChange(progress, p.getLevel());
            if (t % 5 == 0) sendStatus(p);
        } else if (showingGauge.remove(id)) {
            gaugeProgress.remove(id);
            p.sendExperienceChange(p.getExp(), p.getLevel()); // 本物の経験値バーに戻す
        }
    }

    private void sendStatus(Player p) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long until = boneUntil.get(id);
        if (until != null && until > now) {
            p.sendActionBar(Component.text(String.format("古狩人の遺骨: 残り %.1f秒", (until - now) / 1000.0), NamedTextColor.GOLD));
        } else if (karakasaReady.contains(id)) {
            p.sendActionBar(Component.text("唐傘弾: 次に放つ矢が爆発する", NamedTextColor.GOLD));
        } else if (isFull(p)) {
            p.sendActionBar(Component.text("ウルト発動可能", NamedTextColor.GOLD));
        }
    }

    // ===== 古狩人の遺骨: 16秒間、ジャンプで、押しているキーの向きへ突進 =====

    void startBone(Player p) {
        boneUntil.put(p.getUniqueId(), System.currentTimeMillis() + BONE_DURATION_TICKS * 50L);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1.6f);
    }

    /** 古狩人の遺骨の効果中だけ、直近の水平方向の移動の向きを覚えておく(パッドなど、キー入力が取れない場合の代わり) */
    void onMove(Player p, Location from, Location to) {
        UUID id = p.getUniqueId();
        if (to == null || !boneUntil.containsKey(id)) return;
        Vector d = new Vector(to.getX() - from.getX(), 0, to.getZ() - from.getZ());
        if (d.lengthSquared() < MOVE_SAMPLE_MIN_STEP * MOVE_SAMPLE_MIN_STEP) return;
        lastMove.put(id, new MoveSample(d.normalize(), System.currentTimeMillis()));
    }

    void onJump(Player p) {
        UUID id = p.getUniqueId();
        Long until = boneUntil.get(id);
        if (until == null) return;
        if (until <= System.currentTimeMillis()) {
            boneUntil.remove(id, until);
            lastMove.remove(id);
            return;
        }
        // 突進の向き: ①押しているキー(WASD)の向き(W+D なら右前45度) ②キー入力が取れないとき(パッドなど)は、直前の移動の向き
        // ③どちらも無ければ、向いている方向。キー入力を最優先にするのは、移動の向きは慣性で遅れるため
        double yaw = Math.toRadians(p.getLocation().getYaw());
        Vector forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        Input in = p.getCurrentInput();
        Vector dir = new Vector(0, 0, 0);
        if (in.isForward()) dir.add(forward);
        if (in.isBackward()) dir.subtract(forward);
        if (in.isRight()) dir.add(right);
        if (in.isLeft()) dir.subtract(right);
        if (dir.lengthSquared() < 1.0E-4) {
            MoveSample m = lastMove.get(id);
            if (m != null && System.currentTimeMillis() - m.at <= MOVE_SAMPLE_FRESH_MS) dir = m.dir.clone();
            else dir = forward.clone(); // その場ジャンプなどは、向いている方向へ
        }
        dir.normalize();
        Vector v = dir.clone().multiply(LUNGE_HORIZONTAL_SPEED);
        v.setY(Math.max(p.getVelocity().getY(), LUNGE_MIN_VERTICAL));
        p.setVelocity(v);
        Location loc = p.getLocation();
        p.getWorld().playSound(loc, Sound.ITEM_TRIDENT_RIPTIDE_3, 0.8f, 1.2f);
        p.getWorld().spawnParticle(Particle.CLOUD, loc, 8, 0.3, 0.1, 0.3, 0.02);
    }

    // ===== 唐傘弾: 発動後に最初に放つ矢が爆発する =====

    void armKarakasa(Player p) {
        karakasaReady.add(p.getUniqueId());
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 1f, 0.8f);
    }

    /** 装填中なら解除して true(その矢を爆発する矢にする) */
    boolean consumeKarakasa(Player p) {
        return karakasaReady.remove(p.getUniqueId());
    }

    // ===== 状態の後始末 =====

    /** 効果中の状態を消す(ログアウト・スペクテイター・死亡) */
    void clearActive(UUID id) {
        boneUntil.remove(id);
        lastMove.remove(id);
        karakasaReady.remove(id);
        showingGauge.remove(id);
        gaugeProgress.remove(id);
    }

    /** 死亡・スペクテイター: チャージも効果中の状態もリセットし、経験値バーを本物の表示に戻す */
    void reset(Player p) {
        clearActive(p.getUniqueId());
        setCharge(p, 0);
        p.sendExperienceChange(p.getExp(), p.getLevel());
    }
}
