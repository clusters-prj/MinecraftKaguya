package trigger.tukuyomiWeapons;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;

public final class RejectionHandler implements WeaponHandler {
    static final double REJECT_RADIUS = 5;
    static final double REJECT_PUSH_SPEED = 2.0; // 吹き飛ばしの水平の強さ
    static final int REJECT_COOLDOWN_TICKS = 200;

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) { rejection(plugin, event.getPlayer()); }

    /** 拒絶の処理本体。 */
    private void rejection(TukuyomiWeapons plugin, Player p) {
        if (plugin.onCooldown(p, plugin.rejectCooldown)) return;
        plugin.startCooldown(p, plugin.rejectCooldown, REJECT_COOLDOWN_TICKS);
        burst(plugin, p, REJECT_RADIUS, REJECT_PUSH_SPEED, 0);
    }

    /**
     * 周囲を吹き飛ばす(拒絶の効果本体。神の怒りも、範囲・強さ・ダメージを変えて使う)。
     * damage が 0 より大きければ、範囲内の相手にそのダメージも与える。
     */
    static void burst(TukuyomiWeapons plugin, Player p, double radius, double pushSpeed, double damage) {
        Location center = p.getLocation();
        World world = center.getWorld();
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.4f);
        world.spawnParticle(Particle.EXPLOSION, center.clone().add(0, 1, 0),
                Math.max(3, (int) Math.round(3 * radius / REJECT_RADIUS)), radius * 0.3, 0.5, radius * 0.3, 0);

        for (LivingEntity en : plugin.nearbyLiving(center, radius)) {
            if (en.equals(p) || en instanceof ArmorStand) continue;
            if (en.getLocation().distanceSquared(center) > radius * radius) continue;

            Vector v = en.getLocation().toVector().subtract(center.toVector());
            v.setY(0);
            if (v.lengthSquared() < 1.0E-4) {
                v = new Vector(Math.random() - 0.5, 0, Math.random() - 0.5);
            }
            v.normalize().multiply(pushSpeed).setY(0.6);
            Vector push = v;
            plugin.onEntity(en, () -> {
                if (!plugin.canAffect(p, en)) return; // PvP禁止区域などでは、ダメージも吹き飛ばしもしない
                if (damage > 0) plugin.damageBy(en, damage, p);
                else plugin.ult.addCharge(p, Weapon.REJECTION, en); // 拒絶(ダメージなし)は、吹き飛ばした相手ごとに数える
                en.setVelocity(push);
            }); // 相手を扱うスレッドで動かす(Folia 対応)
        }
    }
}
