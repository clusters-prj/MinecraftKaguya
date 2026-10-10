package trigger.tukuyomiWeapons;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Snowball;
import org.bukkit.event.entity.ProjectileHitEvent;

public final class FushiBombHandler implements WeaponHandler {
    private static final double FUSHI_RADIUS = 2;
    private static final double FUSHI_DAMAGE = 2;
    private static final double FUSHI_STUN_CHANCE = 0.01;
    private static final int FUSHI_STUN_TICKS = 8000;
    private static final boolean FUSHI_HITS_THROWER = false;

    @Override public void onProjectileHit(TukuyomiWeapons plugin, ProjectileHitEvent event, Snowball projectile) { fushiBlast(plugin, projectile); }

void fushiBlast(TukuyomiWeapons plugin, Snowball ball) {
    Location center = ball.getLocation();
    World world = center.getWorld();
    Object shooter = ball.getShooter();

    world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.3f);
    world.spawnParticle(Particle.EXPLOSION, center, 1, 0, 0, 0, 0);

    for (LivingEntity le : plugin.nearbyLiving(center, FUSHI_RADIUS)) {
        if (le instanceof ArmorStand) continue;
        if (!FUSHI_HITS_THROWER && le.equals(shooter)) continue;
        if (le.getBoundingBox().getCenter().distance(center.toVector()) > FUSHI_RADIUS
                && le.getLocation().distance(center) > FUSHI_RADIUS) continue;

        plugin.damageBy(le, FUSHI_DAMAGE, shooter);
        if (Math.random() < FUSHI_STUN_CHANCE) {
            plugin.onEntity(le, () -> {
                if (plugin.canAffect(shooter, le)) plugin.freeze(le, FUSHI_STUN_TICKS); // PvP禁止区域などでは、停止させない
            });
        }
    }
}
}
