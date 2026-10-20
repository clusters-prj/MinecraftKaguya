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

/** 共鳴震(ウルト): 半径30ブロック内のプレイヤーとモブ(自分以外)に、ウィザー状態を15秒、吐き気を10秒与える。 */
public final class ResonanceQuakeHandler implements WeaponHandler {
    static final double RADIUS = 30;
    static final int WITHER_TICKS = 15 * 20;
    static final int WITHER_AMPLIFIER = 0;   // ウィザーI
    static final int NAUSEA_TICKS = 10 * 20;

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.ult.consumeCharge(p)) return;

        Location center = p.getLocation();
        center.getWorld().playSound(center, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 3f, 0.6f);
        center.getWorld().spawnParticle(Particle.SOUL, center.clone().add(0, 1, 0), 80, RADIUS / 8, 1, RADIUS / 8, 0.03);

        for (LivingEntity le : plugin.nearbyLiving(center, RADIUS)) {
            if (le.equals(p) || le instanceof ArmorStand) continue;
            if (le instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR) continue;
            if (le.getLocation().distanceSquared(center) > RADIUS * RADIUS) continue;
            plugin.onEntity(le, () -> {
                if (!plugin.canAffect(p, le)) return;
                plugin.giveEffect(le, new PotionEffect(PotionEffectType.WITHER, WITHER_TICKS, WITHER_AMPLIFIER), p);
                plugin.giveEffect(le, new PotionEffect(PotionEffectType.NAUSEA, NAUSEA_TICKS, 0));
            });
        }
    }
}
