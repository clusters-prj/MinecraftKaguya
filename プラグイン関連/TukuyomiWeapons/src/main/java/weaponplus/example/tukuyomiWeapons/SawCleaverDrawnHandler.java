package weaponplus.example.tukuyomiWeapons;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
public final class SawCleaverDrawnHandler implements WeaponHandler {
    static final int SAW_POISON_TICKS = 200;
    static final int SAW_POISON_AMPLIFIER = 1;

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {
        plugin.transformWeapon(event.getPlayer(), Weapon.SAW_CLEAVER);
    }
    @Override public void onMeleeDamage(TukuyomiWeapons plugin, EntityDamageByEntityEvent event, Player attacker) {
        if (event.getEntity() instanceof LivingEntity victim) {
            plugin.giveEffect(victim, new PotionEffect(PotionEffectType.POISON,
                    SAW_POISON_TICKS, SAW_POISON_AMPLIFIER));
        }
    }
}
