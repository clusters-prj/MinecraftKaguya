package trigger.tukuyomiWeapons;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** 大失態(ウルト): 半径20ブロック内のプレイヤーとモブ(自分以外)に、毒を15秒、吐き気を10秒与える。 */
public final class BigBlunderHandler implements WeaponHandler {
    static final double RADIUS = 20;
    static final int POISON_TICKS = 15 * 20;
    static final int POISON_AMPLIFIER = 0;   // 毒I
    static final int NAUSEA_TICKS = 10 * 20;

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.ult.consumeCharge(p)) return;

        Location center = p.getLocation();
        center.getWorld().playSound(center, Sound.ENTITY_WITCH_CELEBRATE, 2f, 0.6f);
        center.getWorld().spawnParticle(Particle.WITCH, center.clone().add(0, 1, 0), 80, RADIUS / 6, 1, RADIUS / 6, 0.05);

        for (LivingEntity le : plugin.nearbyLiving(center, RADIUS)) {
            if (le.equals(p) || le instanceof ArmorStand) continue;
            if (le instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR) continue;
            if (le.getLocation().distanceSquared(center) > RADIUS * RADIUS) continue;
            plugin.onEntity(le, () -> {
                if (!plugin.canAffect(p, le)) return;
                plugin.giveEffect(le, new PotionEffect(PotionEffectType.POISON, POISON_TICKS, POISON_AMPLIFIER), p);
                plugin.giveEffect(le, new PotionEffect(PotionEffectType.NAUSEA, NAUSEA_TICKS, 0));
            });
        }
    }
}
