package trigger.tukuyomiWeapons;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
public final class CaneDrawnHandler implements WeaponHandler {
    private static final double CANE_SLASH_RANGE = 4.5;
    private static final double CANE_SLASH_ANGLE = 30;
    private static final double CANE_SLASH_DAMAGE = 6;
    private static final int CANE_SLASH_INTERVAL_TICKS = 12;

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) { plugin.transformWeapon(event.getPlayer(), Weapon.CANE_SHEATHED); }
    @Override public void onMeleeDamage(TukuyomiWeapons plugin, EntityDamageByEntityEvent event, Player attacker) { plugin.slash(attacker, event.getEntity(), CANE_SLASH_RANGE, CANE_SLASH_ANGLE, CANE_SLASH_DAMAGE, CANE_SLASH_INTERVAL_TICKS); }
    @Override public void onLeftClick(TukuyomiWeapons plugin, Player player, org.bukkit.entity.Entity exclude) { plugin.slash(player, exclude, CANE_SLASH_RANGE, CANE_SLASH_ANGLE, CANE_SLASH_DAMAGE, CANE_SLASH_INTERVAL_TICKS); }
}
