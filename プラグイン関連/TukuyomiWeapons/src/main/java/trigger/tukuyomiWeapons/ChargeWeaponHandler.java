package trigger.tukuyomiWeapons;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** チャージ式衝撃波武器の共通エンジン。武器固有の設定・効果は各サブクラスが定義する。 */
public abstract class ChargeWeaponHandler implements WeaponHandler {
    protected abstract Weapon weapon();
    protected abstract int chargeTicks();
    protected abstract double range();
    protected abstract double damage();
    protected abstract int cooldownTicks();
    protected abstract boolean pierceWalls();
    /** true なら防具を無視するダメージで当てる(耐性・プロテクションは効く) */
    protected boolean piercesArmor() { return false; }
    /** 通常の近接攻撃ではウルトゲージを増やさない(衝撃波が当たったときだけ数える) */
    @Override public boolean countsMeleeForUlt() { return false; }
    protected abstract float chargePitch();
    protected abstract float shockwavePitch();
    protected abstract void applyChargeEffect(TukuyomiWeapons plugin, Player player, int ticks);
    protected abstract void removeChargeEffect(TukuyomiWeapons plugin, Player player);
    protected abstract void afterFire(TukuyomiWeapons plugin, Player player);
    protected void onChargeTick(TukuyomiWeapons plugin, Player player, int tick) {}
    /** 衝撃波が当たる範囲の半径(ブロック) */
    protected double hitRadius() { return 1.0; }
    /** チャージを始めてよいか。ウルトは、ここでキルのチャージを消費する */
    protected boolean tryActivate(TukuyomiWeapons plugin, Player player) { return true; }

    @Override
    public final void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {
        startCharge(plugin, event.getPlayer());
    }

    private void startCharge(TukuyomiWeapons plugin, Player p) {
        Weapon w = weapon();
        UUID id = p.getUniqueId();
        if (plugin.charging.contains(id)) return;
        Map<UUID, Long> cooldown = cooldownMap(plugin);
        if (plugin.onCooldown(p, cooldown)) return;
        if (!tryActivate(plugin, p)) return;
        plugin.charging.add(id);

        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.5f, chargePitch());
        applyChargeEffect(plugin, p, chargeTicks() + 2);

        BossBar bar = BossBar.bossBar(Component.text(w.displayName + " チャージ中", NamedTextColor.AQUA),
                0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        p.showBossBar(bar);
        AtomicInteger tick = new AtomicInteger(0);
        p.getScheduler().runAtFixedRate(plugin, task -> {
            if (!p.isOnline() || p.isDead() || p.getGameMode() == GameMode.SPECTATOR
                    || plugin.getWeapon(p.getInventory().getItemInMainHand()) != w) {
                plugin.charging.remove(id);
                p.hideBossBar(bar);
                removeChargeEffect(plugin, p);
                task.cancel();
                return;
            }
            int t = tick.incrementAndGet();
            onChargeTick(plugin, p, t);
            p.getWorld().spawnParticle(Particle.END_ROD,
                    p.getEyeLocation().add(p.getLocation().getDirection().multiply(0.8)),
                    2, 0.15, 0.15, 0.15, 0.02);
            bar.progress(Math.min(1f, t / (float) chargeTicks()));
            if (t >= chargeTicks()) {
                plugin.charging.remove(id);
                p.hideBossBar(bar);
                task.cancel();
                fireShockwave(plugin, p);
            }
        }, () -> {
            plugin.charging.remove(id);
            p.hideBossBar(bar);
        }, 1L, 1L);
    }

    protected Map<UUID, Long> cooldownMap(TukuyomiWeapons plugin) {
        return weapon() == Weapon.GUITAR ? plugin.guitarCooldown : plugin.laserCooldown;
    }

    private void fireShockwave(TukuyomiWeapons plugin, Player p) {
        World world = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        removeChargeEffect(plugin, p);

        double length = range();
        if (!pierceWalls()) {
            RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, length, FluidCollisionMode.NEVER, true);
            if (blockHit != null) length = eye.toVector().distance(blockHit.getHitPosition());
        }
        world.playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 2f, shockwavePitch());

        List<Location> points = new ArrayList<>();
        for (double d = 1; d <= length; d += 1.0) {
            Location point = eye.clone().add(dir.clone().multiply(d));
            if (((int) d) % 2 == 0) world.spawnParticle(Particle.SONIC_BOOM, point, 1, 0, 0, 0, 0);
            points.add(point);
        }
        Map<Long, List<Location>> byChunk = new LinkedHashMap<>();
        for (Location pt : points) {
            long key = (((long) (pt.getBlockX() >> 4)) << 32) | ((pt.getBlockZ() >> 4) & 0xFFFFFFFFL);
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(pt);
        }
        Set<UUID> hit = ConcurrentHashMap.newKeySet();
        try {
            for (List<Location> group : byChunk.values()) {
                plugin.atLocation(group.get(0), () -> {
                    for (Location pt : group) {
                        for (LivingEntity le : plugin.nearbyLiving(pt, hitRadius())) {
                            if (le.equals(p) || le instanceof ArmorStand) continue;
                            if (hit.add(le.getUniqueId())) {
                                if (piercesArmor()) plugin.damageByPierce(le, damage(), p, weapon());
                                else plugin.damageBy(le, damage(), p, weapon());
                            }
                        }
                    }
                });
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("衝撃波の判定中にエラーが出ました: " + ex);
        } finally {
            afterFire(plugin, p); // エラーが出ても、硬直とクールタイムは必ず始める
        }
    }
}
