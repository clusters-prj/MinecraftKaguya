package trigger.tukuyomiWeapons;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.concurrent.atomic.AtomicInteger;

public final class PancakeHandler implements WeaponHandler {
    private static final double PANCAKE_RADIUS = 3;
    private static final int PANCAKE_EFFECT_TICKS = 80;
    private static final int PANCAKE_SOUND_INTERVAL_TICKS = 10;
    private static final boolean PANCAKE_HITS_THROWER = false;

    private static final int PANCAKE_COOLDOWN_TICKS = 300;

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {
        if (plugin.isPancakeOnCooldown(event.getPlayer())) event.setCancelled(true);
    }
    @Override public void onProjectileLaunch(TukuyomiWeapons plugin, ProjectileLaunchEvent event, Snowball projectile) {
        if (projectile.getShooter() instanceof Player player) {
            if (plugin.isPancakeOnCooldown(player)) { event.setCancelled(true); return; }
            plugin.startCooldown(player, plugin.pancakeCooldown, PANCAKE_COOLDOWN_TICKS);
        }
    }
    @Override public void onProjectileHit(TukuyomiWeapons plugin, ProjectileHitEvent event, Snowball projectile) { pancakeBurst(plugin, projectile); }

void pancakeBurst(TukuyomiWeapons plugin, Snowball ball) {
    Location center = ball.getLocation();
    World world = center.getWorld();
    Object shooter = ball.getShooter();

    world.spawnParticle(Particle.SNEEZE, center, 40, PANCAKE_RADIUS / 2, 0.5, PANCAKE_RADIUS / 2, 0.02);
    world.playSound(center, Sound.ENTITY_SLIME_SQUISH, 1.5f, 0.7f);

    for (LivingEntity le : plugin.nearbyLiving(center, PANCAKE_RADIUS)) {
        if (le instanceof ArmorStand) continue;
        if (!PANCAKE_HITS_THROWER && le.equals(shooter)) continue;

        plugin.onEntity(le, () -> {
            if (!plugin.canAffect(shooter, le)) return; // PvP禁止区域などでは、効果を与えない
            plugin.giveEffect(le, new PotionEffect(PotionEffectType.NAUSEA, PANCAKE_EFFECT_TICKS, 0));
            plugin.giveEffect(le, new PotionEffect(PotionEffectType.BLINDNESS, PANCAKE_EFFECT_TICKS, 0));
            // 爆音は Player#playSound で、本人にだけ聞こえる
            if (le instanceof Player victim) playExplosionLoop(plugin, victim);
        });
    }
}

private void playExplosionLoop(TukuyomiWeapons plugin, Player victim) {
    AtomicInteger elapsed = new AtomicInteger(0);
    victim.playSound(victim.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.9f + (float) Math.random() * 0.2f);
    elapsed.addAndGet(PANCAKE_SOUND_INTERVAL_TICKS);
    victim.getScheduler().runAtFixedRate(plugin, task -> {
        if (!victim.isOnline() || elapsed.get() >= PANCAKE_EFFECT_TICKS) {
            task.cancel();
            return;
        }
        victim.playSound(victim.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.9f + (float) Math.random() * 0.2f);
        elapsed.addAndGet(PANCAKE_SOUND_INTERVAL_TICKS);
    }, null, PANCAKE_SOUND_INTERVAL_TICKS, PANCAKE_SOUND_INTERVAL_TICKS);
}
}
