package trigger.tukuyomiWeapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * パイルバンカー(ウルト): 近接武器。右クリックの発動はなく、最初から構えた状態。
 * ウルトのチャージが満タン(5キル)のときだけ、攻撃が100ダメージになり、チャージを消費する。
 * 攻撃したあとは、スーパーノヴァと同じ硬直を受ける。
 * チャージがないときは、USABLE_WITHOUT_CHARGE が true なら攻撃力1の弱い攻撃、false なら攻撃できない。
 */
public final class PileBunkerHandler implements WeaponHandler {
    static final double BUNKER_DAMAGE = 100;
    static final boolean USABLE_WITHOUT_CHARGE = true;

    @Override public void onMeleeDamage(TukuyomiWeapons plugin, EntityDamageByEntityEvent event, Player attacker) {
        if (!plugin.ult.isFull(attacker)) {
            if (!USABLE_WITHOUT_CHARGE) { // 使用不能: 攻撃をなかったことにする
                event.setCancelled(true);
                attacker.sendActionBar(Component.text("パイルバンカー: ウルトのチャージが足りない", NamedTextColor.RED));
            }
            return; // true のときは、攻撃力1の弱い攻撃のまま
        }
        event.setDamage(BUNKER_DAMAGE);
        plugin.stiffen(attacker, LightSwordHandler.STIFF_TICKS);
        plugin.onEntity(attacker, () -> plugin.ult.consumeCharge(attacker)); // チャージの消費は、攻撃した本人のスレッドで(Folia 対応)
        attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1.5f, 0.4f);
        attacker.getWorld().playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_LAND, 1f, 0.6f);
    }
}
