package trigger.tukuyomiWeapons;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;

/**
 * 光の剣(ウルト): スーパーノヴァの強化版。射程と衝撃波の半径が2倍、チャージとクールタイムが1.5倍。
 * ダメージは60(スーパーノヴァは50)。発射後の硬直(5秒)は同じ。
 */
public final class LightSwordUltHandler extends ChargeWeaponHandler {
    private static final int CHARGE_TICKS = 90;      // 60 の1.5倍(4.5秒)
    private static final double RANGE = 140;         // 70 の2倍
    private static final double HIT_RADIUS = 2.0;    // 衝撃波の半径 1.0 の2倍
    private static final double DAMAGE = 60;
    private static final int COOLDOWN_TICKS = 600;   // 400 の1.5倍(30秒)

    @Override protected Weapon weapon() { return Weapon.LIGHT_SWORD_ULT; }
    @Override protected int chargeTicks() { return CHARGE_TICKS; }
    @Override protected double range() { return RANGE; }
    @Override protected double hitRadius() { return HIT_RADIUS; }
    @Override protected double damage() { return DAMAGE; }
    @Override protected int cooldownTicks() { return COOLDOWN_TICKS; }
    @Override protected boolean pierceWalls() { return true; }
    @Override protected boolean piercesArmor() { return true; }
    @Override protected float chargePitch() { return 0.8f; }
    @Override protected float shockwavePitch() { return 0.8f; }
    @Override protected Map<UUID, Long> cooldownMap(TukuyomiWeapons plugin) { return plugin.laserUltCooldown; }

    /** チャージを始めるときに、キルのチャージを消費する */
    @Override protected boolean tryActivate(TukuyomiWeapons plugin, Player player) {
        return plugin.ult.consumeCharge(player);
    }

    @Override protected void applyChargeEffect(TukuyomiWeapons plugin, Player player, int ticks) {
        plugin.giveEffect(player, new PotionEffect(PotionEffectType.SLOWNESS, ticks, 3));
        plugin.giveEffect(player, new PotionEffect(PotionEffectType.RESISTANCE, ticks, 2));
    }
    @Override protected void removeChargeEffect(TukuyomiWeapons plugin, Player player) {
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.RESISTANCE);
    }
    @Override protected void afterFire(TukuyomiWeapons plugin, Player player) {
        plugin.stiffen(player, LightSwordHandler.STIFF_TICKS);
        plugin.startCooldown(player, plugin.laserUltCooldown, COOLDOWN_TICKS);
    }
    @Override protected void onChargeTick(TukuyomiWeapons plugin, Player player, int tick) {
        // スーパーノヴァの 1・21・41 tick(60 tick 中)に合わせて、90 tick 中の 1・31・61 tick で送る
        if (tick == 1) LightSwordHandler.broadcastSequence(plugin, player, 0, "光の剣、起動。発射シーケンスへ移行します");
        else if (tick == 31) LightSwordHandler.broadcastSequence(plugin, player, 1, null);
        else if (tick == 61) LightSwordHandler.broadcastSequence(plugin, player, 2, null);
    }
}
