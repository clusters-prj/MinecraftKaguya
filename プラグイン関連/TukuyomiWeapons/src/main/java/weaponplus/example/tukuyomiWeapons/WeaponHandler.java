package weaponplus.example.tukuyomiWeapons;

import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/** 武器ごとのイベント処理を分離するための共通インターフェース。 */
interface WeaponHandler {
    default void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {}
    default void onMeleeDamage(TukuyomiWeapons plugin, EntityDamageByEntityEvent event, Player attacker) {}
    default void onProjectileLaunch(TukuyomiWeapons plugin, ProjectileLaunchEvent event, Snowball projectile) {}
    default void onProjectileHit(TukuyomiWeapons plugin, ProjectileHitEvent event, Snowball projectile) {}
    default void onLeftClick(TukuyomiWeapons plugin, Player player, org.bukkit.entity.Entity exclude) {}
    default void onTick(TukuyomiWeapons plugin, Player player) {}
    default void onPlayerTick(TukuyomiWeapons plugin, Player player) {}
    default void onJoin(TukuyomiWeapons plugin, Player player) {}
    default void onDeath(TukuyomiWeapons plugin, org.bukkit.event.entity.PlayerDeathEvent event) {}
    default void onInventoryClick(TukuyomiWeapons plugin, org.bukkit.event.inventory.InventoryClickEvent event) {}
    default void onSwapHands(TukuyomiWeapons plugin, org.bukkit.event.player.PlayerSwapHandItemsEvent event) {}
    default void onDropItem(TukuyomiWeapons plugin, org.bukkit.event.player.PlayerDropItemEvent event) {}
}
