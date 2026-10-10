package weaponplus.example.tukuyomiWeapons;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;
public final class RejectionHandler implements WeaponHandler {
    static final double REJECT_RADIUS = 5;
    static final int REJECT_COOLDOWN_TICKS = 200;

 @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) { rejection(plugin, event.getPlayer()); }
 /** 拒絶の処理本体。 */
 private void rejection(TukuyomiWeapons plugin, Player p) {

        if (plugin.onCooldown(p, plugin.rejectCooldown)) return;
        plugin.startCooldown(p, plugin.rejectCooldown, REJECT_COOLDOWN_TICKS);

        Location center = p.getLocation();
        World world = p.getWorld();
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.4f);
        world.spawnParticle(Particle.EXPLOSION, center.clone().add(0, 1, 0), 3, 1.5, 0.5, 1.5, 0);

        for (Entity en : p.getNearbyEntities(REJECT_RADIUS, REJECT_RADIUS, REJECT_RADIUS)) {
            if (!(en instanceof LivingEntity) || en instanceof ArmorStand) continue;
            if (en.getLocation().distanceSquared(center) > REJECT_RADIUS * REJECT_RADIUS) continue;

            Vector v = en.getLocation().toVector().subtract(center.toVector());
            v.setY(0);
            if (v.lengthSquared() < 1.0E-4) {
                v = new Vector(Math.random() - 0.5, 0, Math.random() - 0.5);
            }
            v.normalize().multiply(2.0).setY(0.6);
            Vector push = v;
            plugin.onEntity(en, () -> {
                if (plugin.canAffect(p, en)) en.setVelocity(push); // PvP禁止区域などでは、吹き飛ばさない
            }); // 相手を扱うスレッドで動かす(Folia 対応)
        }
    
 }
}
