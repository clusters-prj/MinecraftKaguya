package trigger.tukuyomiWeapons;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public final class BeastCleaverDrawnHandler implements WeaponHandler {
    private static final double BEAST_DRAWN_SLASH_RANGE = 7;
    private static final double BEAST_DRAWN_SLASH_ANGLE = 30;
    private static final double BEAST_DRAWN_SLASH_DAMAGE = 6;
    private static final int BEAST_DRAWN_SLASH_INTERVAL_TICKS = 40;
    private static final float BEAST_DRAWN_STRONG_THRESHOLD = 0.9f;

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) { plugin.transformWeapon(event.getPlayer(), Weapon.BEAST_CLEAVER); }

    @Override public void onLeftClick(TukuyomiWeapons plugin, Player player, org.bukkit.entity.Entity exclude) {
        if (player.getAttackCooldown() >= BEAST_DRAWN_STRONG_THRESHOLD) plugin.slash(player, exclude, BEAST_DRAWN_SLASH_RANGE, BEAST_DRAWN_SLASH_ANGLE, BEAST_DRAWN_SLASH_DAMAGE, BEAST_DRAWN_SLASH_INTERVAL_TICKS);
    }
    @Override public void onMeleeDamage(TukuyomiWeapons plugin, EntityDamageByEntityEvent event, Player attacker) {
        if (attacker.getAttackCooldown() >= BEAST_DRAWN_STRONG_THRESHOLD) plugin.slash(attacker, event.getEntity(), BEAST_DRAWN_SLASH_RANGE, BEAST_DRAWN_SLASH_ANGLE, BEAST_DRAWN_SLASH_DAMAGE, BEAST_DRAWN_SLASH_INTERVAL_TICKS);
    }
}
