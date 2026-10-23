package trigger.tukuyomiWeapons;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 月光の聖剣(ウルト): 緑色の斬撃を放つ。50ブロック飛翔する。
 * 斬撃の両端は、撃ったときに向いていた方向から左右30°の直線に沿って進む(進むほど大きくなる)。
 * 斬撃は水平方向の弧で、壁は貫通する。1回の斬撃で、同じ相手には1度だけ当たる。
 */
public final class MoonlightHolySwordHandler implements WeaponHandler {
    static final double RANGE = 50;           // 飛翔距離(ブロック)
    static final double SPEED = 2.0;          // 1tickに進む距離(ブロック)
    static final double HALF_ANGLE_DEG = 30;  // 向いていた方向から、左右それぞれの角度
    static final double HIT_RADIUS = 1.5;
    static final double DAMAGE = 40;

    private static final Particle.DustOptions GREEN = new Particle.DustOptions(Color.fromRGB(60, 255, 90), 1.6f);

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.ult.consumeCharge(p)) return;

        World world = p.getWorld();
        Location origin = p.getEyeLocation();
        Vector forward = origin.getDirection().normalize();
        double half = Math.toRadians(HALF_ANGLE_DEG);
        Set<UUID> hit = ConcurrentHashMap.newKeySet();
        AtomicInteger tick = new AtomicInteger(0);

        world.playSound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 2f, 0.6f);
        world.playSound(origin, Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1.6f);

        p.getScheduler().runAtFixedRate(plugin, task -> {
            double d = SPEED * tick.incrementAndGet();
            if (d > RANGE) {
                task.cancel();
                return;
            }
            // 距離 d の位置にある弧: 向いていた方向から左右 half まで。弧の端が、左右30°の直線の上に乗る
            int n = Math.max(4, (int) Math.ceil(d * 2 * half));
            List<Location> points = new ArrayList<>(n + 1);
            for (int i = 0; i <= n; i++) {
                double a = -half + 2 * half * i / n;
                Vector v = forward.clone().rotateAroundY(a).multiply(d);
                points.add(origin.clone().add(v));
            }
            for (Location pt : points) {
                world.spawnParticle(Particle.DUST, pt, 2, 0.1, 0.5, 0.1, 0, GREEN);
            }
            // 当たり判定: 弧の点を、チャンクごとにまとめて、そのチャンクを扱うスレッドで調べる(Folia 対応)
            Map<Long, List<Location>> byChunk = new LinkedHashMap<>();
            for (Location pt : points) {
                long key = (((long) (pt.getBlockX() >> 4)) << 32) | ((pt.getBlockZ() >> 4) & 0xFFFFFFFFL);
                byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(pt);
            }
            for (List<Location> group : byChunk.values()) {
                plugin.atLocation(group.get(0), () -> {
                    for (Location pt : group) {
                        for (LivingEntity le : plugin.nearbyLiving(pt, HIT_RADIUS)) {
                            if (le.equals(p) || le instanceof ArmorStand) continue;
                            if (hit.add(le.getUniqueId())) plugin.damageBy(le, DAMAGE, p);
                        }
                    }
                });
            }
        }, null, 1L, 1L);
    }
}
