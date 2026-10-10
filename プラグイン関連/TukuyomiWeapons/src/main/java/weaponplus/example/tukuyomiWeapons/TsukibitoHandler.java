package weaponplus.example.tukuyomiWeapons;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

public final class TsukibitoHandler implements WeaponHandler {
    private static final double MOON_SPEED = 0.15;
    private static final int MOON_LIFETIME_TICKS = 200;
    private static final double MOON_RADIUS = 7;
    private static final double MOON_CENTER_DAMAGE = 50;
    private static final boolean MOON_HITS_THROWER = false;
    private static final double MOON_TIMEOUT_DAMAGE = 30;
    private static final double MOON_HOMING_RANGE = 12;
    private static final double MOON_TURN_RATE = 0.2;

    @Override public void onProjectileLaunch(TukuyomiWeapons plugin, ProjectileLaunchEvent event, Snowball projectile) { startMoonBomb(plugin, projectile); }
    @Override public void onProjectileHit(TukuyomiWeapons plugin, ProjectileHitEvent event, Snowball projectile) { handleTsukibitoImpact(plugin, projectile); }

void startMoonBomb(TukuyomiWeapons plugin, Snowball ball) {
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
    ball.getScheduler().runAtFixedRate(plugin, task -> {
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
            moonExplode(plugin, loc, shooter, MOON_TIMEOUT_DAMAGE);
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
                cur = findMoonTarget(plugin, ball, loc);
            }
            target[0] = cur;
            if (cur != null) {
                Vector want = cur.getBoundingBox().getCenter().subtract(loc.toVector());
                if (want.lengthSquared() > 1.0E-6) {
                    heading.multiply(1 - MOON_TURN_RATE).add(want.normalize().multiply(MOON_TURN_RATE)).normalize();
                }
            }
        } catch (IllegalStateException ex) {
            plugin.threadNote("月人の追尾", ex);
            target[0] = null; // Folia: 別リージョンにいる相手には触れない。まっすぐ進む
        }

        ball.setVelocity(heading.clone().multiply(MOON_SPEED)); // 空気抵抗で遅くならないよう毎tick速度を戻す
    }, null, 1L, 1L);
}

private LivingEntity findMoonTarget(TukuyomiWeapons plugin, Snowball ball, Location loc) {
    Object shooter = ball.getShooter();
    LivingEntity best = null;
    double bestDist = Double.MAX_VALUE;
    for (LivingEntity le : plugin.nearbyLiving(loc, MOON_HOMING_RANGE)) {
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

void handleTsukibitoImpact(TukuyomiWeapons plugin, Snowball projectile) {
    moonExplode(plugin, projectile.getLocation(), projectile.getShooter(), MOON_CENTER_DAMAGE);
}

void moonExplode(TukuyomiWeapons plugin, Location center, Object shooter, double centerDamage) {
    World world = center.getWorld();
    world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2.5f, 0.7f);
    world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 3, MOON_RADIUS / 4, MOON_RADIUS / 8, MOON_RADIUS / 4, 0);

    for (LivingEntity le : plugin.nearbyLiving(center, MOON_RADIUS)) {
        if (le instanceof ArmorStand) continue;
        if (!MOON_HITS_THROWER && le.equals(shooter)) continue;
        double dist = le.getBoundingBox().getCenter().distance(center.toVector());
        double ratio = 1.0 - dist / MOON_RADIUS;
        if (ratio <= 0) continue;
        plugin.damageBy(le, centerDamage * ratio, shooter);
    }
}
}
