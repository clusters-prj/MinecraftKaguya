package weaponplus.example.tukuyomiWeapons;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

public final class HuntingHornHandler implements WeaponHandler {
    private static final int HORN_EFFECT_TICKS = 300;
    private static final boolean HORN_NEEDS_MAIN_HAND = true;
    private static final boolean HORN_CLEAR_ON_UNEQUIP = false;

    @Override public void onTick(TukuyomiWeapons plugin, Player player) { tickHorn(plugin, player); }

private void tickHorn(TukuyomiWeapons plugin, Player p) {
    boolean has = plugin.getWeapon(p.getInventory().getItemInMainHand()) == Weapon.HUNTING_HORN;
    if (!has && !HORN_NEEDS_MAIN_HAND) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (plugin.getWeapon(it) == Weapon.HUNTING_HORN) {
                has = true;
                break;
            }
        }
    }
    if (has) {
        // 1秒ごとに 15秒へ更新する。持っている間は切れない
        plugin.giveEffect(p, new PotionEffect(PotionEffectType.STRENGTH, HORN_EFFECT_TICKS, 0, false, false, true));
        plugin.giveEffect(p, new PotionEffect(PotionEffectType.SPEED, HORN_EFFECT_TICKS, 0, false, false, true));
        plugin.hornHolders.add(p.getUniqueId());
    } else if (plugin.hornHolders.remove(p.getUniqueId()) && HORN_CLEAR_ON_UNEQUIP) {
        p.removePotionEffect(PotionEffectType.STRENGTH);
        p.removePotionEffect(PotionEffectType.SPEED);
    }
}
}
