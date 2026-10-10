package trigger.tukuyomiWeapons;
import org.bukkit.event.player.PlayerInteractEvent;
public final class CaneSheathedHandler implements WeaponHandler {
    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) { plugin.transformWeapon(event.getPlayer(), Weapon.CANE_DRAWN); }
}
