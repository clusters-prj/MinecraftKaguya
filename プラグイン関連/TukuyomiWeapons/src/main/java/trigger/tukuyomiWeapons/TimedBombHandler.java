package trigger.tukuyomiWeapons;

import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Snowball;
import org.bukkit.event.entity.ProjectileHitEvent;

import java.util.concurrent.atomic.AtomicInteger;

public final class TimedBombHandler implements WeaponHandler {
    private static final double TIMED_IMPACT_DAMAGE = 3;
    private static final int TIMED_DELAY_TICKS = 10;
    private static final double TIMED_RADIUS = 4;
    private static final double TIMED_DAMAGE = 30;
    private static final boolean TIMED_HITS_THROWER = false;

    @Override public void onProjectileHit(TukuyomiWeapons plugin, ProjectileHitEvent event, Snowball projectile) { timedBomb(plugin, event, projectile); }

void timedBomb(TukuyomiWeapons plugin, ProjectileHitEvent e, Snowball ball) {
    Location center = ball.getLocation().clone();
    World world = center.getWorld();
    Object shooter = ball.getShooter();

    if (e.getHitEntity() instanceof LivingEntity hit && !(hit instanceof ArmorStand)) {
        plugin.damageBy(hit, TIMED_IMPACT_DAMAGE, shooter);
    }

    world.playSound(center, Sound.ENTITY_CREEPER_PRIMED, 1.5f, 1f);

    AtomicInteger tick = new AtomicInteger(0);
    // 着弾した場所を担当するリージョンで動かす
    Bukkit.getRegionScheduler().runAtFixedRate(plugin, center, task -> {
        int t = tick.incrementAndGet();
        // 爆発までの予兆(煙)
        if (t % 2 == 0) {
            world.spawnParticle(Particle.SMOKE, center, 6, 0.3, 0.3, 0.3, 0.01);
        }
        if (t < TIMED_DELAY_TICKS) return;
        task.cancel();

        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.8f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1, 0, 0, 0, 0);
        for (LivingEntity le : plugin.nearbyLiving(center, TIMED_RADIUS)) {
            if (le instanceof ArmorStand) continue;
            if (!TIMED_HITS_THROWER && le.equals(shooter)) continue;
            plugin.damageBy(le, TIMED_DAMAGE, shooter);
        }
    }, 1L, 1L);
}
}
