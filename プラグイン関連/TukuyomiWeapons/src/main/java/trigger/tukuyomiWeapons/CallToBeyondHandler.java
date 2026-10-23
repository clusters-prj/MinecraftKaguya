package trigger.tukuyomiWeapons;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.GameMode;
import org.bukkit.FluidCollisionMode;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 彼方への呼びかけ(ウルト): 頭上から、追尾する雪玉を放射状に12発射出する。
 * 月人と同じ仕組みで追尾するが、追尾は緩い。速度は毎秒5ブロック。爆風はなく、命中した相手に20ダメージ(無敵時間を貫通)。
 */
public final class CallToBeyondHandler implements WeaponHandler {
    static final int COUNT = 12;
    static final double SPEED = 5.0 / 20.0;             // 毎秒5ブロック(ブロック/tick)
    static final double SPAWN_HEIGHT = 2.2;             // 頭上(足元からの高さ)
    static final double RISE = 0.15;                    // 水平からのわずかな上向き成分
    static final int LIFETIME_TICKS = 200;              // 10秒(月人と同じ)。これを過ぎたら、何も起こさず消える
    static final double HOMING_RANGE = 16;
    static final double TURN_RATE = 0.15;               // 月人は 0.2。小さいほど追尾が緩い。使用感を見て要調整
    static final double DAMAGE = 20;

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.ult.consumeCharge(p)) return;

        Location origin = p.getLocation().add(0, SPAWN_HEIGHT, 0);
        World world = origin.getWorld();
        ItemStack marker = plugin.createItem(Weapon.CALL_TO_BEYOND);
        for (int i = 0; i < COUNT; i++) {
            double a = Math.PI * 2 * i / COUNT;
            Vector dir = new Vector(Math.cos(a), RISE, Math.sin(a)).normalize();
            Snowball ball = world.spawn(origin, Snowball.class, sb -> {
                sb.setShooter(p);
                sb.setItem(marker.clone()); // 着弾時にこの武器の処理へ回すための印
                sb.setGravity(false);
            });
            ball.setVelocity(dir.clone().multiply(SPEED));
            startHoming(plugin, ball, dir);
        }
        world.playSound(origin, Sound.ENTITY_ENDER_DRAGON_SHOOT, 1.2f, 1.4f);
    }

    /** 命中: 相手に20ダメージ。ヒット時の無敵時間は貫通する */
    @Override public void onProjectileHit(TukuyomiWeapons plugin, ProjectileHitEvent event, Snowball ball) {
        if (!(event.getHitEntity() instanceof LivingEntity target) || target instanceof ArmorStand) return;
        Object shooter = ball.getShooter();
        if (target.equals(shooter)) { // 自分には当たらず、すり抜ける
            event.setCancelled(true);
            return;
        }
        plugin.onEntity(target, () -> {
            target.setNoDamageTicks(0); // 無敵時間を貫通して、12発すべてが入るようにする
            plugin.damageBy(target, DAMAGE, shooter);
        });
    }

    private void startHoming(TukuyomiWeapons plugin, Snowball ball, Vector startDir) {
        final Vector heading = startDir.clone().normalize();
        AtomicInteger tick = new AtomicInteger(0);
        LivingEntity[] target = {null};
        ball.getScheduler().runAtFixedRate(plugin, task -> {
            if (!ball.isValid()) { // 着弾して消えた
                task.cancel();
                return;
            }
            int t = tick.incrementAndGet();
            Location loc = ball.getLocation();
            if (t >= LIFETIME_TICKS) { // 時間切れ: 爆発はせず消える
                ball.remove();
                task.cancel();
                return;
            }
            try {
                LivingEntity cur = target[0];
                if (cur != null && (!cur.isValid() || cur.getWorld() != loc.getWorld()
                        || cur.getBoundingBox().getCenter().distance(loc.toVector()) > HOMING_RANGE * 1.5)) {
                    cur = null;
                }
                if (cur == null && t % 5 == 0) cur = findTarget(plugin, ball, loc);
                target[0] = cur;
                if (cur != null) {
                    Vector want = cur.getBoundingBox().getCenter().subtract(loc.toVector());
                    if (want.lengthSquared() > 1.0E-6) {
                        heading.multiply(1 - TURN_RATE).add(want.normalize().multiply(TURN_RATE)).normalize();
                    }
                }
            } catch (IllegalStateException ex) {
                plugin.threadNote("彼方への呼びかけの追尾", ex);
                target[0] = null; // Folia: 別リージョンの相手には触れない。まっすぐ進む
            }
            ball.setVelocity(heading.clone().multiply(SPEED)); // 空気抵抗で遅くならないよう毎tick速度を戻す
        }, null, 1L, 1L);
    }

    private LivingEntity findTarget(TukuyomiWeapons plugin, Snowball ball, Location loc) {
        Object shooter = ball.getShooter();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity le : plugin.nearbyLiving(loc, HOMING_RANGE)) {
            if (le.equals(shooter) || le instanceof ArmorStand) continue;
            if (le instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR) continue;
            Vector to = le.getBoundingBox().getCenter().subtract(loc.toVector());
            double dist = to.length();
            if (dist > HOMING_RANGE || dist >= bestDist || dist < 1.0E-4) continue;
            if (loc.getWorld().rayTraceBlocks(loc, to.clone().normalize(), dist, FluidCollisionMode.NEVER, true) != null) continue;
            best = le;
            bestDist = dist;
        }
        return best;
    }
}
