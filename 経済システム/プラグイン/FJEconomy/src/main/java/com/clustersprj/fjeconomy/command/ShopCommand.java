package com.clustersprj.fjeconomy.command;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.shop.ShopManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ShopCommand implements CommandExecutor, TabCompleter {

    private final FJEconomy plugin;
    private final ShopManager shopManager;

    public ShopCommand(FJEconomy plugin, ShopManager shopManager) {
        this.plugin = plugin;
        this.shopManager = shopManager;
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, Command command,
                            String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>/shop <subcommand>"));
            return false;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "create":
                return handleCreate(sender, args);
            case "delete":
                return handleDelete(sender, args);
            case "list":
                return handleList(sender, args);
            case "info":
                return handleInfo(sender, args);
            case "setprice":
                return handleSetPrice(sender, args);
            case "setstock":
                return handleSetStock(sender, args);
            case "addstock":
                return handleAddStock(sender, args);
            case "removestock":
                return handleRemoveStock(sender, args);
            default:
                sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>不明なコマンド: " + subcommand));
                return false;
        }
    }

    /**
     * /shop create <npcUuid> <item> <price> [stock]
     */
    private boolean handleCreate(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return false;
        }

        if (args.length < 4) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>使用方法: /shop create <NPC UUID> <アイテム> <価格> [在庫]"));
            return false;
        }

        Player player = (Player) sender;
        UUID npcUuid;
        String itemMaterial = args[2];
        int price;
        int stock;

        try {
            npcUuid = UUID.fromString(args[1]);
            price = Integer.parseInt(args[3]);
            stock = args.length > 4 ? Integer.parseInt(args[4]) : 
                   plugin.getConfigManager().getDefaultStock();
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>無効な数値、または無効なUUIDです"));
            return false;
        }

        if (price < 0 || stock < 0) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>価格と在庫は0以上である必要があります"));
            return false;
        }

        String serverId = plugin.getConfigManager().getServerId();

        if (shopManager.createShop(npcUuid, serverId, player.getUniqueId(), itemMaterial, price, stock)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<green>店舗を作成しました (NPC UUID: " + npcUuid + ", アイテム: " + itemMaterial +
                    ", 価格: " + plugin.getConfigManager().getCurrencySymbol() + price + ")"));
            return true;
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>店舗作成に失敗しました"));
            return false;
        }
    }

    /**
     * /shop delete <npcUuid>
     */
    private boolean handleDelete(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.shop.delete")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドを実行する権限がありません"));
            return false;
        }

        if (args.length < 2) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>使用方法: /shop delete <NPC UUID>"));
            return false;
        }

        UUID npcUuid;
        try {
            npcUuid = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>無効なNPC UUIDです"));
            return false;
        }

        String serverId = plugin.getConfigManager().getServerId();

        if (shopManager.deleteShop(npcUuid, serverId)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<green>店舗を削除しました (NPC UUID: " + npcUuid + ")"));
            return true;
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>該当する店舗が見つかりません"));
            return false;
        }
    }

    /**
     * /shop list [player]
     */
    private boolean handleList(org.bukkit.command.CommandSender sender, String[] args) {
        UUID targetUUID;
        String targetName;

        if (args.length < 2) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>プレイヤー名を指定してください"));
                return false;
            }
            Player player = (Player) sender;
            targetUUID = player.getUniqueId();
            targetName = player.getName();
        } else {
            Player targetPlayer = Bukkit.getPlayer(args[1]);
            if (targetPlayer == null) {
                sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                        "<red>プレイヤーが見つかりません"));
                return false;
            }
            targetUUID = targetPlayer.getUniqueId();
            targetName = targetPlayer.getName();
        }

        String serverId = plugin.getConfigManager().getServerId();
        List<ShopManager.Shop> shops = shopManager.getShopsByOwner(targetUUID, serverId);

        if (shops.isEmpty()) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>" + targetName + " の店舗がありません"));
            return true;
        }

        sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                "<aqua>" + targetName + " の店舗一覧:"));
        for (ShopManager.Shop shop : shops) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("  <gray>NPC UUID: " + shop.getNpcUuid() + " - " +
                    shop.getDisplayInfo(plugin.getConfigManager().getCurrencySymbol())));
        }

        return true;
    }

    /**
     * /shop info <npcUuid>
     */
    private boolean handleInfo(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>使用方法: /shop info <NPC UUID>"));
            return false;
        }

        UUID npcUuid;
        try {
            npcUuid = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>無効なNPC UUIDです"));
            return false;
        }

        String serverId = plugin.getConfigManager().getServerId();
        ShopManager.Shop shop = shopManager.getShop(npcUuid, serverId);

        if (shop == null) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>該当する店舗が見つかりません"));
            return false;
        }

        sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<aqua>店舗情報:"));
        sender.sendMessage("  NPC UUID: " + shop.getNpcUuid());
        sender.sendMessage("  アイテム: " + shop.getItemMaterial());
        sender.sendMessage("  価格: " + plugin.getConfigManager().getCurrencySymbol() + shop.getPrice());
        sender.sendMessage("  在庫: " + shop.getStock() + "個");

        return true;
    }

    /**
     * /shop setprice <npcUuid> <price>
     */
    private boolean handleSetPrice(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return false;
        }

        if (args.length < 3) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>使用方法: /shop setprice <NPC UUID> <価格>"));
            return false;
        }

        Player player = (Player) sender;
        UUID npcUuid;
        int price;

        try {
            npcUuid = UUID.fromString(args[1]);
            price = Integer.parseInt(args[2]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>無効な数値、または無効なUUIDです"));
            return false;
        }

        String serverId = plugin.getConfigManager().getServerId();
        ShopManager.Shop shop = shopManager.getShop(npcUuid, serverId);

        if (shop == null || !shop.getOwnerUUID().equals(player.getUniqueId())) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>この店舗を管理する権限がありません"));
            return false;
        }

        if (shopManager.updateShopPrice(npcUuid, serverId, price)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<green>価格を " + plugin.getConfigManager().getCurrencySymbol() + price + " に設定しました"));
            return true;
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c価格設定に失敗しました");
            return false;
        }
    }

    /**
     * /shop setstock <npcUuid> <stock>
     */
    private boolean handleSetStock(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return false;
        }

        if (args.length < 3) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>使用方法: /shop setstock <NPC UUID> <在庫>"));
            return false;
        }

        Player player = (Player) sender;
        UUID npcUuid;
        int stock;

        try {
            npcUuid = UUID.fromString(args[1]);
            stock = Integer.parseInt(args[2]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>無効な数値、または無効なUUIDです"));
            return false;
        }

        String serverId = plugin.getConfigManager().getServerId();
        ShopManager.Shop shop = shopManager.getShop(npcUuid, serverId);

        if (shop == null || !shop.getOwnerUUID().equals(player.getUniqueId())) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>この店舗を管理する権限がありません"));
            return false;
        }

        if (shopManager.updateShopStock(npcUuid, serverId, stock)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<green>在庫を " + stock + "個に設定しました"));
            return true;
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c在庫設定に失敗しました");
            return false;
        }
    }

    /**
     * /shop addstock <npcUuid> <quantity>
     */
    private boolean handleAddStock(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return false;
        }

        if (args.length < 3) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>使用方法: /shop addstock <NPC UUID> <個数>"));
            return false;
        }

        Player player = (Player) sender;
        UUID npcUuid;
        int quantity;

        try {
            npcUuid = UUID.fromString(args[1]);
            quantity = Integer.parseInt(args[2]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>無効な数値、または無効なUUIDです"));
            return false;
        }

        String serverId = plugin.getConfigManager().getServerId();
        ShopManager.Shop shop = shopManager.getShop(npcUuid, serverId);

        if (shop == null || !shop.getOwnerUUID().equals(player.getUniqueId())) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>この店舗を管理する権限がありません"));
            return false;
        }

        if (shopManager.addStock(npcUuid, serverId, quantity)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<green>在庫に " + quantity + "個追加しました"));
            return true;
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c在庫追加に失敗しました");
            return false;
        }
    }

    /**
     * /shop removestock <npcUuid> <quantity>
     */
    private boolean handleRemoveStock(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return false;
        }

        if (args.length < 3) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>使用方法: /shop removestock <NPC UUID> <個数>"));
            return false;
        }

        Player player = (Player) sender;
        UUID npcUuid;
        int quantity;

        try {
            npcUuid = UUID.fromString(args[1]);
            quantity = Integer.parseInt(args[2]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>無効な数値、または無効なUUIDです"));
            return false;
        }

        String serverId = plugin.getConfigManager().getServerId();
        ShopManager.Shop shop = shopManager.getShop(npcUuid, serverId);

        if (shop == null || !shop.getOwnerUUID().equals(player.getUniqueId())) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>この店舗を管理する権限がありません"));
            return false;
        }

        if (shopManager.removeStock(npcUuid, serverId, quantity)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<green>在庫から " + quantity + "個削除しました"));
            return true;
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c在庫削除に失敗しました");
            return false;
        }
    }

    @Override
    public List<String> onTabComplete(org.bukkit.command.CommandSender sender, Command command,
                                     String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.add("create");
            completions.add("delete");
            completions.add("list");
            completions.add("info");
            completions.add("setprice");
            completions.add("setstock");
            completions.add("addstock");
            completions.add("removestock");
        } else if (args.length == 2 && "list".equalsIgnoreCase(args[0])) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                completions.add(player.getName());
            }
        }

        return completions;
    }
}
