package trigger.tukuyomiWeapons;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
public final class PileHammerHandler implements WeaponHandler {
    @Override public boolean countsMeleeForUlt() { return false; } // 強攻撃・弱攻撃とも、onMeleeDamage の中で数える
    static final int PILE_STIFF_TICKS = 60;
    static final double PILE_STRONG_RATIO = 0.848;
    private static final double PILE_WEAK_DAMAGE = 1.0;
    static final int PILE_ARMOR_DAMAGE = 200;

 @Override public void onMeleeDamage(TukuyomiWeapons plugin, EntityDamageByEntityEvent event, Player attacker) {
  boolean strong = event.getDamage() >= Weapon.PILE_HAMMER.damage * PILE_STRONG_RATIO;
  if (strong) {
   plugin.stiffen(attacker, PILE_STIFF_TICKS);
   attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.5f);
   if (event.getEntity() instanceof LivingEntity target) {
    plugin.damageArmor(target, PILE_ARMOR_DAMAGE);
    plugin.ult.addCharge(attacker, Weapon.PILE_HAMMER, target); // 強攻撃: 20%
   }
  } else {
   event.setDamage(PILE_WEAK_DAMAGE);
   if (event.getEntity() instanceof LivingEntity target) plugin.ult.addChargeFor(attacker, UltimateManager.PILE_HAMMER_WEAK_CHARGE, target); // 弱攻撃は少しだけ
  }
 }
}
