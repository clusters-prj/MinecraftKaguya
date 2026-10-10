package trigger.tukuyomiWeapons;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
public final class BeastCleaverHandler implements WeaponHandler {
    private static final double BEAST_SLASH_RANGE = 4.5;
    private static final double BEAST_SLASH_ANGLE = 60;
    private static final double BEAST_SLASH_DAMAGE = 16;
    private static final int BEAST_SLASH_INTERVAL_TICKS = 14;

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) { plugin.transformWeapon(event.getPlayer(), Weapon.BEAST_CLEAVER_DRAWN); }
    @Override public void onMeleeDamage(TukuyomiWeapons plugin, EntityDamageByEntityEvent event, Player attacker) { plugin.slash(attacker, event.getEntity(), BEAST_SLASH_RANGE, BEAST_SLASH_ANGLE, BEAST_SLASH_DAMAGE, BEAST_SLASH_INTERVAL_TICKS); }
    @Override public void onLeftClick(TukuyomiWeapons plugin, Player player, org.bukkit.entity.Entity exclude) { plugin.slash(player, exclude, BEAST_SLASH_RANGE, BEAST_SLASH_ANGLE, BEAST_SLASH_DAMAGE, BEAST_SLASH_INTERVAL_TICKS); }
}
