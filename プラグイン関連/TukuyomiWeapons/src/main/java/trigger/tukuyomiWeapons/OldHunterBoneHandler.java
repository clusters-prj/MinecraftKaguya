package trigger.tukuyomiWeapons;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;

/** 古狩人の遺骨(ウルト): 発動後16秒間、ジャンプと同時に、移動入力の向き(WASD。W+D なら右前45度)へ突進する。キー入力が取れないときは、直前の移動の向き。 */
public final class OldHunterBoneHandler implements WeaponHandler {
    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.ult.consumeCharge(p)) return;
        plugin.ult.startBone(p);
    }
}
