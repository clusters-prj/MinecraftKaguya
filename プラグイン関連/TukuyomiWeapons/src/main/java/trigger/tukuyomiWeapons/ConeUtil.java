package trigger.tukuyomiWeapons;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/** 円錐状の範囲の判定と演出(竜撃砲・音撃震で使う)。角度は、円錐全体の開き(斬撃と同じ数え方)。 */
final class ConeUtil {
    private ConeUtil() {}

    /** eye から dir の向きに、距離 range 以内・全体の開き fullAngleDeg の円錐にいる相手(壁の向こうは除く) */
    static List<LivingEntity> inCone(TukuyomiWeapons plugin, Location eye, Vector dir, double range, double fullAngleDeg, Player exclude) {
        World world = eye.getWorld();
        Vector d = dir.clone().normalize();
        double half = Math.toRadians(fullAngleDeg / 2);
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity le : plugin.nearbyLiving(eye, range)) {
            if (le.equals(exclude) || le instanceof ArmorStand) continue;
            Vector to = le.getBoundingBox().getCenter().subtract(eye.toVector());
            double dist = to.length();
            if (dist > range || dist < 1.0E-4) continue;
            if (d.angle(to) > half) continue;
            boolean blocked;
            try {
                blocked = world.rayTraceBlocks(eye, to.clone().normalize(), dist, FluidCollisionMode.NEVER, true) != null;
            } catch (IllegalStateException ex) {
                plugin.threadNote("円錐の遮蔽判定", ex);
                blocked = false; // Folia: 別リージョンのブロックは読めない。遮られていないものとして扱う
            }
            if (!blocked) out.add(le);
        }
        return out;
    }

    /** 円錐状に広がる爆煙の演出 */
    static void smoke(World world, Location eye, Vector dir, double range, double fullAngleDeg) {
        Vector d = dir.clone().normalize();
        double tan = Math.tan(Math.toRadians(fullAngleDeg / 2));
        Vector right = d.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-4) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(d).normalize();
        for (double dist = 1; dist <= range + 1.0E-9; dist += 1.0) {
            Location center = eye.clone().add(d.clone().multiply(dist));
            double r = dist * tan * 0.8;
            world.spawnParticle(Particle.LARGE_SMOKE, center, 3, r * 0.3, r * 0.3, r * 0.3, 0.02);
            for (int k = 0; k < 6; k++) {
                double a = Math.PI * 2 * k / 6;
                Vector off = right.clone().multiply(Math.cos(a) * r).add(up.clone().multiply(Math.sin(a) * r));
                world.spawnParticle(Particle.LARGE_SMOKE, center.clone().add(off), 2, 0.1, 0.1, 0.1, 0.01);
            }
            if (((int) dist) % 3 == 0) world.spawnParticle(Particle.EXPLOSION, center, 1, r * 0.3, r * 0.3, r * 0.3, 0);
        }
    }
}
