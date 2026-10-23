package trigger.tukuyomiWeapons;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;

/** 唐傘弾(ウルト): 発動後に最初に放つ矢が、着弾点で半径20ブロック・30ダメージの爆発を起こす。 */
public final class KarakasaHandler implements WeaponHandler {
    static final double KARAKASA_RADIUS = 20;
    static final double KARAKASA_DAMAGE = 30;

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.ult.consumeCharge(p)) return;
        plugin.ult.armKarakasa(p);
    }

    /** 唐傘弾の矢が着弾したときの爆発。ブロックは壊さない */
    static void blast(TukuyomiWeapons plugin, AbstractArrow arrow) {
        Location center = arrow.getLocation();
        World world = center.getWorld();
        Object shooter = arrow.getShooter();

        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.8f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 3, 2, 1, 2, 0);

        for (LivingEntity le : plugin.nearbyLiving(center, KARAKASA_RADIUS)) {
            if (le instanceof ArmorStand || le.equals(shooter)) continue;
            if (le.getBoundingBox().getCenter().distance(center.toVector()) > KARAKASA_RADIUS) continue;
            plugin.damageBy(le, KARAKASA_DAMAGE, shooter);
        }
    }
}
