package trigger.tukuyomiWeapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * カスタム武器プラグイン (Paper 26.2 / Java 25)
 */
public class TukuyomiWeapons extends JavaPlugin implements Listener, TabExecutor {

    // ===== 共通状態 =====
    private NamespacedKey weaponKey;
    NamespacedKey keychainDataKey; // キーホルダーが預かっている、元のオフハンドのアイテム
    // このプラグインが付けた効果の記録(種類と、効果が切れる時刻ms)。スペクテイターになったとき、まとめて消すために使う
    private final Map<UUID, Map<PotionEffectType, Long>> pluginEffects = new ConcurrentHashMap<>();
    private NamespacedKey stiffRemainKey;   // ログアウト時に、残りの硬直時間をプレイヤーに保存する
    NamespacedKey offhandBackupKey; // キーホルダーが預かっているアイテムの控え(プレイヤー側にも保存)
    private NamespacedKey stiffJumpKey; // 硬直中のジャンプ封じ用の属性修飾子
    private final Map<UUID, Long> stiffUntil = new ConcurrentHashMap<>(); // 硬直の終了時刻(ms)
    final Set<UUID> charging = ConcurrentHashMap.newKeySet();
    final Map<UUID, Long> laserCooldown = new ConcurrentHashMap<>();  // 光の剣のクールタイム終了時刻(ms)
    final Map<UUID, Long> rejectCooldown = new ConcurrentHashMap<>(); // 拒絶のクールタイム終了時刻(ms)
    final Map<UUID, Long> slashCooldown = new ConcurrentHashMap<>();  // 斬撃の連発防止(ms)
    final Map<UUID, Long> guitarCooldown = new ConcurrentHashMap<>();  // ギターのクールタイム終了時刻(ms)
    final Set<UUID> hornHolders = ConcurrentHashMap.newKeySet();
    final Map<UUID, Long> lastMeleeAttack = new ConcurrentHashMap<>(); // 直近に殴った時刻(ms)。左クリック斬撃との二重ヒット防止用           // 狩猟笛の効果を与えているプレイヤー
    final Map<UUID, Long> pancakeCooldown = new ConcurrentHashMap<>(); // パンケーキのクールタイム終了時刻(ms)
    // 斬撃・爆風などのダメージ処理中(武器の追加効果が連鎖するのを防ぐ)。ダメージ処理はそのスレッド内で完結するので ThreadLocal にする(Folia 対応)
    private final ThreadLocal<Boolean> inSlash = ThreadLocal.withInitial(() -> false);
    // 武器別ロジックは各 WeaponHandler クラスへ委譲。共通処理・スケジューラ・アイテム生成は本体に残す。
    private final Map<Weapon, WeaponHandler> weaponHandlers = new EnumMap<>(Weapon.class);
    UltimateManager ult;                // ウルト(キルのチャージ・経験値バーのゲージ・効果中の状態)
    private NamespacedKey karakasaArrowKey; // 唐傘弾の矢の印
    final Map<UUID, Long> laserUltCooldown = new ConcurrentHashMap<>(); // 光の剣(ウルト)のクールタイム終了時刻(ms)

    @Override
    public void onEnable() {
        weaponKey = new NamespacedKey(this, "weapon_id");
        stiffJumpKey = new NamespacedKey(this, "stiff_jump_lock");
        stiffRemainKey = new NamespacedKey(this, "stiff_remaining_ms");
        offhandBackupKey = new NamespacedKey(this, "offhand_backup");
        keychainDataKey = new NamespacedKey(this, "keychain_stored_offhand");
        ult = new UltimateManager(this);
        karakasaArrowKey = new NamespacedKey(this, "karakasa_arrow");
        registerWeaponHandlers();
        getServer().getPluginManager().registerEvents(this, this);

        // すでにオンラインのプレイヤーにも、見回りを始める(以降は参加時に始める)
        for (Player pl : Bukkit.getOnlinePlayers()) startPlayerTask(pl);
        var cmd = getCommand("weapon");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        var ultCmd = getCommand("ultcharge");
        if (ultCmd != null) ultCmd.setExecutor(new UltChargeCommand(this));
    }

    private void registerWeaponHandlers() {
        weaponHandlers.put(Weapon.LIGHT_SWORD, new LightSwordHandler());
        weaponHandlers.put(Weapon.GUITAR, new GuitarHandler());
        weaponHandlers.put(Weapon.REJECTION, new RejectionHandler());
        weaponHandlers.put(Weapon.PANCAKE, new PancakeHandler());
        weaponHandlers.put(Weapon.CANE_SHEATHED, new CaneSheathedHandler());
        weaponHandlers.put(Weapon.CANE_DRAWN, new CaneDrawnHandler());
        weaponHandlers.put(Weapon.BEAST_CLEAVER, new BeastCleaverHandler());
        weaponHandlers.put(Weapon.BEAST_CLEAVER_DRAWN, new BeastCleaverDrawnHandler());
        weaponHandlers.put(Weapon.SAW_CLEAVER, new SawCleaverHandler());
        weaponHandlers.put(Weapon.SAW_CLEAVER_DRAWN, new SawCleaverDrawnHandler());
        weaponHandlers.put(Weapon.PILE_HAMMER, new PileHammerHandler());
        weaponHandlers.put(Weapon.FUSHI_BOMB, new FushiBombHandler());
        weaponHandlers.put(Weapon.TIMED_BOMB, new TimedBombHandler());
        weaponHandlers.put(Weapon.TSUKIBITO, new TsukibitoHandler());
        weaponHandlers.put(Weapon.HUNTING_HORN, new HuntingHornHandler());
        weaponHandlers.put(Weapon.GUNLANCE, new GunlanceHandler());
        weaponHandlers.put(Weapon.KEYCHAIN, new KeychainHandler());
        weaponHandlers.put(Weapon.OLD_HUNTER_BONE, new OldHunterBoneHandler());
        weaponHandlers.put(Weapon.WRATH_OF_GOD, new WrathOfGodHandler());
        weaponHandlers.put(Weapon.KARAKASA_SHELL, new KarakasaHandler());
        weaponHandlers.put(Weapon.LIGHT_SWORD_ULT, new LightSwordUltHandler());
        weaponHandlers.put(Weapon.CALL_TO_BEYOND, new CallToBeyondHandler());
        weaponHandlers.put(Weapon.MOONLIGHT_HOLY_SWORD, new MoonlightHolySwordHandler());
        weaponHandlers.put(Weapon.PILE_BUNKER, new PileBunkerHandler());
        weaponHandlers.put(Weapon.BIG_BLUNDER, new BigBlunderHandler());
        weaponHandlers.put(Weapon.ENVOY_FROM_STARS, new EnvoyFromStarsHandler());
        weaponHandlers.put(Weapon.REMINISCENCE_8000, new Reminiscence8000Handler());
        weaponHandlers.put(Weapon.DRAGON_CANNON, new DragonCannonHandler());
        weaponHandlers.put(Weapon.SONIC_QUAKE, new SonicQuakeHandler());
        weaponHandlers.put(Weapon.RESONANCE_QUAKE, new ResonanceQuakeHandler());
    }





    // ===== Paper / Folia 両対応の土台 =====
    // Folia ではメインスレッドが無く、処理はリージョン(地域)ごとのスレッドで動く。
    // BukkitRunnable / Bukkit.getScheduler() は使わず、エンティティ・リージョンのスケジューラを使う。
    // (これらのスケジューラは Paper にもあり、Paper ではメインスレッドで動くので、同じコードで両方に対応できる)

    /** このエンティティを扱うスレッドにいれば直ちに、そうでなければそのエンティティのスケジューラで実行する */
    void onEntity(Entity e, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(e)) {
            task.run();
        } else {
            e.getScheduler().run(this, t -> task.run(), null);
        }
    }

    /** この場所を扱うスレッドにいれば直ちに、そうでなければリージョンスケジューラで実行する */
    void atLocation(Location loc, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(loc)) {
            task.run();
        } else {
            Bukkit.getRegionScheduler().run(this, loc, t -> task.run());
        }
    }

    /** Folia で別リージョンに触れて処理を飛ばしたときの記録(デバッグ用。ログレベル FINE なので、通常は表示されない) */
    void threadNote(String where, RuntimeException ex) {
        getLogger().log(java.util.logging.Level.FINE, "[Folia] " + where + ": 別リージョンのため一部をスキップ (" + ex.getMessage() + ")");
    }

    /** 周囲の生き物を取る。Folia で他のリージョンに触れてしまう場合は、取れた範囲だけにする */
    Collection<LivingEntity> nearbyLiving(Location center, double radius) {
        try {
            return center.getWorld().getNearbyLivingEntities(center, radius);
        } catch (IllegalStateException ex) { // Folia: 別リージョンにまたがる範囲は読めない
            threadNote("周囲の取得", ex);
            return List.of();
        }
    }

    /** プレイヤーごとの見回り(0.1秒ごと): キーホルダーと、狩猟笛のバフ(1秒ごと) */
    /** バニラの飛び道具(矢・トライデントなど)が当たったぶんの、ウルトのチャージを増やす */
    private void countProjectileForUlt(EntityDamageByEntityEvent e, Projectile proj, Player shooter) {
        if (inSlash.get()) return; // このプラグイン自身が与えたダメージは、武器ごとの処理で数える
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        if (proj instanceof Snowball ball && getWeapon(ball.getItem()) != null) return; // このプラグインの武器の雪玉は、別に数える
        if (proj.getPersistentDataContainer().has(karakasaArrowKey, PersistentDataType.BYTE)) return; // 唐傘弾(ウルト)の矢
        double charge = e.getDamage() * UltimateManager.VANILLA_DAMAGE_CHARGE_RATIO;
        if (charge <= 0) return; // ダメージのない雪玉・卵などは増えない
        ult.addChargeFor(shooter, charge, victim);
    }

    private void startPlayerTask(Player p) {
        AtomicInteger n = new AtomicInteger(0);
        p.getScheduler().runAtFixedRate(this, task -> {
            if (!p.isOnline()) {
                task.cancel();
                return;
            }
            int t = n.incrementAndGet();
            if (p.getGameMode() == GameMode.SPECTATOR) return;
            WeaponHandler keychain = weaponHandlers.get(Weapon.KEYCHAIN);
            if (keychain != null) keychain.onPlayerTick(this, p);
            ult.onTick(p, t);
            if (t % 10 == 0) { WeaponHandler horn = weaponHandlers.get(Weapon.HUNTING_HORN); if (horn != null) horn.onTick(this, p); }
        }, null, 1L, 2L);

        // ウルトのゲージの送り直し(毎tick)。ゲージを出している人だけ、数バイトのパケットを1つ送る
        p.getScheduler().runAtFixedRate(this, task -> {
            if (!p.isOnline()) {
                task.cancel();
                return;
            }
            ult.onFastTick(p);
        }, null, 1L, 1L);
    }

    // ===== アイテム生成・判定 =====

    ItemStack createItem(Weapon w) {
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

    Weapon getWeapon(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
        return id == null ? null : Weapon.byId(id);
    }

    // ===== 他のプラグインの保護(PvP禁止区域など)の確認 =====

    /**
     * 相手に影響を与えてよいか、ダメージのイベントを0ダメージで一度流して確認する。
     * WorldGuard の PvP フラグなどが効いて、キャンセルされたら false。
     * (ダメージを与えない効果 = 吹き飛ばし・状態異常にも、保護区域を守らせるため)
     */
    boolean canAffect(Object attacker, Entity target) {
        if (!(attacker instanceof Entity a)) return true;
        inSlash.set(true); // このプラグイン自身の処理が、確認用のイベントに反応しないように
        try {
            EntityDamageByEntityEvent probe = createDamageProbe(a, target);
            Bukkit.getPluginManager().callEvent(probe);
            return !probe.isCancelled();
        } finally {
            inSlash.set(false);
        }
    }


    /** API差異を吸収して保護プラグイン確認用イベントを生成する。 */
    private EntityDamageByEntityEvent createDamageProbe(Entity attacker, Entity target) {
        try {
            DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                    .withCausingEntity(attacker).withDirectEntity(attacker).build();
            try {
                var ctor = EntityDamageByEntityEvent.class.getConstructor(Entity.class, Entity.class, DamageSource.class, double.class);
                return ctor.newInstance(attacker, target, source, 0.0);
            } catch (NoSuchMethodException ignored) {
                var ctor = EntityDamageByEntityEvent.class.getConstructor(Entity.class, Entity.class, EntityDamageEvent.DamageCause.class, double.class);
                return ctor.newInstance(attacker, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 0.0);
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("確認用ダメージイベントを生成できません。Bukkit/Paper APIを確認してください。", ex);
        }
    }

    // ===== このプラグインが付けた効果の管理 =====

    /** 効果を付ける。プレイヤーには「このプラグインが付けた効果」として記録する */
    void giveEffect(LivingEntity le, PotionEffect effect) {
        le.addPotionEffect(effect);
        if (le instanceof Player pl) {
            long until = System.currentTimeMillis() + effect.getDuration() * 50L;
            pluginEffects.computeIfAbsent(pl.getUniqueId(), k -> new ConcurrentHashMap<>())
                    .merge(effect.getType(), until, Math::max);
        }
    }

    /**
     * 毒・ウィザーのように「ダメージを与え続ける効果」を付けるとき用。
     * かけた人をSkript(PvPのキル判定)へ伝える。後からかけた人で上書きされる。
     */
    void giveEffect(LivingEntity le, PotionEffect effect, Player source) {
        giveEffect(le, effect);
        if (source == null || !(le instanceof Player victim) || victim.equals(source)) return;
        PotionEffectType type = effect.getType();
        if (type != PotionEffectType.POISON && type != PotionEffectType.WITHER) return;
        double seconds = effect.getDuration() / 20.0;
        String cmd = "pvpdebuff " + victim.getUniqueId() + " " + source.getUniqueId() + " " + seconds;
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            // Skript側のコマンドが無い環境(PvP用Skript未導入)では何もしない
            if (Bukkit.getCommandMap().getCommand("pvpdebuff") == null) return;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        });
    }

    /** このプラグインが付けた効果のうち、まだ続いているものをすべて消す */
    private void clearPluginEffects(Player p) {
        Map<PotionEffectType, Long> recorded = pluginEffects.remove(p.getUniqueId());
        if (recorded == null) return;
        long now = System.currentTimeMillis();
        recorded.forEach((type, until) -> {
            if (until > now) p.removePotionEffect(type);
        });
    }

    /** スペクテイターになったとき: このプラグイン由来の効果・状態を、いったんすべて消去する */
    private void clearEverythingFromPlugin(Player p) {
        resetStiffness(p);                     // 硬直(記録・移動速度低下・ジャンプ封じ)
        clearPluginEffects(p);                 // 付けた効果すべて(狩猟笛、停止・盲目・発光、毒、パンケーキ、チャージ中の鈍足など)
        hornHolders.remove(p.getUniqueId());   // 狩猟笛のバフの管理
        ult.reset(p);                          // ウルト(チャージ・効果中の状態)をリセット
        WeaponHandler keychain = weaponHandlers.get(Weapon.KEYCHAIN);
        if (keychain instanceof KeychainHandler handler) handler.restoreOnSpectator(this, p);
    }

    // ===== 硬直 =====

    /** 硬直を解除する(記録・移動速度低下・ジャンプ封じ)。死亡時とスペクテイターになったときに使う */
    private void resetStiffness(Player p) {
        stiffUntil.remove(p.getUniqueId());
        p.getPersistentDataContainer().remove(stiffRemainKey);
        removeJumpLock(p);
        p.removePotionEffect(PotionEffectType.SLOWNESS);
    }

    private boolean isStiff(Player p) {
        Long until = stiffUntil.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    void stiffen(Player p, int ticks) {
        stiffUntil.put(p.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
        giveEffect(p, new PotionEffect(PotionEffectType.SLOWNESS, ticks, 6, false, false, false));
        applyJumpLock(p, ticks);
    }

    /** ジャンプ封じ: 跳躍力の属性を 0 にする(旧来のジャンプ力上昇エフェクト128は、今のバージョンでは逆に超ジャンプになる) */
    private void applyJumpLock(Player p, int ticks) {
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

    /** ログイン時: ログアウト時の硬直の残りがあれば復元し、なければジャンプ封じの取り残しを掃除する */
    private void restoreStiffnessOnJoin(Player p) {
        Long remain = p.getPersistentDataContainer().get(stiffRemainKey, PersistentDataType.LONG);
        p.getPersistentDataContainer().remove(stiffRemainKey);
        if (remain != null && remain > 50) {
            stiffUntil.put(p.getUniqueId(), System.currentTimeMillis() + remain);
            applyJumpLock(p, (int) (remain / 50));
        } else {
            removeJumpLock(p);
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
                WeaponHandler keychain = weaponHandlers.get(Weapon.KEYCHAIN);
                if (keychain instanceof KeychainHandler handler) handler.restoreOnDisable(this, pl);
            } catch (IllegalStateException ex) {
                threadNote("停止時の後片付け", ex); // Folia: 別リージョンのプレイヤーには触れないことがある(次回ログイン時に掃除される)
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        restoreStiffnessOnJoin(e.getPlayer()); // ログアウト時の硬直の残りを復元(なければ、取り残しの掃除)
        WeaponHandler keychain = weaponHandlers.get(Weapon.KEYCHAIN);
        if (keychain != null) keychain.onJoin(this, e.getPlayer());
        startPlayerTask(e.getPlayer());
    }

    // スペクテイターになったら、チャージ中の武器は中断され(チャージの見回りが検知する)、全武器のクールタイムをリセットし、
    // このプラグイン由来の効果・状態(硬直、各種の効果、キーホルダーなど)をすべて消去する
    @EventHandler(ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent e) {
        if (e.getNewGameMode() != GameMode.SPECTATOR) return;
        UUID id = e.getPlayer().getUniqueId();
        laserCooldown.remove(id);   // 光の剣
        guitarCooldown.remove(id);  // ギター
        rejectCooldown.remove(id);  // 拒絶
        pancakeCooldown.remove(id); // パンケーキ
        slashCooldown.remove(id);   // 斬撃(仕込み杖・獣肉断ち)
        clearEverythingFromPlugin(e.getPlayer()); // 硬直・効果・キーホルダーなど、このプラグイン由来のものをすべて消去
    }

    // ===== クールタイム =====

    /** クールタイム中ならアクションバーに残り時間を出して true を返す */
    boolean onCooldown(Player p, Map<UUID, Long> map) {
        Long until = map.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (until != null && until > now) {
            p.sendActionBar(Component.text(
                    String.format("クールタイム: %.1f秒", (until - now) / 1000.0), NamedTextColor.RED));
            return true;
        }
        return false;
    }

    boolean isPancakeOnCooldown(Player p) {
        return onCooldown(p, pancakeCooldown);
    }

    void startCooldown(Player p, Map<UUID, Long> map, int ticks) {
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
            WeaponHandler slashHandler = held == null ? null : weaponHandlers.get(held);
            if (slashHandler != null && !isStiff(attacker)) {
                attacker.getScheduler().runDelayed(this, t -> {
                    Long last = lastMeleeAttack.get(attacker.getUniqueId());
                    if (last != null && System.currentTimeMillis() - last < 300) return;
                    if (isStiff(attacker) || getWeapon(attacker.getInventory().getItemInMainHand()) != held) return;
                    slashHandler.onLeftClick(this, attacker, null);
                }, null, 1L);
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
        WeaponHandler handler = weaponHandlers.get(w);
        if (handler != null) handler.onRightClick(this, e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        // 弓矢・投げ物(バニラ)が当たったときも、見込みダメージの1/3 %のチャージを増やす
        if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player shooter) {
            countProjectileForUlt(e, proj, shooter);
            return;
        }
        if (!(e.getDamager() instanceof Player p)) return;

        // 斬撃・爆風など、プラグイン自身が与えたダメージには武器の追加効果を連鎖させない
        if (inSlash.get()) return;

        // 硬直中は攻撃不可
        if (isStiff(p)) {
            e.setCancelled(true);
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            lastMeleeAttack.put(p.getUniqueId(), System.currentTimeMillis()); // 直近に殴った時刻
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            Weapon held = getWeapon(p.getInventory().getItemInMainHand());
            WeaponHandler handler = held == null ? null : weaponHandlers.get(held);
            if (handler != null) handler.onMeleeDamage(this, e, p);
            // 近接攻撃が当たったぶん、ウルトのチャージを増やす
            if (held != null && (handler == null || handler.countsMeleeForUlt()) && e.getEntity() instanceof LivingEntity victim) {
                ult.addCharge(p, held, victim);
            }
            // このプラグインの武器以外で殴ったとき: 素手(拳)なら 0.1%、バニラの武器なら見込みダメージの1/3 %
            if (held == null && e.getEntity() instanceof LivingEntity otherVictim) {
                if (p.getInventory().getItemInMainHand().getType().isAir()) {
                    ult.addChargeFor(p, UltimateManager.FIST_CHARGE, otherVictim);
                } else {
                    ult.addChargeFor(p, e.getDamage() * UltimateManager.VANILLA_DAMAGE_CHARGE_RATIO, otherVictim);
                }
            }
        }
    }

    /** 防具(4部位)の耐久値を減らす。耐久が尽きた防具は壊れる。 */
    void damageArmor(LivingEntity target, int amount) {
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
        // 唐傘弾: 発動後に最初に放った矢に印を付ける
        if (e.getEntity() instanceof AbstractArrow arrow && !(arrow instanceof Trident)
                && arrow.getShooter() instanceof Player shooter && ult.consumeKarakasa(shooter)) {
            arrow.getPersistentDataContainer().set(karakasaArrowKey, PersistentDataType.BYTE, (byte) 1);
        }
        if (e.getEntity() instanceof Snowball ball) {
            Weapon w = getWeapon(ball.getItem());
            WeaponHandler handler = w == null ? null : weaponHandlers.get(w);
            if (handler != null) handler.onProjectileLaunch(this, e, ball);

        }
    }

    // 投擲武器(雪玉ベース)の着弾
    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent e) {
        // 唐傘弾の矢の着弾: 爆発して、矢は消える
        if (e.getEntity() instanceof AbstractArrow arrow) {
            Byte mark = arrow.getPersistentDataContainer().get(karakasaArrowKey, PersistentDataType.BYTE);
            if (mark != null) {
                if (mark == 1) { // 1=装填済み。爆発は1回だけ。2=使用済み(印は残して、チャージの加算から除外する)
                    arrow.getPersistentDataContainer().set(karakasaArrowKey, PersistentDataType.BYTE, (byte) 2);
                    KarakasaHandler.blast(this, arrow);
                    arrow.remove();
                }
                return;
            }
        }
        if (!(e.getEntity() instanceof Snowball ball)) return;
        Weapon w = getWeapon(ball.getItem());
        WeaponHandler handler = w == null ? null : weaponHandlers.get(w);
        if (handler != null) handler.onProjectileHit(this, e, ball);
    }

    /** パンケーキ: 着弾点の半径3ブロックに、吐き気・盲目・爆音(本人にだけ聞こえる)を80tick与える */
    /** 本人にだけ、爆音を PANCAKE_EFFECT_TICKS の間くり返し鳴らす */
    /** 月人: 重力なし・一定速度で、近くの敵を追尾しながら進む。一定時間で自己破壊(爆発)する */
    /** 追尾する相手を探す: 投げた本人・アーマースタンド・スペクテイターを除いた最も近い敵(壁越しは除く) */
    /** 月人: 半径7ブロックの爆発。中心が centerDamage で、距離に応じて直線的に小さくなる */
    /** FUSHI爆弾: 着弾点で爆風 */
    /**
     * プラグイン自身のダメージ処理(武器の追加効果を連鎖させない)。
     * 相手を扱うスレッドで実行する(Folia 対応。Paper ではそのまま直ちに実行される)。
     */
    void damageBy(LivingEntity target, double amount, Object source) {
        damageBy(target, amount, source, null);
    }

    /** weapon を渡すと、ダメージを与えたあとに、その武器のぶんウルトのチャージが増える(攻撃したのがプレイヤーのとき) */
    void damageBy(LivingEntity target, double amount, Object source, Weapon weapon) {
        onEntity(target, () -> {
            inSlash.set(true);
            try {
                if (source instanceof LivingEntity src) target.damage(amount, src);
                else target.damage(amount);
            } finally {
                inSlash.set(false);
            }
            if (weapon != null && source instanceof Player attacker) ult.addCharge(attacker, weapon, target);
        });
    }

    /**
     * 防具を無視するダメージ(魔法ダメージ扱い)で当てる。防具値・防具強度は無視される。
     * 耐性ポーションやプロテクションは、そのまま効く。攻撃者は source として記録される。
     */
    void damageByPierce(LivingEntity target, double amount, Player source, Weapon weapon) {
        onEntity(target, () -> {
            inSlash.set(true);
            try {
                DamageSource ds = DamageSource.builder(DamageType.INDIRECT_MAGIC)
                        .withCausingEntity(source).build();
                target.damage(amount, ds);
            } finally {
                inSlash.set(false);
            }
            if (weapon != null) ult.addCharge(source, weapon, target);
        });
    }

    /** 時限爆弾(もと光るダムダム弾): 着弾時に直撃ダメージ → 0.5秒後に爆発 */
    /** 停止 + 盲目。プレイヤーは攻撃やスキルも封じ、さらに発光する。モブは AI も止める。(相手を扱うスレッドで呼ぶこと) */
    void freeze(LivingEntity le, int ticks) {
        giveEffect(le, new PotionEffect(PotionEffectType.BLINDNESS, ticks, 0, false, true, true));
        if (le instanceof Player p) {
            stiffen(p, ticks);
            giveEffect(p, new PotionEffect(PotionEffectType.GLOWING, ticks, 0, false, false, true)); // 発光(居場所が分かる)
        } else {
            giveEffect(le, new PotionEffect(PotionEffectType.SLOWNESS, ticks, 6, false, false, false));
            if (le instanceof Mob mob) {
                mob.setAware(false);
                mob.getScheduler().runDelayed(this, t -> mob.setAware(true), null, Math.max(1, ticks));
            }
        }
    }

    // ===== ウルト =====

    // 古狩人の遺骨: ジャンプの検知(ジャンプの統計が増えたとき)
    @EventHandler(ignoreCancelled = true)
    public void onJumpStat(PlayerStatisticIncrementEvent e) {
        if (e.getStatistic() == Statistic.JUMP) ult.onJump(e.getPlayer());
    }

    // 古狩人の遺骨: パッドなどでキー入力が取れないときのために、直近の移動の向きを覚える
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        ult.onMove(e.getPlayer(), e.getFrom(), e.getTo());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        charging.remove(e.getPlayer().getUniqueId());
        // 硬直は、ログアウトで解除しない。残り時間を保存し、ログイン時に復元する(効果の時間もオフライン中は進まないため、それに合わせる)
        Long until = stiffUntil.remove(e.getPlayer().getUniqueId());
        long remain = until == null ? 0 : until - System.currentTimeMillis();
        if (remain > 0) {
            e.getPlayer().getPersistentDataContainer().set(stiffRemainKey, PersistentDataType.LONG, remain);
        }
        removeJumpLock(e.getPlayer());
        lastMeleeAttack.remove(e.getPlayer().getUniqueId());
        laserCooldown.remove(e.getPlayer().getUniqueId());
        rejectCooldown.remove(e.getPlayer().getUniqueId());
        slashCooldown.remove(e.getPlayer().getUniqueId());
        pancakeCooldown.remove(e.getPlayer().getUniqueId());
        guitarCooldown.remove(e.getPlayer().getUniqueId());
        hornHolders.remove(e.getPlayer().getUniqueId());
        ult.clearActive(e.getPlayer().getUniqueId());
        pluginEffects.remove(e.getPlayer().getUniqueId());
    }

    // ===== キーホルダー(光の剣を持っている間、オフハンドを埋める) =====
    /** キーホルダーを作る。stored(元のオフハンドのアイテム)は、キーホルダー自身に保存しておく */
    /** キーホルダーが預かっている元のアイテム(なければ null) */
    /** 光の剣を持っていればオフハンドをキーホルダーで埋め、持っていなければ元のアイテムに戻す */
    /** オフハンドのキーホルダーを外し、預かっていたアイテムを返す */
    /** キーホルダーが持ち物のどこにも無いのに、預かりの控えが残っていたら、そのアイテムを返す */
    /** オフハンド以外に紛れ込んだキーホルダーを回収し、預かっていたアイテムを返す(ログイン時) */
    /** ログイン時の確認: オフハンドにキーホルダーが残っていて、光の剣を持っていなければ、元のアイテムに戻す */
    // キーホルダーは動かせない・捨てられない
    // キーホルダー関連イベントは KeychainHandler に委譲する
    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        WeaponHandler handler = weaponHandlers.get(Weapon.KEYCHAIN);
        if (handler != null) handler.onInventoryClick(this, e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        WeaponHandler handler = weaponHandlers.get(Weapon.KEYCHAIN);
        if (handler != null) handler.onSwapHands(this, e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent e) {
        WeaponHandler handler = weaponHandlers.get(Weapon.KEYCHAIN);
        if (handler != null) handler.onDropItem(this, e);
    }

    // 死亡時は、キーホルダーを落とさず、預かっていたアイテムを落とす
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        resetStiffness(dead);       // 死んだら、硬直は解除(リスポーンしても硬直が残らない)
        clearPluginEffects(dead);   // 効果の記録も消す
        hornHolders.remove(dead.getUniqueId());
        ult.reset(dead);            // ウルトのチャージと効果中の状態をリセット
        WeaponHandler keychain = weaponHandlers.get(Weapon.KEYCHAIN);
        if (keychain != null) keychain.onDeath(this, e);
    }

    // ===== 狩猟笛(持っている間、攻撃力増加I と 移動速度増加I) =====
    // ===== 光の剣・ギター(ウォーデンの衝撃波) =====

    /** 右クリックでチャージを始め、満タンになったら衝撃波を撃つ(光の剣 / ギター) */
    /** 光の剣のチャージ中、1秒ごとに発射シーケンスのメッセージをチャットへ送る(tick=1,21,41 が各秒の始まり) */
    /** タートルマスター(ポーション): 移動速度低下IV + 耐性III */
    // ===== 仕込み杖 =====

    /** メインハンドの仕込み杖を、もう一方の形態に持ち替える */
    void transformWeapon(Player p, Weapon to) {
        ItemStack old = p.getInventory().getItemInMainHand();
        Weapon from = getWeapon(old);
        ItemStack next = createItem(to);
        ItemMeta oldMeta = old.getItemMeta();
        ItemMeta nextMeta = next.getItemMeta();
        // 金床などで付けた名前・説明文は引き継ぐ(名前が標準のままなら、変形後の標準の名前になる)。
        // 耐久値は、武器が壊れない設定なので、引き継ぐ必要がない。
        if (oldMeta != null && from != null) {
            if (oldMeta.hasDisplayName()) {
                String plain = PlainTextComponentSerializer.plainText().serialize(oldMeta.displayName());
                if (!plain.equals(from.displayName)) nextMeta.displayName(oldMeta.displayName());
            }
            if (oldMeta.lore() != null) nextMeta.lore(oldMeta.lore());
            next.setItemMeta(nextMeta);
        }
        next.addUnsafeEnchantments(old.getEnchantments()); // エンチャントを引き継ぐ
        p.getInventory().setItemInMainHand(next);
        boolean drawing = to.id.endsWith("_drawn");
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1f, drawing ? 1.6f : 1.0f);
        p.sendActionBar(Component.text(to.displayName, NamedTextColor.GRAY));
    }

    /** 斬撃を持つ武器か */
    /** 武器ごとの斬撃(範囲・角度・ダメージ・連発間隔)で、前方の扇形を斬る */
    /**
     * 前方の扇形(半径 range ブロック・全体の角度 angle 度)にいる敵へ斬撃。
     * exclude は通常攻撃で既に当たった相手(二重にダメージを与えない)。
     */
    // ===== 拒絶 =====



    // ===== 共通の扇形斬撃判定 =====

    void slash(Player p, Entity exclude, double range, double angle, double damage, int intervalTicks) {
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
            } catch (IllegalStateException ex) {
                threadNote("斬撃の遮蔽判定", ex);
                blocked = false; // Folia: 別リージョンのブロックは読めない。遮られていないものとして扱う
            }
            if (blocked) continue;
            targets.add(le);
        }
        Weapon held = getWeapon(p.getInventory().getItemInMainHand()); // 斬撃は、持っている武器のぶんチャージが増える
        for (LivingEntity le : targets) damageBy(le, damage, p, held);
    }

    // ===== コマンド: /weapon <id> [player] =====

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("使い方: /weapon <武器ID> [プレイヤー] [個数] [所持上限]");
            return true;
        }
        Weapon w = Weapon.byId(args[0]);
        if (w == null) {
            sender.sendMessage("そのIDの武器はありません。");
            return true;
        }
        if (w == Weapon.KEYCHAIN) {
            sender.sendMessage("キーホルダーは、スーパーノヴァを持つと自動で付与されるアイテムです。");
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
        // 4つ目の引数: 所持上限。この武器をすでに持っている数を数え、上限に届くぶんだけ渡す(補給用)
        if (args.length >= 4) {
            int max;
            try {
                max = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("所持上限は数字で指定してください。");
                return true;
            }
            if (max > 0) {
                int have = 0;
                for (ItemStack it : target.getInventory().getContents()) {
                    if (it != null && getWeapon(it) == w) have += it.getAmount();
                }
                amount = Math.min(amount, max - have);
                if (amount <= 0) {
                    sender.sendMessage(w.displayName + " は上限(" + max + "個)まで所持済みです。");
                    return true;
                }
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
