package weaponplus.example.tukuyomiWeapons;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * カスタム武器プラグイン (Paper 26.2 / Java 25)
 */
public class TukuyomiWeapons extends JavaPlugin implements Listener, TabExecutor {

    /**
     * 武器定義。
     * material / modelData はリソースパック側の設定に合わせて変更してください。
     * damage < 0 または speed < 0 の項目は、素材のデフォルト値のままになります。
     * reach は攻撃リーチの倍率です(1.0 = 通常)。
     */
    enum Weapon {
        //            id              表示名           素材                    CMD   ダメージ 攻撃速度 リーチ
        LIGHT_SWORD  ("light_sword",   "光の剣",        Material.DIAMOND_SWORD, 1001, -1,    -1,     1.0),
        SAW_CLEAVER  ("saw_cleaver",   "ノコギリ鉈",    Material.IRON_SWORD,    1002, 8,     2.0,    1.0),
        REJECTION    ("rejection",     "拒絶",          Material.BLAZE_ROD,     1003, -1,    -1,     1.0),
        BEAST_CLEAVER("beast_cleaver", "獣肉断ち",      Material.IRON_AXE,      1004, 16,    1.5,    1.5),
        PILE_HAMMER  ("pile_hammer",   "パイルハンマー", Material.NETHERITE_AXE,  1005, 60,    0.1,    1.0 / 3.0),
        // 仕込み杖: 右クリックで変形前 ⇄ 変形後を切り替える。変形後のリーチは 4.5ブロック ÷ 通常3ブロック
        CANE_SHEATHED("cane_sheathed", "仕込み杖(変形前)", Material.WOODEN_SWORD, 1006, 10,    2.0,    1.0),
        CANE_DRAWN   ("cane_drawn",    "仕込み杖(変形後)", Material.NETHERITE_SWORD, 1007, 12,  1.6,    4.5 / 3.0),
        // FUSHI爆弾: 雪玉と同じ投擲物。着弾点で爆風が起きる
        FUSHI_BOMB   ("fushi_bomb",    "FUSHI爆弾",     Material.SNOWBALL,      1008, -1,    -1,     1.0),
        // もと光るダムダム弾(旧: 八千代の筍。IDは timed_bomb のまま): 投げる → 着弾時に3ダメージ → 0.5秒後に爆発して周囲に30ダメージ
        TIMED_BOMB   ("timed_bomb",    "もと光るダムダム弾", Material.SNOWBALL,      1009, -1,    -1,     1.0),
        // 自律稼働無人爆弾(月人): 低速(秒速3ブロック)で直進する投擲物。着弾で爆発、10秒で自己破壊
        TSUKIBITO    ("tsukibito_bomb", "自律稼働無人爆弾(月人)", Material.SNOWBALL, 1010, -1, -1, 1.0),
        // 彩葉特製、毒素マシマシ粉水パンケーキ: 雪玉型。着弾時、半径3ブロックに吐き気・盲目・爆音(本人にだけ聞こえる)。クールタイム15秒
        PANCAKE      ("pancake",       "彩葉特製、毒素マシマシ粉水パンケーキ", Material.SNOWBALL, 1011, -1, -1, 1.0),
        // キーホルダー: 光の剣を持っている間、オフハンドを埋める自動付与アイテム(効果なし)。/weapon では出さない
        KEYCHAIN     ("keychain",      "キーホルダー",   Material.PAPER,         1012, -1,    -1,     1.0),
        // 狩猟笛: 剣ベース(ダメージ10、攻撃速度は剣と同じ1.6)。手に持っている間、攻撃力増加Iと移動速度増加I
        HUNTING_HORN ("hunting_horn",  "狩猟笛",         Material.STONE_SWORD,   1013, 10,    1.6,    1.0),
        // ガンランス「MIKA-D0」: 斧ベース。攻撃速度 0.6、ダメージ22
        GUNLANCE     ("gunlance_mika_d0", "ガンランス「MIKA-D0」", Material.DIAMOND_AXE, 1014, 22, 0.6, 1.0),
        // ギター「共振波動」: 0.5秒チャージ(鈍足III)でウォーデンの衝撃波(24ダメージ)。クールタイム6秒
        GUITAR       ("guitar_resonance", "ギター「共振波動」", Material.GOLDEN_SWORD, 1015, -1, -1, 1.0),
        // 獣肉断ち(変形後): 斧ベース。ダメージ12・リーチ7ブロック・攻撃速度0.5。右クリックで獣肉断ち(変形前)に戻る
        BEAST_CLEAVER_DRAWN("beast_cleaver_drawn", "獣肉断ち(変形後)", Material.STONE_AXE, 1016, 12, 0.5, 7.0 / 3.0),
        // ノコギリ鉈(変形後): 剣ベース。ダメージ+2(10)・リーチ4.5ブロック・攻撃速度1。両形態とも、命中時に毒II(10秒)
        SAW_CLEAVER_DRAWN("saw_cleaver_drawn", "ノコギリ鉈(変形後)", Material.IRON_SWORD, 1017, 10, 1.0, 4.5 / 3.0);

        final String id, displayName;
        final Material material;
        final int modelData;
        final double damage, speed, reach;

        Weapon(String id, String displayName, Material material, int modelData, double damage, double speed, double reach) {
            this.id = id;
            this.displayName = displayName;
            this.material = material;
            this.modelData = modelData;
            this.damage = damage;
            this.speed = speed;
            this.reach = reach;
        }

        static Weapon byId(String id) {
            for (Weapon w : values()) if (w.id.equalsIgnoreCase(id)) return w;
            return null;
        }
    }

    // ===== 調整用の定数 =====
    private static final int LASER_CHARGE_TICKS = 60;      // 光の剣: チャージ 3秒
    private static final int LASER_STIFF_TICKS = 100;      // 光の剣: 硬直 5秒
    private static final double LASER_RANGE = 70;          // 光の剣: 射程
    private static final boolean LASER_PIERCE_WALLS = true; // 光の剣: 衝撃波が壁(ブロック)を貫通するか
    private static final int GUITAR_CHARGE_TICKS = 10;       // ギター: チャージ 0.5秒
    private static final int GUITAR_SLOWNESS_AMPLIFIER = 2;  // ギター: チャージ中の鈍足(レベルIII = 2)
    private static final double GUITAR_DAMAGE = 24;          // ギター: 衝撃波のダメージ
    private static final int GUITAR_COOLDOWN_TICKS = 120;    // ギター: クールタイム 6秒(発射した時点から)
    private static final double GUITAR_RANGE = 15;           // ギター: 射程
    private static final boolean GUITAR_PIERCE_WALLS = false; // ギター: 衝撃波が壁を貫通するか(false = 壁で止まる)
    private static final int HORN_EFFECT_TICKS = 300;        // 狩猟笛: 効果時間 15秒(持っている間は、1秒ごとに更新)
    private static final boolean HORN_NEEDS_MAIN_HAND = true; // 狩猟笛: true=手に持っている間 / false=インベントリにある間
    private static final boolean HORN_CLEAR_ON_UNEQUIP = false; // 狩猟笛: 持ち替えたとき、効果をすぐ消すか(false=15秒残る)
    private static final double LASER_DAMAGE = 50;         // 光の剣: ダメージ
    private static final double REJECT_RADIUS = 5;         // 拒絶: 半径
    private static final int LASER_COOLDOWN_TICKS = 400;   // 光の剣: クールタイム 20秒(発射した時点から)
    private static final int REJECT_COOLDOWN_TICKS = 200;  // 拒絶: クールタイム 10秒
    private static final double CANE_SLASH_RANGE = 4.5;    // 仕込み杖(変形後): 斬撃の半径(リーチと同じ)
    private static final double CANE_SLASH_ANGLE = 30;     // 仕込み杖(変形後): 斬撃の角度(前方の扇形の全体の角度)
    private static final double CANE_SLASH_DAMAGE = 6;     // 仕込み杖(変形後): 斬撃のダメージ
    private static final double BEAST_SLASH_RANGE = 4.5;   // 獣肉断ち: 斬撃の半径(リーチと同じ 3 × 1.5)
    private static final double BEAST_SLASH_ANGLE = 60;    // 獣肉断ち: 斬撃の角度(前方の扇形の全体の角度)
    private static final double BEAST_SLASH_DAMAGE = 16;   // 獣肉断ち: 斬撃のダメージ(通常攻撃と同じ)
    private static final int BEAST_SLASH_INTERVAL_TICKS = 14; // 獣肉断ち: 斬撃の連発間隔(0.7秒)
    private static final double BEAST_DRAWN_SLASH_RANGE = 7;   // 獣肉断ち(変形後): 斬撃の半径
    private static final double BEAST_DRAWN_SLASH_ANGLE = 30;  // 獣肉断ち(変形後): 斬撃の角度
    private static final double BEAST_DRAWN_SLASH_DAMAGE = 6;  // 獣肉断ち(変形後): 斬撃のダメージ
    private static final int BEAST_DRAWN_SLASH_INTERVAL_TICKS = 40; // 獣肉断ち(変形後): 斬撃の連発間隔(2秒。攻撃速度0.5のチャージ時間と同じ)
    private static final float BEAST_DRAWN_STRONG_THRESHOLD = 0.9f;    // 獣肉断ち(変形後): 強攻撃とみなす攻撃のチャージ度
    private static final int SAW_POISON_TICKS = 200;           // ノコギリ鉈: 命中時の毒の時間(10秒)
    private static final int SAW_POISON_AMPLIFIER = 1;         // ノコギリ鉈: 毒のレベル(II = 1)
    private static final double LASER_MESSAGE_RADIUS = 70;     // 光の剣: 発射シーケンスのチャットを送る範囲(ブロック。0=本人のみ、負の値=全員)
    private static final String[] RAILGUN_WARNING_LINES = {    // 光の剣: [RAILGUN WARNING] の下に出す赤文字(5行)
            "!! WARNING !!   WARNING !!   WARNING !!",
            "超高出力レールガン 充填中",
            "射線上の全生命体は 直ちに退避せよ",
            "ターゲット捕捉 ロック完了",
            "!! WARNING !!   WARNING !!   WARNING !!"
    };
    private static final double FUSHI_RADIUS = 2;          // FUSHI爆弾: 爆風の半径
    private static final double FUSHI_DAMAGE = 2;          // FUSHI爆弾: 爆風のダメージ
    private static final double FUSHI_STUN_CHANCE = 0.01;  // FUSHI爆弾: 停止・盲目になる確率(1/100)
    private static final int FUSHI_STUN_TICKS = 8000;      // FUSHI爆弾: 停止・盲目の時間(400秒)
    private static final boolean FUSHI_HITS_THROWER = false; // FUSHI爆弾: 投げた本人も爆風を受けるか
    private static final double TIMED_IMPACT_DAMAGE = 3;   // 時限爆弾: 着弾時(直撃した相手)のダメージ
    private static final int TIMED_DELAY_TICKS = 10;       // もと光るダムダム弾: 着弾から爆発までの時間(0.5秒)
    private static final double TIMED_RADIUS = 4;          // 時限爆弾: 爆発の半径(仕様に無いので仮)
    private static final double TIMED_DAMAGE = 30;         // 時限爆弾: 爆発のダメージ
    private static final boolean TIMED_HITS_THROWER = false; // 時限爆弾: 投げた本人も爆発を受けるか
    private static final double MOON_SPEED = 0.15;         // 月人: 速度(ブロック/tick。0.15 = 秒速3ブロック)
    private static final int MOON_LIFETIME_TICKS = 200;    // 月人: 自己破壊までの時間(10秒)
    private static final double MOON_RADIUS = 7;           // 月人: 爆発の半径
    private static final double MOON_CENTER_DAMAGE = 50;   // 月人: 爆発の中心部のダメージ(外側ほど小さくなる)
    private static final boolean MOON_HITS_THROWER = false; // 月人: 投げた本人も爆発を受けるか
    private static final double MOON_TIMEOUT_DAMAGE = 30;  // 月人: 自己破壊時の爆発の中心部のダメージ
    private static final double MOON_HOMING_RANGE = 12;    // 月人: 追尾する敵を探す距離(仕様に無いので仮)
    private static final double MOON_TURN_RATE = 0.2;      // 月人: 1tickあたりの方向転換の度合い(0〜1。大きいほど急旋回)
    private static final double PANCAKE_RADIUS = 3;        // パンケーキ: 効果の半径
    private static final int PANCAKE_EFFECT_TICKS = 80;    // パンケーキ: 効果の持続時間
    private static final int PANCAKE_COOLDOWN_TICKS = 300; // パンケーキ: クールタイム 15秒(投げた時点から)
    private static final int PANCAKE_SOUND_INTERVAL_TICKS = 10; // パンケーキ: 爆音を鳴らす間隔(持続時間の間くり返す)
    private static final boolean PANCAKE_HITS_THROWER = false;  // パンケーキ: 投げた本人も効果を受けるか
    private static final int CANE_SLASH_INTERVAL_TICKS = 12; // 仕込み杖(変形後): 斬撃の連発間隔(0.6秒)
    private static final int PILE_STIFF_TICKS = 60;        // パイルハンマー: 硬直 3秒
    // パイルハンマー: 強攻撃の判定。バニラは攻撃のチャージ率 f の二乗でダメージを減らす(× 0.2 + 0.8 × f²)。
    // 強攻撃(チャージ90%超)のときの倍率 0.2 + 0.8 × 0.9² = 0.848 以上なら強攻撃とみなす。
    private static final double PILE_STRONG_RATIO = 0.848;
    private static final double PILE_WEAK_DAMAGE = 1.0;    // パイルハンマー: 弱攻撃のダメージ(追加効果なし)
    private static final int PILE_ARMOR_DAMAGE = 400;      // パイルハンマー: 命中時に防具1つあたり減らす耐久値

    private NamespacedKey weaponKey;
    private NamespacedKey keychainDataKey; // キーホルダーが預かっている、元のオフハンドのアイテム
    private NamespacedKey stiffJumpKey; // 硬直中のジャンプ封じ用の属性修飾子
    private final Map<UUID, Long> stiffUntil = new ConcurrentHashMap<>(); // 硬直の終了時刻(ms)
    private final Set<UUID> charging = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> laserCooldown = new ConcurrentHashMap<>();  // 光の剣のクールタイム終了時刻(ms)
    private final Map<UUID, Long> rejectCooldown = new ConcurrentHashMap<>(); // 拒絶のクールタイム終了時刻(ms)
    private final Map<UUID, Long> slashCooldown = new ConcurrentHashMap<>();  // 斬撃の連発防止(ms)
    private final Map<UUID, Long> guitarCooldown = new ConcurrentHashMap<>();  // ギターのクールタイム終了時刻(ms)
    private final Set<UUID> hornHolders = ConcurrentHashMap.newKeySet();           // 狩猟笛の効果を与えているプレイヤー
    private final Map<UUID, Long> pancakeCooldown = new ConcurrentHashMap<>(); // パンケーキのクールタイム終了時刻(ms)
    // 斬撃・爆風などのダメージ処理中(武器の追加効果が連鎖するのを防ぐ)。ダメージ処理はそのスレッド内で完結するので ThreadLocal にする(Folia 対応)
    private final ThreadLocal<Boolean> inSlash = ThreadLocal.withInitial(() -> false);

    @Override
    public void onEnable() {
        weaponKey = new NamespacedKey(this, "weapon_id");
        stiffJumpKey = new NamespacedKey(this, "stiff_jump_lock");
        keychainDataKey = new NamespacedKey(this, "keychain_stored_offhand");
        getServer().getPluginManager().registerEvents(this, this);

        // すでにオンラインのプレイヤーにも、見回りを始める(以降は参加時に始める)
        for (Player pl : Bukkit.getOnlinePlayers()) startPlayerTask(pl);
        var cmd = getCommand("weapon");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    // ===== Paper / Folia 両対応の土台 =====
    // Folia ではメインスレッドが無く、処理はリージョン(地域)ごとのスレッドで動く。
    // BukkitRunnable / Bukkit.getScheduler() は使わず、エンティティ・リージョンのスケジューラを使う。
    // (これらのスケジューラは Paper にもあり、Paper ではメインスレッドで動くので、同じコードで両方に対応できる)

    /** このエンティティを扱うスレッドにいれば直ちに、そうでなければそのエンティティのスケジューラで実行する */
    private void onEntity(Entity e, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(e)) {
            task.run();
        } else {
            e.getScheduler().run(this, t -> task.run(), null);
        }
    }

    /** この場所を扱うスレッドにいれば直ちに、そうでなければリージョンスケジューラで実行する */
    private void atLocation(Location loc, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(loc)) {
            task.run();
        } else {
            Bukkit.getRegionScheduler().run(this, loc, t -> task.run());
        }
    }

    /** 周囲の生き物を取る。Folia で他のリージョンに触れてしまう場合は、取れた範囲だけにする */
    private Collection<LivingEntity> nearbyLiving(Location center, double radius) {
        try {
            return center.getWorld().getNearbyLivingEntities(center, radius);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** プレイヤーごとの見回り(0.1秒ごと): キーホルダーと、狩猟笛のバフ(1秒ごと) */
    private void startPlayerTask(Player p) {
        AtomicInteger n = new AtomicInteger(0);
        p.getScheduler().runAtFixedRate(this, task -> {
            if (!p.isOnline()) {
                task.cancel();
                return;
            }
            int t = n.incrementAndGet();
            if (p.getGameMode() == GameMode.SPECTATOR) return;
            tickKeychain(p);
            if (t % 10 == 0) tickHorn(p);
        }, null, 1L, 2L);
    }

    // ===== アイテム生成・判定 =====

    private ItemStack createItem(Weapon w) {
        ItemStack item = new ItemStack(w.material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(w.displayName, NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setFloats(List.of((float) w.modelData)); // 旧来の整数CMDと同じ扱い
        meta.setCustomModelDataComponent(cmd);
        if (w.material.getMaxDurability() > 0) meta.setUnbreakable(true); // 耐久のある素材だけ
        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.STRING, w.id);

        // プレイヤー基礎値: ダメージ1 / 攻撃速度4 に対する加算値で指定する
        if (w.damage >= 0 || w.speed >= 0) {
            double dmg = w.damage >= 0 ? w.damage : 1; // 未指定ならダメージは基礎値のまま
            double spd = w.speed >= 0 ? w.speed : 4;
            meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                    new NamespacedKey(this, "dmg_" + w.id), dmg - 1,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                    new NamespacedKey(this, "spd_" + w.id), spd - 4,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        }
        // リーチ(通常3ブロックに対する倍率)
        if (w.reach != 1.0) {
            meta.addAttributeModifier(Attribute.ENTITY_INTERACTION_RANGE, new AttributeModifier(
                    new NamespacedKey(this, "reach_" + w.id), w.reach - 1,
                    AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.MAINHAND));
        }
        item.setItemMeta(meta);
        return item;
    }

    private Weapon getWeapon(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
        return id == null ? null : Weapon.byId(id);
    }

    // ===== 硬直 =====

    private boolean isStiff(Player p) {
        Long until = stiffUntil.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    private void stiffen(Player p, int ticks) {
        stiffUntil.put(p.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 6, false, false, false));
        // ジャンプ封じ: 跳躍力の属性を 0 にする(旧来のジャンプ力上昇エフェクト128は、今のバージョンでは逆に超ジャンプになる)
        AttributeInstance jump = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) {
            jump.removeModifier(stiffJumpKey);
            jump.addModifier(new AttributeModifier(stiffJumpKey, -1.0,
                    AttributeModifier.Operation.ADD_SCALAR)); // 基礎値 × (1 - 1) = 0
            // 硬直が終わったら外す(延長されていれば、最後の硬直の分が外す)
            p.getScheduler().runDelayed(this, t -> {
                if (!isStiff(p)) removeJumpLock(p);
            }, null, ticks + 1L);
        }
    }

    private void removeJumpLock(Player p) {
        AttributeInstance jump = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) jump.removeModifier(stiffJumpKey);
    }

    @Override
    public void onDisable() {
        // サーバー停止・リロード時に、ジャンプ封じが残らないようにする
        for (Player pl : Bukkit.getOnlinePlayers()) {
            try {
                removeJumpLock(pl);
                restoreKeychain(pl); // オフハンドのキーホルダーは、元のアイテムに戻す
            } catch (RuntimeException ignored) {
                // Folia: 停止時に、別リージョンのプレイヤーには触れないことがある(次回ログイン時に掃除される)
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // 硬直中に落ちた・再起動した場合の、取り残しの掃除
        removeJumpLock(e.getPlayer());
        scrubStrayKeychains(e.getPlayer());
        startPlayerTask(e.getPlayer());
    }

    // ===== クールタイム =====

    /** クールタイム中ならアクションバーに残り時間を出して true を返す */
    private boolean onCooldown(Player p, Map<UUID, Long> map) {
        Long until = map.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (until != null && until > now) {
            p.sendActionBar(Component.text(
                    String.format("クールタイム: %.1f秒", (until - now) / 1000.0), NamedTextColor.RED));
            return true;
        }
        return false;
    }

    private void startCooldown(Player p, Map<UUID, Long> map, int ticks) {
        map.put(p.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
    }

    // ===== イベント =====

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        // 左クリック(空振り・ブロック): 仕込み杖(変形後)の斬撃
        if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK) {
            Player attacker = e.getPlayer();
            Weapon held = getWeapon(attacker.getInventory().getItemInMainHand());
            if (isSlashWeapon(held) && !isStiff(attacker)) {
                weaponSlash(attacker, held, null);
            }
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        Weapon w = getWeapon(p.getInventory().getItemInMainHand());
        if (w == null || isStiff(p)) return;

        // 斧の形態は、右クリックで丸太の皮むきなどが起きないようにする
        if (w == Weapon.BEAST_CLEAVER || w == Weapon.BEAST_CLEAVER_DRAWN) {
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        }
        switch (w) {
            case LIGHT_SWORD, GUITAR -> startCharge(p, w);
            case REJECTION -> rejection(p);
            case PANCAKE -> {
                if (onCooldown(p, pancakeCooldown)) e.setCancelled(true); // クールタイム中は投げられない(アイテムも減らない)
            }
            case CANE_SHEATHED -> transformWeapon(p, Weapon.CANE_DRAWN);
            case CANE_DRAWN -> transformWeapon(p, Weapon.CANE_SHEATHED);
            case BEAST_CLEAVER -> transformWeapon(p, Weapon.BEAST_CLEAVER_DRAWN);
            case BEAST_CLEAVER_DRAWN -> transformWeapon(p, Weapon.BEAST_CLEAVER);
            case SAW_CLEAVER -> transformWeapon(p, Weapon.SAW_CLEAVER_DRAWN);
            case SAW_CLEAVER_DRAWN -> transformWeapon(p, Weapon.SAW_CLEAVER);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;

        // 斬撃・爆風など、プラグイン自身が与えたダメージには武器の追加効果を連鎖させない
        if (inSlash.get()) return;

        // 硬直中は攻撃不可
        if (isStiff(p)) {
            e.setCancelled(true);
            return;
        }
        // ノコギリ鉈(両形態): 攻撃が当たったら、相手に毒II(10秒)
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && e.getEntity() instanceof LivingEntity victim) {
            Weapon sawCheck = getWeapon(p.getInventory().getItemInMainHand());
            if (sawCheck == Weapon.SAW_CLEAVER || sawCheck == Weapon.SAW_CLEAVER_DRAWN) {
                victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, SAW_POISON_TICKS, SAW_POISON_AMPLIFIER));
            }
        }
        // 仕込み杖(変形後)・獣肉断ち: 通常攻撃が当たったら斬撃(当てた相手は二重にダメージを受けない)
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            Weapon held = getWeapon(p.getInventory().getItemInMainHand());
            if (isSlashWeapon(held)) weaponSlash(p, held, e.getEntity());
        }
        // パイルハンマー: 強攻撃(チャージがほぼ満タン)のときだけ、ダメージ60・防具ダメージ・硬直が出る。
        // 弱攻撃はダメージ1だけで、追加効果なし。
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && getWeapon(p.getInventory().getItemInMainHand()) == Weapon.PILE_HAMMER) {
            boolean strong = e.getDamage() >= Weapon.PILE_HAMMER.damage * PILE_STRONG_RATIO;
            if (strong) {
                stiffen(p, PILE_STIFF_TICKS);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.5f);
                if (e.getEntity() instanceof LivingEntity target) {
                    damageArmor(target, PILE_ARMOR_DAMAGE);
                }
            } else {
                e.setDamage(PILE_WEAK_DAMAGE);
            }
        }
    }

    /** 防具(4部位)の耐久値を減らす。耐久が尽きた防具は壊れる。 */
    private void damageArmor(LivingEntity target, int amount) {
        EntityEquipment eq = target.getEquipment();
        if (eq == null) return;
        ItemStack[] armor = eq.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            ItemStack piece = armor[i];
            if (piece == null || piece.getType().isAir()) continue;
            int max = piece.getType().getMaxDurability();
            if (max <= 0 || !(piece.getItemMeta() instanceof Damageable dm) || dm.isUnbreakable()) continue;

            int newDamage = dm.getDamage() + amount;
            if (newDamage >= max) {
                armor[i] = null;
                target.getWorld().playSound(target.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            } else {
                dm.setDamage(newDamage);
                piece.setItemMeta(dm);
            }
        }
        eq.setArmorContents(armor);
    }

    // 硬直・停止中は投げられない
    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Player p && isStiff(p)) {
            e.setCancelled(true);
            return;
        }
        if (e.getEntity() instanceof Snowball ball) {
            Weapon w = getWeapon(ball.getItem());
            if (w == Weapon.TSUKIBITO) {
                startMoonBomb(ball);
            } else if (w == Weapon.PANCAKE && ball.getShooter() instanceof Player p) {
                if (onCooldown(p, pancakeCooldown)) { // メイン手以外から投げた場合の保険
                    e.setCancelled(true);
                    return;
                }
                startCooldown(p, pancakeCooldown, PANCAKE_COOLDOWN_TICKS);
            }
        }
    }

    // 投擲武器(雪玉ベース)の着弾
    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Snowball ball)) return;
        Weapon w = getWeapon(ball.getItem());
        if (w == Weapon.FUSHI_BOMB) fushiBlast(ball);
        else if (w == Weapon.TIMED_BOMB) timedBomb(e, ball);
        else if (w == Weapon.TSUKIBITO) moonExplode(ball.getLocation(), ball.getShooter(), MOON_CENTER_DAMAGE);
        else if (w == Weapon.PANCAKE) pancakeBurst(ball);
    }

    /** パンケーキ: 着弾点の半径3ブロックに、吐き気・盲目・爆音(本人にだけ聞こえる)を80tick与える */
    private void pancakeBurst(Snowball ball) {
        Location center = ball.getLocation();
        World world = center.getWorld();
        Object shooter = ball.getShooter();

        world.spawnParticle(Particle.SNEEZE, center, 40, PANCAKE_RADIUS / 2, 0.5, PANCAKE_RADIUS / 2, 0.02);
        world.playSound(center, Sound.ENTITY_SLIME_SQUISH, 1.5f, 0.7f);

        for (LivingEntity le : nearbyLiving(center, PANCAKE_RADIUS)) {
            if (le instanceof ArmorStand) continue;
            if (!PANCAKE_HITS_THROWER && le.equals(shooter)) continue;

            onEntity(le, () -> {
                le.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, PANCAKE_EFFECT_TICKS, 0));
                le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, PANCAKE_EFFECT_TICKS, 0));
                // 爆音は Player#playSound で、本人にだけ聞こえる
                if (le instanceof Player victim) playExplosionLoop(victim);
            });
        }
    }

    /** 本人にだけ、爆音を PANCAKE_EFFECT_TICKS の間くり返し鳴らす */
    private void playExplosionLoop(Player victim) {
        AtomicInteger elapsed = new AtomicInteger(0);
        victim.playSound(victim.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.9f + (float) Math.random() * 0.2f);
        elapsed.addAndGet(PANCAKE_SOUND_INTERVAL_TICKS);
        victim.getScheduler().runAtFixedRate(this, task -> {
            if (!victim.isOnline() || elapsed.get() >= PANCAKE_EFFECT_TICKS) {
                task.cancel();
                return;
            }
            victim.playSound(victim.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.9f + (float) Math.random() * 0.2f);
            elapsed.addAndGet(PANCAKE_SOUND_INTERVAL_TICKS);
        }, null, PANCAKE_SOUND_INTERVAL_TICKS, PANCAKE_SOUND_INTERVAL_TICKS);
    }

    /** 月人: 重力なし・一定速度で、近くの敵を追尾しながら進む。一定時間で自己破壊(爆発)する */
    private void startMoonBomb(Snowball ball) {
        Vector initial = ball.getVelocity().clone();
        if (initial.lengthSquared() < 1.0E-6) {
            initial = ball.getShooter() instanceof LivingEntity s ? s.getEyeLocation().getDirection() : new Vector(0, -1, 0);
        }
        final Vector heading = initial.normalize(); // 現在の進行方向(毎tick更新)
        ball.setGravity(false);
        ball.setVelocity(heading.clone().multiply(MOON_SPEED));

        AtomicInteger tick = new AtomicInteger(0);
        LivingEntity[] target = {null};
        // 弾そのもののスケジューラで動かす(弾が消えれば自動で止まる)
        ball.getScheduler().runAtFixedRate(this, task -> {
            if (!ball.isValid()) { // 着弾して消えた
                task.cancel();
                return;
            }
            int t = tick.incrementAndGet();
            Location loc = ball.getLocation();

            if (t >= MOON_LIFETIME_TICKS) { // 自己破壊: 爆発する
                Object shooter = ball.getShooter();
                ball.remove();
                task.cancel();
                moonExplode(loc, shooter, MOON_TIMEOUT_DAMAGE);
                return;
            }

            try {
                // 追尾対象: 無効になったら、5tickごとに最も近い敵を探し直す
                LivingEntity cur = target[0];
                if (cur != null && (!cur.isValid() || cur.getWorld() != loc.getWorld()
                        || cur.getBoundingBox().getCenter().distance(loc.toVector()) > MOON_HOMING_RANGE * 1.5)) {
                    cur = null;
                }
                if (cur == null && t % 5 == 0) {
                    cur = findMoonTarget(ball, loc);
                }
                target[0] = cur;
                if (cur != null) {
                    Vector want = cur.getBoundingBox().getCenter().subtract(loc.toVector());
                    if (want.lengthSquared() > 1.0E-6) {
                        heading.multiply(1 - MOON_TURN_RATE).add(want.normalize().multiply(MOON_TURN_RATE)).normalize();
                    }
                }
            } catch (RuntimeException ex) {
                target[0] = null; // Folia: 別リージョンにいる相手には触れない。まっすぐ進む
            }

            ball.setVelocity(heading.clone().multiply(MOON_SPEED)); // 空気抵抗で遅くならないよう毎tick速度を戻す
        }, null, 1L, 1L);
    }

    /** 追尾する相手を探す: 投げた本人・アーマースタンド・スペクテイターを除いた最も近い敵(壁越しは除く) */
    private LivingEntity findMoonTarget(Snowball ball, Location loc) {
        Object shooter = ball.getShooter();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity le : nearbyLiving(loc, MOON_HOMING_RANGE)) {
            if (le.equals(shooter) || le instanceof ArmorStand) continue;
            if (le instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR) continue;
            Vector to = le.getBoundingBox().getCenter().subtract(loc.toVector());
            double dist = to.length();
            if (dist > MOON_HOMING_RANGE || dist >= bestDist || dist < 1.0E-4) continue;
            if (loc.getWorld().rayTraceBlocks(loc, to.clone().normalize(), dist, FluidCollisionMode.NEVER, true) != null) continue;
            best = le;
            bestDist = dist;
        }
        return best;
    }

    /** 月人: 半径7ブロックの爆発。中心が centerDamage で、距離に応じて直線的に小さくなる */
    private void moonExplode(Location center, Object shooter, double centerDamage) {
        World world = center.getWorld();
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2.5f, 0.7f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 3, MOON_RADIUS / 4, MOON_RADIUS / 8, MOON_RADIUS / 4, 0);

        for (LivingEntity le : nearbyLiving(center, MOON_RADIUS)) {
            if (le instanceof ArmorStand) continue;
            if (!MOON_HITS_THROWER && le.equals(shooter)) continue;
            double dist = le.getBoundingBox().getCenter().distance(center.toVector());
            double ratio = 1.0 - dist / MOON_RADIUS;
            if (ratio <= 0) continue;
            damageBy(le, centerDamage * ratio, shooter);
        }
    }

    /** FUSHI爆弾: 着弾点で爆風 */
    private void fushiBlast(Snowball ball) {
        Location center = ball.getLocation();
        World world = center.getWorld();
        Object shooter = ball.getShooter();

        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.3f);
        world.spawnParticle(Particle.EXPLOSION, center, 1, 0, 0, 0, 0);

        for (LivingEntity le : nearbyLiving(center, FUSHI_RADIUS)) {
            if (le instanceof ArmorStand) continue;
            if (!FUSHI_HITS_THROWER && le.equals(shooter)) continue;
            if (le.getBoundingBox().getCenter().distance(center.toVector()) > FUSHI_RADIUS
                    && le.getLocation().distance(center) > FUSHI_RADIUS) continue;

            damageBy(le, FUSHI_DAMAGE, shooter);
            if (Math.random() < FUSHI_STUN_CHANCE) onEntity(le, () -> freeze(le, FUSHI_STUN_TICKS));
        }
    }

    /**
     * プラグイン自身のダメージ処理(武器の追加効果を連鎖させない)。
     * 相手を扱うスレッドで実行する(Folia 対応。Paper ではそのまま直ちに実行される)。
     */
    private void damageBy(LivingEntity target, double amount, Object source) {
        onEntity(target, () -> {
            inSlash.set(true);
            try {
                if (source instanceof LivingEntity src) target.damage(amount, src);
                else target.damage(amount);
            } finally {
                inSlash.set(false);
            }
        });
    }

    /** 時限爆弾(もと光るダムダム弾): 着弾時に直撃ダメージ → 0.5秒後に爆発 */
    private void timedBomb(ProjectileHitEvent e, Snowball ball) {
        Location center = ball.getLocation().clone();
        World world = center.getWorld();
        Object shooter = ball.getShooter();

        if (e.getHitEntity() instanceof LivingEntity hit && !(hit instanceof ArmorStand)) {
            damageBy(hit, TIMED_IMPACT_DAMAGE, shooter);
        }

        world.playSound(center, Sound.ENTITY_CREEPER_PRIMED, 1.5f, 1f);

        AtomicInteger tick = new AtomicInteger(0);
        // 着弾した場所を担当するリージョンで動かす
        Bukkit.getRegionScheduler().runAtFixedRate(this, center, task -> {
            int t = tick.incrementAndGet();
            // 爆発までの予兆(煙)
            if (t % 2 == 0) {
                world.spawnParticle(Particle.SMOKE, center, 6, 0.3, 0.3, 0.3, 0.01);
            }
            if (t < TIMED_DELAY_TICKS) return;
            task.cancel();

            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.8f);
            world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1, 0, 0, 0, 0);
            for (LivingEntity le : nearbyLiving(center, TIMED_RADIUS)) {
                if (le instanceof ArmorStand) continue;
                if (!TIMED_HITS_THROWER && le.equals(shooter)) continue;
                damageBy(le, TIMED_DAMAGE, shooter);
            }
        }, 1L, 1L);
    }

    /** 停止 + 盲目。プレイヤーは攻撃やスキルも封じ、さらに発光する。モブは AI も止める。(相手を扱うスレッドで呼ぶこと) */
    private void freeze(LivingEntity le, int ticks) {
        le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 0, false, true, true));
        if (le instanceof Player p) {
            stiffen(p, ticks);
            p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, ticks, 0, false, false, true)); // 発光(居場所が分かる)
        } else {
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 6, false, false, false));
            if (le instanceof Mob mob) {
                mob.setAware(false);
                mob.getScheduler().runDelayed(this, t -> mob.setAware(true), null, Math.max(1, ticks));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        charging.remove(e.getPlayer().getUniqueId());
        stiffUntil.remove(e.getPlayer().getUniqueId());
        removeJumpLock(e.getPlayer());
        laserCooldown.remove(e.getPlayer().getUniqueId());
        rejectCooldown.remove(e.getPlayer().getUniqueId());
        slashCooldown.remove(e.getPlayer().getUniqueId());
        pancakeCooldown.remove(e.getPlayer().getUniqueId());
        guitarCooldown.remove(e.getPlayer().getUniqueId());
        hornHolders.remove(e.getPlayer().getUniqueId());
    }

    // ===== キーホルダー(光の剣を持っている間、オフハンドを埋める) =====

    private boolean isKeychain(ItemStack item) {
        return item != null && getWeapon(item) == Weapon.KEYCHAIN;
    }

    /** キーホルダーを作る。stored(元のオフハンドのアイテム)は、キーホルダー自身に保存しておく */
    private ItemStack createKeychain(ItemStack stored) {
        ItemStack key = createItem(Weapon.KEYCHAIN);
        ItemMeta meta = key.getItemMeta();
        meta.setMaxStackSize(1);
        if (stored != null && !stored.getType().isAir()) {
            meta.getPersistentDataContainer().set(keychainDataKey, PersistentDataType.BYTE_ARRAY, stored.serializeAsBytes());
        }
        key.setItemMeta(meta);
        return key;
    }

    /** キーホルダーが預かっている元のアイテム(なければ null) */
    private ItemStack storedOf(ItemStack key) {
        if (key == null || !key.hasItemMeta()) return null;
        byte[] data = key.getItemMeta().getPersistentDataContainer().get(keychainDataKey, PersistentDataType.BYTE_ARRAY);
        return data == null ? null : ItemStack.deserializeBytes(data);
    }

    /** 光の剣を持っていればオフハンドをキーホルダーで埋め、持っていなければ元のアイテムに戻す */
    private void tickKeychain(Player p) {
        PlayerInventory inv = p.getInventory();
        boolean holdingLight = getWeapon(inv.getItemInMainHand()) == Weapon.LIGHT_SWORD;
        ItemStack off = inv.getItemInOffHand();
        boolean offIsKey = isKeychain(off);
        if (holdingLight && !offIsKey) {
            inv.setItemInOffHand(createKeychain(off)); // 元のオフハンドのアイテムは、キーホルダーが預かる
        } else if (!holdingLight && offIsKey) {
            restoreKeychain(p);
        }
    }

    /** オフハンドのキーホルダーを外し、預かっていたアイテムを返す */
    private void restoreKeychain(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack off = inv.getItemInOffHand();
        if (!isKeychain(off)) return;
        ItemStack stored = storedOf(off);
        inv.setItemInOffHand(stored != null ? stored : new ItemStack(Material.AIR));
    }

    /** オフハンド以外に紛れ込んだキーホルダーを回収し、預かっていたアイテムを返す(ログイン時) */
    private void scrubStrayKeychains(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (!isKeychain(contents[i])) continue;
            ItemStack stored = storedOf(contents[i]);
            inv.setItem(i, null);
            if (stored != null) {
                inv.addItem(stored).values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
            }
        }
    }

    // キーホルダーは動かせない・捨てられない
    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (isKeychain(e.getCurrentItem()) || isKeychain(e.getCursor())) {
            e.setCancelled(true);
            return;
        }
        // Fキー(オフハンド入れ替え)で、キーホルダーが動かされるのを防ぐ
        if (e.getClick() == ClickType.SWAP_OFFHAND && e.getWhoClicked() instanceof Player pl
                && isKeychain(pl.getInventory().getItemInOffHand())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        if (isKeychain(e.getMainHandItem()) || isKeychain(e.getOffHandItem())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent e) {
        if (isKeychain(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    // 死亡時は、キーホルダーを落とさず、預かっていたアイテムを落とす
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (e.getKeepInventory()) return;
        List<ItemStack> returned = new ArrayList<>();
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack drop = it.next();
            if (!isKeychain(drop)) continue;
            it.remove();
            ItemStack stored = storedOf(drop);
            if (stored != null) returned.add(stored);
        }
        e.getDrops().addAll(returned);
    }

    // ===== 狩猟笛(持っている間、攻撃力増加I と 移動速度増加I) =====

    private void tickHorn(Player p) {
        boolean has = getWeapon(p.getInventory().getItemInMainHand()) == Weapon.HUNTING_HORN;
        if (!has && !HORN_NEEDS_MAIN_HAND) {
            for (ItemStack it : p.getInventory().getContents()) {
                if (getWeapon(it) == Weapon.HUNTING_HORN) {
                    has = true;
                    break;
                }
            }
        }
        if (has) {
            // 1秒ごとに 15秒へ更新する。持っている間は切れない
            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, HORN_EFFECT_TICKS, 0, false, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, HORN_EFFECT_TICKS, 0, false, false, true));
            hornHolders.add(p.getUniqueId());
        } else if (hornHolders.remove(p.getUniqueId()) && HORN_CLEAR_ON_UNEQUIP) {
            p.removePotionEffect(PotionEffectType.STRENGTH);
            p.removePotionEffect(PotionEffectType.SPEED);
        }
    }

    // ===== 光の剣・ギター(ウォーデンの衝撃波) =====

    /** 右クリックでチャージを始め、満タンになったら衝撃波を撃つ(光の剣 / ギター) */
    private void startCharge(Player p, Weapon w) {
        boolean guitar = (w == Weapon.GUITAR);
        int chargeTicks = guitar ? GUITAR_CHARGE_TICKS : LASER_CHARGE_TICKS;
        UUID id = p.getUniqueId();
        if (charging.contains(id)) return;                                     // チャージ中
        if (onCooldown(p, guitar ? guitarCooldown : laserCooldown)) return;    // クールタイム中
        charging.add(id);

        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.5f, guitar ? 1.4f : 1f);
        if (guitar) {
            // ギター: チャージ中は鈍足III
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, chargeTicks + 2, GUITAR_SLOWNESS_AMPLIFIER));
        } else {
            applyTurtleMaster(p, chargeTicks + 2); // 光の剣: チャージ中はタートルマスター
        }

        // チャージの進み具合は、文字ではなくボスバーで表示する(文字の記号が環境によって「豆腐」になるため)
        BossBar bar = BossBar.bossBar(Component.text(w.displayName + " チャージ中", NamedTextColor.AQUA),
                0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        p.showBossBar(bar);

        AtomicInteger tick = new AtomicInteger(0);
        // プレイヤーのスケジューラで動かす(Folia 対応)。プレイヤーが消えたら、最後の処理(retired)で後片付けする
        p.getScheduler().runAtFixedRate(this, task -> {
            // 中断条件: 退出・死亡・持ち替え
            if (!p.isOnline() || p.isDead() || getWeapon(p.getInventory().getItemInMainHand()) != w) {
                charging.remove(id);
                p.hideBossBar(bar);
                removeChargeEffects(p, guitar); // 中断したら効果も外す
                task.cancel();
                return;
            }
            int t = tick.incrementAndGet();
            if (!guitar) sendRailgunSequence(p, t); // 光の剣: 発射シーケンスをチャットに出す
            p.getWorld().spawnParticle(Particle.END_ROD,
                    p.getEyeLocation().add(p.getLocation().getDirection().multiply(0.8)),
                    2, 0.15, 0.15, 0.15, 0.02);
            bar.progress(Math.min(1f, t / (float) chargeTicks));

            if (t >= chargeTicks) {
                charging.remove(id);
                p.hideBossBar(bar);
                task.cancel();
                fireShockwave(p, w);
            }
        }, () -> {
            charging.remove(id);
            p.hideBossBar(bar);
        }, 1L, 1L);
    }

    /** 光の剣のチャージ中、1秒ごとに発射シーケンスのメッセージをチャットへ送る(tick=1,21,41 が各秒の始まり) */
    private void sendRailgunSequence(Player p, int tick) {
        Component msg;
        if (tick == 1) {
            msg = Component.text("光の剣コピー起動、発射シーケンスへ移行します", NamedTextColor.AQUA);
        } else if (tick == 21) {
            Component warning = Component.text("[RAILGUN WARNING]", NamedTextColor.RED, TextDecoration.BOLD);
            for (String line : RAILGUN_WARNING_LINES) {
                warning = warning.append(Component.newline()).append(Component.text(line, NamedTextColor.RED));
            }
            msg = warning;
        } else if (tick == 41) {
            msg = Component.text("光よ！", NamedTextColor.YELLOW);
        } else {
            return;
        }
        Set<Player> receivers = new HashSet<>();
        receivers.add(p);
        if (LASER_MESSAGE_RADIUS < 0) {
            receivers.addAll(Bukkit.getOnlinePlayers());
        } else if (LASER_MESSAGE_RADIUS > 0) {
            try {
                double r = LASER_MESSAGE_RADIUS;
                for (Entity en : p.getNearbyEntities(r, r, r)) {
                    if (en instanceof Player pl && pl.getLocation().distanceSquared(p.getLocation()) <= r * r) {
                        receivers.add(pl);
                    }
                }
            } catch (RuntimeException ignored) {
                // Folia: 別リージョンのプレイヤーは見えない範囲。見える範囲だけに送る
            }
        }
        for (Player pl : receivers) pl.sendMessage(msg);
    }

    private void removeChargeEffects(Player p, boolean guitar) {
        if (guitar) p.removePotionEffect(PotionEffectType.SLOWNESS);
        else removeTurtleMaster(p);
    }

    /** タートルマスター(ポーション): 移動速度低下IV + 耐性III */
    private void applyTurtleMaster(Player p, int ticks) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 3));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks, 2));
    }

    private void removeTurtleMaster(Player p) {
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        p.removePotionEffect(PotionEffectType.RESISTANCE);
    }

    private void fireShockwave(Player p, Weapon w) {
        boolean guitar = (w == Weapon.GUITAR);
        double range = guitar ? GUITAR_RANGE : LASER_RANGE;
        double damage = guitar ? GUITAR_DAMAGE : LASER_DAMAGE;
        boolean pierce = guitar ? GUITAR_PIERCE_WALLS : LASER_PIERCE_WALLS;

        World world = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        removeChargeEffects(p, guitar); // 発射したらチャージ中の効果を外す

        // 壁を貫通する設定なら射程いっぱい。貫通しない設定なら壁で止める
        double length = range;
        if (!pierce) {
            RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
            if (blockHit != null) length = eye.toVector().distance(blockHit.getHitPosition());
        }

        world.playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 2f, guitar ? 1.3f : 1f);

        // 衝撃波の通り道の点(1ブロックごと)を集める
        List<Location> points = new ArrayList<>();
        for (double d = 1; d <= length; d += 1.0) {
            Location point = eye.clone().add(dir.clone().multiply(d));
            if (((int) d) % 2 == 0) {
                world.spawnParticle(Particle.SONIC_BOOM, point, 1, 0, 0, 0, 0);
            }
            points.add(point);
        }
        // チャンクごとにまとめて、そのチャンクを担当するスレッドで当たり判定とダメージを行う
        // (Folia では、遠くの相手は別リージョンのため。Paper ではすべてその場で実行される)
        Map<Long, List<Location>> byChunk = new LinkedHashMap<>();
        for (Location pt : points) {
            long key = (((long) (pt.getBlockX() >> 4)) << 32) | ((pt.getBlockZ() >> 4) & 0xFFFFFFFFL);
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(pt);
        }
        Set<UUID> hit = ConcurrentHashMap.newKeySet(); // 1回の発射で、1体につき1回だけ(貫通)
        for (List<Location> group : byChunk.values()) {
            atLocation(group.get(0), () -> {
                for (Location pt : group) {
                    for (LivingEntity le : nearbyLiving(pt, 1.0)) {
                        if (le.equals(p) || le instanceof ArmorStand) continue;
                        if (hit.add(le.getUniqueId())) damageBy(le, damage, p);
                    }
                }
            });
        }

        if (guitar) {
            startCooldown(p, guitarCooldown, GUITAR_COOLDOWN_TICKS); // 発射した時点から6秒(硬直なし)
        } else {
            stiffen(p, LASER_STIFF_TICKS); // ダメージ処理の後に硬直を付与
            startCooldown(p, laserCooldown, LASER_COOLDOWN_TICKS); // 発射した時点から20秒
        }
    }

    // ===== 仕込み杖 =====

    /** メインハンドの仕込み杖を、もう一方の形態に持ち替える */
    private void transformWeapon(Player p, Weapon to) {
        ItemStack old = p.getInventory().getItemInMainHand();
        ItemStack next = createItem(to);
        next.addUnsafeEnchantments(old.getEnchantments()); // エンチャントを引き継ぐ
        p.getInventory().setItemInMainHand(next);
        boolean drawing = to.id.endsWith("_drawn");
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1f, drawing ? 1.6f : 1.0f);
        p.sendActionBar(Component.text(to.displayName, NamedTextColor.GRAY));
    }

    /** 斬撃を持つ武器か */
    private boolean isSlashWeapon(Weapon w) {
        return w == Weapon.CANE_DRAWN || w == Weapon.BEAST_CLEAVER || w == Weapon.BEAST_CLEAVER_DRAWN;
    }

    /** 武器ごとの斬撃(範囲・角度・ダメージ・連発間隔)で、前方の扇形を斬る */
    private void weaponSlash(Player p, Weapon w, Entity exclude) {
        switch (w) {
            case CANE_DRAWN -> slash(p, exclude, CANE_SLASH_RANGE, CANE_SLASH_ANGLE,
                    CANE_SLASH_DAMAGE, CANE_SLASH_INTERVAL_TICKS);
            case BEAST_CLEAVER -> slash(p, exclude, BEAST_SLASH_RANGE, BEAST_SLASH_ANGLE,
                    BEAST_SLASH_DAMAGE, BEAST_SLASH_INTERVAL_TICKS);
            case BEAST_CLEAVER_DRAWN -> {
                // 強攻撃(チャージ満タン)と同じタイミングでだけ出る
                if (p.getAttackCooldown() >= BEAST_DRAWN_STRONG_THRESHOLD) {
                    slash(p, exclude, BEAST_DRAWN_SLASH_RANGE, BEAST_DRAWN_SLASH_ANGLE,
                            BEAST_DRAWN_SLASH_DAMAGE, BEAST_DRAWN_SLASH_INTERVAL_TICKS);
                }
            }
            default -> { }
        }
    }

    /**
     * 前方の扇形(半径 range ブロック・全体の角度 angle 度)にいる敵へ斬撃。
     * exclude は通常攻撃で既に当たった相手(二重にダメージを与えない)。
     */
    private void slash(Player p, Entity exclude, double range, double angle, double damage, int intervalTicks) {
        long now = System.currentTimeMillis();
        Long until = slashCooldown.get(p.getUniqueId());
        if (until != null && until > now) return;
        slashCooldown.put(p.getUniqueId(), now + intervalTicks * 50L);

        World world = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double halfAngle = Math.toRadians(angle / 2);

        // 見た目: 前方に斬撃のパーティクルを扇状に並べる
        world.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.8f);
        for (int ring = 1; ring <= 3; ring++) {
            double d = range * ring / 3.0;
            for (int i = -1; i <= 1; i++) {
                Vector v = dir.clone().rotateAroundY(Math.toRadians(i * angle / 2)).multiply(d);
                world.spawnParticle(Particle.SWEEP_ATTACK, eye.clone().add(v), 1, 0, 0, 0, 0);
            }
        }

        // 判定: 距離 range 以内かつ、視線方向との角度が angle の半分以内
        List<LivingEntity> targets = new ArrayList<>();
        for (LivingEntity le : nearbyLiving(eye, range)) {
            if (le.equals(p) || le.equals(exclude) || le instanceof ArmorStand) continue;
            Vector to = le.getBoundingBox().getCenter().subtract(eye.toVector());
            double dist = to.length();
            if (dist > range || dist < 1.0E-4) continue;
            if (dir.angle(to) > halfAngle) continue;
            // 壁の向こうには届かない
            boolean blocked;
            try {
                blocked = world.rayTraceBlocks(eye, to.clone().normalize(), dist, FluidCollisionMode.NEVER, true) != null;
            } catch (RuntimeException ex) {
                blocked = false; // Folia: 別リージョンのブロックは読めない。遮られていないものとして扱う
            }
            if (blocked) continue;
            targets.add(le);
        }
        for (LivingEntity le : targets) damageBy(le, damage, p);
    }

    // ===== 拒絶 =====

    private void rejection(Player p) {
        if (onCooldown(p, rejectCooldown)) return;
        startCooldown(p, rejectCooldown, REJECT_COOLDOWN_TICKS);

        Location center = p.getLocation();
        World world = p.getWorld();
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.4f);
        world.spawnParticle(Particle.EXPLOSION, center.clone().add(0, 1, 0), 3, 1.5, 0.5, 1.5, 0);

        for (Entity en : p.getNearbyEntities(REJECT_RADIUS, REJECT_RADIUS, REJECT_RADIUS)) {
            if (!(en instanceof LivingEntity) || en instanceof ArmorStand) continue;
            if (en.getLocation().distanceSquared(center) > REJECT_RADIUS * REJECT_RADIUS) continue;

            Vector v = en.getLocation().toVector().subtract(center.toVector());
            v.setY(0);
            if (v.lengthSquared() < 1.0E-4) {
                v = new Vector(Math.random() - 0.5, 0, Math.random() - 0.5);
            }
            v.normalize().multiply(2.0).setY(0.6);
            Vector push = v;
            onEntity(en, () -> en.setVelocity(push)); // 相手を扱うスレッドで動かす(Folia 対応)
        }
    }

    // ===== コマンド: /weapon <id> [player] =====

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("使い方: /weapon <武器ID> [プレイヤー] [個数]");
            return true;
        }
        Weapon w = Weapon.byId(args[0]);
        if (w == null) {
            sender.sendMessage("そのIDの武器はありません。");
            return true;
        }
        if (w == Weapon.KEYCHAIN) {
            sender.sendMessage("キーホルダーは、光の剣を持つと自動で付与されるアイテムです。");
            return true;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("プレイヤーが見つかりません。");
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage("コンソールからはプレイヤー名を指定してください。");
            return true;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("個数は数字で指定してください。");
                return true;
            }
        }
        ItemStack item = createItem(w);
        item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
        target.getInventory().addItem(item);
        sender.sendMessage(w.displayName + " を " + target.getName() + " に " + item.getAmount() + "個渡しました。");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (Weapon w : Weapon.values()) {
                if (w != Weapon.KEYCHAIN && w.id.startsWith(args[0].toLowerCase())) out.add(w.id);
            }
        } else if (args.length == 2) {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(pl.getName());
            }
        }
        return out;
    }
}
