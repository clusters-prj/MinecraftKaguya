package weaponplus.example.tukuyomiWeapons;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** ギター：0.5秒チャージ後に射程15ブロックの非貫通衝撃波を発射。 */
public final class GuitarHandler extends ChargeWeaponHandler {
    private static final int CHARGE_TICKS = 10;
    private static final int SLOWNESS_AMPLIFIER = 2;
    private static final double RANGE = 15;
    private static final double DAMAGE = 24;
    private static final int COOLDOWN_TICKS = 120;

    @Override protected Weapon weapon() { return Weapon.GUITAR; }
    @Override protected int chargeTicks() { return CHARGE_TICKS; }
    @Override protected double range() { return RANGE; }
    @Override protected double damage() { return DAMAGE; }
    @Override protected int cooldownTicks() { return COOLDOWN_TICKS; }
    @Override protected boolean pierceWalls() { return false; }
    @Override protected float chargePitch() { return 1.4f; }
    @Override protected float shockwavePitch() { return 1.3f; }

    @Override protected void applyChargeEffect(TukuyomiWeapons plugin, Player player, int ticks) {
        plugin.giveEffect(player, new PotionEffect(PotionEffectType.SLOWNESS, ticks, SLOWNESS_AMPLIFIER));
    }
    @Override protected void removeChargeEffect(TukuyomiWeapons plugin, Player player) {
        player.removePotionEffect(PotionEffectType.SLOWNESS);
    }
    @Override protected void afterFire(TukuyomiWeapons plugin, Player player) {
        plugin.startCooldown(player, plugin.guitarCooldown, cooldownTicks());
    }
}
