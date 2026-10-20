package trigger.tukuyomiWeapons;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;

/**
 * 音撃震(ウルト): 前方30度・10ブロックの円錐状に、神の怒りの効果(吹き飛ばしの強さ2倍)と30ダメージを与える。
 * 神の怒りの10ダメージは付かず、ダメージは30(合計)。角度は円錐全体の開き。壁の向こうには届かない。
 */
public final class SonicQuakeHandler implements WeaponHandler {
    static final double RANGE = 10;
    static final double ANGLE = 30;
    static final double DAMAGE = 30;
    static final double PUSH_SPEED = RejectionHandler.REJECT_PUSH_SPEED * 2; // 神の怒りと同じ

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.ult.consumeCharge(p)) return;

        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = p.getWorld();
        world.playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 2f, 1.2f);
        world.playSound(eye, Sound.BLOCK_BELL_USE, 2f, 0.6f);
        for (int d = 2; d <= RANGE; d += 2) {
            Location pt = eye.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.SONIC_BOOM, pt, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.NOTE, pt, 4, d * 0.1, d * 0.1, d * 0.1, 1);
        }

        Vector origin = p.getLocation().toVector();
        for (LivingEntity le : ConeUtil.inCone(plugin, eye, dir, RANGE, ANGLE, p)) {
            Vector push = le.getLocation().toVector().subtract(origin).setY(0);
            if (push.lengthSquared() < 1.0E-4) push = dir.clone().setY(0);
            if (push.lengthSquared() < 1.0E-4) push = new Vector(1, 0, 0);
            final Vector pushFinal = push.normalize().multiply(PUSH_SPEED).setY(0.6);
            plugin.onEntity(le, () -> {
                if (!plugin.canAffect(p, le)) return; // PvP禁止区域などでは、ダメージも吹き飛ばしもしない
                plugin.damageBy(le, DAMAGE, p);
                le.setVelocity(pushFinal);
            });
        }
    }
}
