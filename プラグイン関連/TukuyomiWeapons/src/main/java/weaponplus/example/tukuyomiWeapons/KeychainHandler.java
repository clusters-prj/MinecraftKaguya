package weaponplus.example.tukuyomiWeapons;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** 光の剣に付随するキーホルダーと、預かりアイテムの管理。 */
public final class KeychainHandler implements WeaponHandler {

    private boolean isKeychain(TukuyomiWeapons plugin, ItemStack item) {
        return item != null && plugin.getWeapon(item) == Weapon.KEYCHAIN;
    }

    private ItemStack createKeychain(TukuyomiWeapons plugin, ItemStack stored) {
        ItemStack key = plugin.createItem(Weapon.KEYCHAIN);
        ItemMeta meta = key.getItemMeta();
        meta.setMaxStackSize(1);
        if (stored != null && !stored.getType().isAir()) {
            meta.getPersistentDataContainer().set(plugin.keychainDataKey, PersistentDataType.BYTE_ARRAY, stored.serializeAsBytes());
        }
        key.setItemMeta(meta);
        return key;
    }

    private ItemStack storedOf(TukuyomiWeapons plugin, ItemStack key) {
        if (key == null || !key.hasItemMeta()) return null;
        byte[] data = key.getItemMeta().getPersistentDataContainer()
                .get(plugin.keychainDataKey, PersistentDataType.BYTE_ARRAY);
        return data == null ? null : ItemStack.deserializeBytes(data);
    }

    private void tickKeychain(TukuyomiWeapons plugin, Player p) {
        PlayerInventory inv = p.getInventory();
        boolean holdingLight = plugin.getWeapon(inv.getItemInMainHand()) == Weapon.LIGHT_SWORD;
        ItemStack off = inv.getItemInOffHand();
        boolean offIsKey = isKeychain(plugin, off);
        if (holdingLight && !offIsKey) {
            recoverLostOffhand(plugin, p);
            ItemStack current = inv.getItemInOffHand();
            inv.setItemInOffHand(createKeychain(plugin, current));
            saveOffhandBackup(plugin, p, current);
        } else if (!holdingLight && offIsKey) {
            restoreKeychain(plugin, p);
        } else if (!holdingLight) {
            recoverLostOffhand(plugin, p);
        }
    }

    private void restoreKeychain(TukuyomiWeapons plugin, Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack off = inv.getItemInOffHand();
        if (!isKeychain(plugin, off)) return;
        ItemStack stored = storedOf(plugin, off);
        if (stored == null) stored = loadOffhandBackup(plugin, p);
        p.getPersistentDataContainer().remove(plugin.offhandBackupKey);
        inv.setItemInOffHand(stored != null ? stored : new ItemStack(Material.AIR));
    }

    private void saveOffhandBackup(TukuyomiWeapons plugin, Player p, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            p.getPersistentDataContainer().remove(plugin.offhandBackupKey);
        } else {
            p.getPersistentDataContainer().set(plugin.offhandBackupKey, PersistentDataType.BYTE_ARRAY, item.serializeAsBytes());
        }
    }

    private ItemStack loadOffhandBackup(TukuyomiWeapons plugin, Player p) {
        byte[] data = p.getPersistentDataContainer().get(plugin.offhandBackupKey, PersistentDataType.BYTE_ARRAY);
        return data == null ? null : ItemStack.deserializeBytes(data);
    }

    private void recoverLostOffhand(TukuyomiWeapons plugin, Player p) {
        if (!p.getPersistentDataContainer().has(plugin.offhandBackupKey, PersistentDataType.BYTE_ARRAY)) return;
        PlayerInventory inv = p.getInventory();
        if (isKeychain(plugin, inv.getItemInOffHand())) return;
        for (ItemStack it : inv.getStorageContents()) {
            if (isKeychain(plugin, it)) return;
        }
        ItemStack stored = loadOffhandBackup(plugin, p);
        p.getPersistentDataContainer().remove(plugin.offhandBackupKey);
        if (stored != null) {
            inv.addItem(stored).values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
        }
    }

    private void scrubStrayKeychains(TukuyomiWeapons plugin, Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        ItemStack backup = loadOffhandBackup(plugin, p);
        boolean removedAny = false;
        boolean restoredBackup = false;

        // 通常インベントリに残ったキーホルダーを掃除する。
        // キーホルダー内の保存データがない場合は、プレイヤー側のバックアップも使う。
        for (int i = 0; i < contents.length; i++) {
            if (!isKeychain(plugin, contents[i])) continue;

            ItemStack stored = storedOf(plugin, contents[i]);
            if (stored == null && backup != null && !restoredBackup) {
                stored = backup;
                restoredBackup = true;
            }
            inv.setItem(i, null);
            removedAny = true;

            if (stored != null && !stored.getType().isAir()) {
                inv.addItem(stored).values().forEach(rest ->
                        p.getWorld().dropItemNaturally(p.getLocation(), rest));
            }
        }

        // オフハンドも確認する。光の剣を持っている場合のキーホルダーは正常状態なので残す。
        if (isKeychain(plugin, inv.getItemInOffHand())
                && plugin.getWeapon(inv.getItemInMainHand()) != Weapon.LIGHT_SWORD) {
            ItemStack keychain = inv.getItemInOffHand();
            ItemStack stored = storedOf(plugin, keychain);
            if (stored == null && backup != null && !restoredBackup) {
                stored = backup;
                restoredBackup = true;
            }
            inv.setItemInOffHand(new ItemStack(Material.AIR));
            removedAny = true;

            if (stored != null && !stored.getType().isAir()) {
                inv.addItem(stored).values().forEach(rest ->
                        p.getWorld().dropItemNaturally(p.getLocation(), rest));
            }
        }

        // バックアップは掃除後に一度だけ削除し、保存アイテムの二重復元を避ける。
        if (removedAny) {
            p.getPersistentDataContainer().remove(plugin.offhandBackupKey);
        }
    }

    private void restoreOffhandOnJoin(TukuyomiWeapons plugin, Player p) {
        PlayerInventory inv = p.getInventory();
        if (isKeychain(plugin, inv.getItemInOffHand()) && plugin.getWeapon(inv.getItemInMainHand()) != Weapon.LIGHT_SWORD) {
            restoreKeychain(plugin, p);
        }
    }

    @Override public void onPlayerTick(TukuyomiWeapons plugin, Player player) {
        tickKeychain(plugin, player);
    }

    @Override public void onJoin(TukuyomiWeapons plugin, Player player) {
        scrubStrayKeychains(plugin, player);
        restoreOffhandOnJoin(plugin, player);
    }

    @Override public void onInventoryClick(TukuyomiWeapons plugin, InventoryClickEvent e) {
        if (isKeychain(plugin, e.getCurrentItem()) || isKeychain(plugin, e.getCursor())) {
            e.setCancelled(true);
            return;
        }
        if (e.getClick() == ClickType.SWAP_OFFHAND && e.getWhoClicked() instanceof Player pl
                && isKeychain(plugin, pl.getInventory().getItemInOffHand())) e.setCancelled(true);
    }

    @Override public void onSwapHands(TukuyomiWeapons plugin, PlayerSwapHandItemsEvent e) {
        if (isKeychain(plugin, e.getMainHandItem()) || isKeychain(plugin, e.getOffHandItem())) e.setCancelled(true);
    }

    @Override public void onDropItem(TukuyomiWeapons plugin, PlayerDropItemEvent e) {
        if (isKeychain(plugin, e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @Override public void onDeath(TukuyomiWeapons plugin, PlayerDeathEvent e) {
        Player dead = e.getEntity();
        if (e.getKeepInventory()) return;

        ItemStack backup = loadOffhandBackup(plugin, dead);
        dead.getPersistentDataContainer().remove(plugin.offhandBackupKey);
        boolean foundKeychain = false;
        List<ItemStack> returned = new ArrayList<>();
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack drop = it.next();
            if (!isKeychain(plugin, drop)) continue;
            it.remove();
            foundKeychain = true;
            ItemStack stored = storedOf(plugin, drop);
            if (stored == null) stored = backup;
            if (stored != null) returned.add(stored);
        }
        if (!foundKeychain && backup != null) returned.add(backup);
        e.getDrops().addAll(returned);
    }

    void restoreOnDisable(TukuyomiWeapons plugin, Player player) {
        restoreKeychain(plugin, player);
    }

    void restoreOnSpectator(TukuyomiWeapons plugin, Player player) {
        restoreKeychain(plugin, player);
    }
}
