package trigger.tukuyomiWeapons;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;

/** 神の怒り(ウルト): 拒絶の効果範囲と吹き飛ばしの強さをそれぞれ2倍にし、範囲内に10ダメージを与える。 */
public final class WrathOfGodHandler implements WeaponHandler {
    static final double WRATH_DAMAGE = 10;

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.ult.consumeCharge(p)) return;
        RejectionHandler.burst(plugin, p,
                RejectionHandler.REJECT_RADIUS * 2,
                RejectionHandler.REJECT_PUSH_SPEED * 2,
                WRATH_DAMAGE);
    }
}
