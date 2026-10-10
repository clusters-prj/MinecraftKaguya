package weaponplus.example.tukuyomiWeapons;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
public final class PileHammerHandler implements WeaponHandler {
    static final int PILE_STIFF_TICKS = 60;
    static final double PILE_STRONG_RATIO = 0.848;
    private static final double PILE_WEAK_DAMAGE = 1.0;
    static final int PILE_ARMOR_DAMAGE = 400;

 @Override public void onMeleeDamage(TukuyomiWeapons plugin, EntityDamageByEntityEvent event, Player attacker) {
  boolean strong = event.getDamage() >= Weapon.PILE_HAMMER.damage * PILE_STRONG_RATIO;
  if (strong) {
   plugin.stiffen(attacker, PILE_STIFF_TICKS);
   attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.5f);
   if (event.getEntity() instanceof LivingEntity target) plugin.damageArmor(target, PILE_ARMOR_DAMAGE);
  } else event.setDamage(PILE_WEAK_DAMAGE);
 }
}
