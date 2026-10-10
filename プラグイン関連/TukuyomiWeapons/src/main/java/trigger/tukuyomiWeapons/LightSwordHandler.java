package trigger.tukuyomiWeapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;

/** 光の剣：3秒チャージ後に射程70ブロックの貫通衝撃波を発射。 */
public final class LightSwordHandler extends ChargeWeaponHandler {
    private static final int CHARGE_TICKS = 60;
    private static final int STIFF_TICKS = 100;
    private static final double RANGE = 70;
    private static final double DAMAGE = 50;
    private static final int COOLDOWN_TICKS = 400;
    private static final double MESSAGE_RADIUS = 70;
    private static final String[] WARNING_LINES = {
            "!! WARNING !!   WARNING !!   WARNING !!",
            "超高出力レールガン 充填中",
            "射線上の全生命体は 直ちに退避せよ",
            "ターゲット捕捉 ロック完了",
            "!! WARNING !!   WARNING !!   WARNING !!"
    };

    @Override protected Weapon weapon() { return Weapon.LIGHT_SWORD; }
    @Override protected int chargeTicks() { return CHARGE_TICKS; }
    @Override protected double range() { return RANGE; }
    @Override protected double damage() { return DAMAGE; }
    @Override protected int cooldownTicks() { return COOLDOWN_TICKS; }
    @Override protected boolean pierceWalls() { return true; }
    @Override protected float chargePitch() { return 1f; }
    @Override protected float shockwavePitch() { return 1f; }

    @Override protected void applyChargeEffect(TukuyomiWeapons plugin, Player player, int ticks) {
        plugin.giveEffect(player, new PotionEffect(PotionEffectType.SLOWNESS, ticks, 3));
        plugin.giveEffect(player, new PotionEffect(PotionEffectType.RESISTANCE, ticks, 2));
    }
    @Override protected void removeChargeEffect(TukuyomiWeapons plugin, Player player) {
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.RESISTANCE);
    }
    @Override protected void afterFire(TukuyomiWeapons plugin, Player player) {
        plugin.stiffen(player, STIFF_TICKS);
        plugin.startCooldown(player, plugin.laserCooldown, cooldownTicks());
    }
    @Override protected void onChargeTick(TukuyomiWeapons plugin, Player player, int tick) {
        sendRailgunSequence(plugin, player, tick);
    }

    private void sendRailgunSequence(TukuyomiWeapons plugin, Player p, int tick) {
        Component msg;
        if (tick == 1) {
            msg = Component.text("光の剣コピー起動、発射シーケンスへ移行します", NamedTextColor.AQUA);
        } else if (tick == 21) {
            Component warning = Component.text("[RAILGUN WARNING]", NamedTextColor.RED, TextDecoration.BOLD);
            for (String line : WARNING_LINES) warning = warning.append(Component.newline()).append(Component.text(line, NamedTextColor.RED));
            msg = warning;
        } else if (tick == 41) {
            msg = Component.text("光よ！", NamedTextColor.YELLOW);
        } else return;

        Set<Player> receivers = new HashSet<>();
        receivers.add(p);
        if (MESSAGE_RADIUS < 0) {
            receivers.addAll(Bukkit.getOnlinePlayers());
        } else if (MESSAGE_RADIUS > 0) {
            try {
                double r = MESSAGE_RADIUS;
                for (Entity en : p.getNearbyEntities(r, r, r)) {
                    if (en instanceof Player pl && pl.getLocation().distanceSquared(p.getLocation()) <= r * r) receivers.add(pl);
                }
            } catch (IllegalStateException ex) {
                plugin.threadNote("チャットの送信先", ex);
            }
        }
        for (Player pl : receivers) pl.sendMessage(msg);
    }
}
