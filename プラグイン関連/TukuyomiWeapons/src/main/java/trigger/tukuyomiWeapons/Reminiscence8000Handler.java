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

/** 8000年の追憶「弱」(ウルト): 半径30ブロック内のプレイヤーとモブ(自分以外)に、暗闇を10秒与える。 */
public final class Reminiscence8000Handler implements WeaponHandler {
    static final double RADIUS = 30;
    static final int DARKNESS_TICKS = 10 * 20;

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.ult.consumeCharge(p)) return;

        Location center = p.getLocation();
        center.getWorld().playSound(center, Sound.ENTITY_WARDEN_ROAR, 2f, 0.5f);
        center.getWorld().spawnParticle(Particle.SCULK_SOUL, center.clone().add(0, 1, 0), 60, RADIUS / 10, 1, RADIUS / 10, 0.03);

        for (LivingEntity le : plugin.nearbyLiving(center, RADIUS)) {
            if (le.equals(p) || le instanceof ArmorStand) continue;
            if (le instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR) continue;
            if (le.getLocation().distanceSquared(center) > RADIUS * RADIUS) continue;
            plugin.onEntity(le, () -> {
                if (!plugin.canAffect(p, le)) return;
                plugin.giveEffect(le, new PotionEffect(PotionEffectType.DARKNESS, DARKNESS_TICKS, 0));
            });
        }
    }
}
