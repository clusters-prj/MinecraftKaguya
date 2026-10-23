package trigger.tukuyomiWeapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 星界からの使者(ウルト): 視線の先のブロックめがけて隕石を落とす。
 * 着弾するまで、隕石に当たり判定はない。着弾点の爆風は半径15ブロック、中心のダメージは100。中心から1ブロック離れるたびに、ダメージは前の1/2になる。
 * ブロックは壊さない。
 */
public final class EnvoyFromStarsHandler implements WeaponHandler {
    static final double RAY_DISTANCE = 120;   // 狙えるブロックまでの最大距離
    static final double RADIUS = 15;
    static final double CENTER_DAMAGE = 100;
    static final double MIN_DAMAGE = 0.5;     // これ未満になった相手には、ダメージを与えない
    static final boolean HITS_CASTER = false;
    static final int FALL_TICKS = 60;         // 落下にかかる時間(3秒)
    static final double START_HEIGHT = 70;    // 落下の開始位置の高さ
    static final double START_OFFSET = 25;    // 落下の開始位置の、水平方向のずれ(向いている方向の奥側)

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.ult.isFull(p)) { // チャージ不足の通知
            plugin.ult.consumeCharge(p);
            return;
        }
        Location eye = p.getEyeLocation();
        World world = p.getWorld();
        RayTraceResult hit = world.rayTraceBlocks(eye, eye.getDirection(), RAY_DISTANCE, FluidCollisionMode.NEVER, true);
        if (hit == null) {
            p.sendActionBar(Component.text("星界からの使者: 狙えるブロックがない(視線の先が遠すぎる)", NamedTextColor.RED));
            return;
        }
        if (!plugin.ult.consumeCharge(p)) return;

        Location target = hit.getHitPosition().toLocation(world);
        Vector away = eye.getDirection().setY(0);
        if (away.lengthSquared() < 1.0E-4) away = new Vector(1, 0, 0);
        away.normalize();
        Location start = target.clone().add(away.multiply(START_OFFSET)).add(0, START_HEIGHT, 0);
        Vector path = target.toVector().subtract(start.toVector());

        world.playSound(eye, Sound.ENTITY_WITHER_SPAWN, 1.5f, 1.5f);
        AtomicInteger tick = new AtomicInteger(0);
        // 着弾点を扱うスレッドで動かす(Folia 対応)
        Bukkit.getRegionScheduler().runAtFixedRate(plugin, target, task -> {
            int k = tick.incrementAndGet();
            double r = Math.min(1.0, k / (double) FALL_TICKS);
            Location pos = start.clone().add(path.clone().multiply(r));
            world.spawnParticle(Particle.FLAME, pos, 30, 1.2, 1.2, 1.2, 0.05);
            world.spawnParticle(Particle.LARGE_SMOKE, pos, 15, 1.5, 1.5, 1.5, 0.02);
            if (k % 10 == 0) world.playSound(pos, Sound.ENTITY_BLAZE_SHOOT, 3f, 0.5f);
            if (k >= FALL_TICKS) {
                task.cancel();
                explode(plugin, target, p);
            }
        }, 1L, 1L);
    }

    /** 着弾: 半径15ブロックに、中心100から、1ブロック離れるたびに1/2になるダメージ */
    private void explode(TukuyomiWeapons plugin, Location center, Player caster) {
        World world = center.getWorld();
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 6f, 0.5f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 10, RADIUS / 3, RADIUS / 6, RADIUS / 3, 0);
        world.spawnParticle(Particle.FLAME, center, 200, RADIUS / 3, RADIUS / 6, RADIUS / 3, 0.2);

        for (LivingEntity le : plugin.nearbyLiving(center, RADIUS)) {
            if (le instanceof ArmorStand) continue;
            if (!HITS_CASTER && le.equals(caster)) continue;
            double dist = le.getBoundingBox().getCenter().distance(center.toVector());
            // 中心から1ブロック離れるたびに、前の1/2(0〜1ブロック:100、1〜2ブロック:50、2〜3ブロック:25 …)
            double damage = CENTER_DAMAGE * Math.pow(0.5, Math.floor(dist));
            if (damage < MIN_DAMAGE) continue;
            plugin.damageBy(le, damage, caster);
        }
    }
}
