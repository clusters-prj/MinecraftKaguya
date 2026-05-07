package com.clustersprj.fjeconomy.command;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.shop.ShopManager;
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
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c/shop <subcommand>");
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
                sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c不明なコマンド: " + subcommand);
                return false;
        }
    }

    /**
     * /shop create <npcId> <item> <price> [stock]
     */
    private boolean handleCreate(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます");
            return false;
        }

        if (args.length < 4) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§c使用方法: /shop create <NPC ID> <アイテム> <価格> [在庫]");
            return false;
        }

        Player player = (Player) sender;
        int npcId;
        String itemMaterial = args[2];
        int price;
        int stock;

        try {
            npcId = Integer.parseInt(args[1]);
            price = Integer.parseInt(args[3]);
            stock = args.length > 4 ? Integer.parseInt(args[4]) : 
                   plugin.getConfigManager().getDefaultStock();
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c無効な数値です");
            return false;
        }

        if (price < 0 || stock < 0) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§c価格と在庫は0以上である必要があります");
            return false;
        }

        String serverId = plugin.getConfigManager().getServerId();

        if (shopManager.createShop(npcId, serverId, player.getUniqueId(), itemMaterial, price, stock)) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§a店舗を作成しました (NPC ID: " + npcId + ", アイテム: " + itemMaterial +
                    ", 価格: " + plugin.getConfigManager().getCurrencySymbol() + price + ")");
            return true;
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c店舗作成に失敗しました");
            return false;
        }
    }

    /**
     * /shop delete <npcId>
     */
    private boolean handleDelete(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.shop.delete")) {
            sender.sendMessage("§cこのコマンドを実行する権限がありません");
            return false;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§c使用方法: /shop delete <NPC ID>");
            return false;
        }

        int npcId;
        try {
            npcId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c無効なNPC IDです");
            return false;
        }

        String serverId = plugin.getConfigManager().getServerId();

        if (shopManager.deleteShop(npcId, serverId)) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§a店舗を削除しました (NPC ID: " + npcId + ")");
            return true;
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§c該当する店舗が見つかりません");
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
                sender.sendMessage("§cプレイヤー名を指定してください");
                return false;
            }
            Player player = (Player) sender;
            targetUUID = player.getUniqueId();
            targetName = player.getName();
        } else {
            Player targetPlayer = Bukkit.getPlayer(args[1]);
            if (targetPlayer == null) {
                sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                        "§cプレイヤーが見つかりません");
                return false;
            }
            targetUUID = targetPlayer.getUniqueId();
            targetName = targetPlayer.getName();
        }

        String serverId = plugin.getConfigManager().getServerId();
        List<ShopManager.Shop> shops = shopManager.getShopsByOwner(targetUUID, serverId);

        if (shops.isEmpty()) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§c" + targetName + " の店舗がありません");
            return true;
        }

        sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                "§b" + targetName + " の店舗一覧:");
        for (ShopManager.Shop shop : shops) {
            sender.sendMessage("  §7NPC ID: " + shop.getNpcId() + " - " +
                    shop.getDisplayInfo(plugin.getConfigManager().getCurrencySymbol()));
        }

        return true;
    }

    /**
     * /shop info <npcId>
     */
    private boolean handleInfo(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§c使用方法: /shop info <NPC ID>");
            return false;
        }

        int npcId;
        try {
            npcId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c無効なNPC IDです");
            return false;
        }

        String serverId = plugin.getConfigManager().getServerId();
        ShopManager.Shop shop = shopManager.getShop(npcId, serverId);

        if (shop == null) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§c該当する店舗が見つかりません");
            return false;
        }

        sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§b店舗情報:");
        sender.sendMessage("  NPC ID: " + shop.getNpcId());
        sender.sendMessage("  アイテム: " + shop.getItemMaterial());
        sender.sendMessage("  価格: " + plugin.getConfigManager().getCurrencySymbol() + shop.getPrice());
        sender.sendMessage("  在庫: " + shop.getStock() + "個");

        return true;
    }

    /**
     * /shop setprice <npcId> <price>
     */
    private boolean handleSetPrice(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます");
            return false;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§c使用方法: /shop setprice <NPC ID> <価格>");
            return false;
        }

        Player player = (Player) sender;
        int npcId;
        int price;

        try {
            npcId = Integer.parseInt(args[1]);
            price = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c無効な数値です");
            return false;
        }

        String serverId = plugin.getConfigManager().getServerId();
        ShopManager.Shop shop = shopManager.getShop(npcId, serverId);

        if (shop == null || !shop.getOwnerUUID().equals(player.getUniqueId())) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§cこの店舗を管理する権限がありません");
            return false;
        }

        if (shopManager.updateShopPrice(npcId, serverId, price)) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§a価格を " + plugin.getConfigManager().getCurrencySymbol() + price + " に設定しました");
            return true;
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c価格設定に失敗しました");
            return false;
        }
    }

    /**
     * /shop setstock <npcId> <stock>
     */
    private boolean handleSetStock(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます");
            return false;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§c使用方法: /shop setstock <NPC ID> <在庫>");
            return false;
        }

        Player player = (Player) sender;
        int npcId;
        int stock;

        try {
            npcId = Integer.parseInt(args[1]);
            stock = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c無効な数値です");
            return false;
        }

        String serverId = plugin.getConfigManager().getServerId();
        ShopManager.Shop shop = shopManager.getShop(npcId, serverId);

        if (shop == null || !shop.getOwnerUUID().equals(player.getUniqueId())) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§cこの店舗を管理する権限がありません");
            return false;
        }

        if (shopManager.updateShopStock(npcId, serverId, stock)) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§a在庫を " + stock + "個に設定しました");
            return true;
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c在庫設定に失敗しました");
            return false;
        }
    }

    /**
     * /shop addstock <npcId> <quantity>
     */
    private boolean handleAddStock(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます");
            return false;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§c使用方法: /shop addstock <NPC ID> <個数>");
            return false;
        }

        Player player = (Player) sender;
        int npcId;
        int quantity;

        try {
            npcId = Integer.parseInt(args[1]);
            quantity = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c無効な数値です");
            return false;
        }

        String serverId = plugin.getConfigManager().getServerId();
        ShopManager.Shop shop = shopManager.getShop(npcId, serverId);

        if (shop == null || !shop.getOwnerUUID().equals(player.getUniqueId())) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§cこの店舗を管理する権限がありません");
            return false;
        }

        if (shopManager.addStock(npcId, serverId, quantity)) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§a在庫に " + quantity + "個追加しました");
            return true;
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c在庫追加に失敗しました");
            return false;
        }
    }

    /**
     * /shop removestock <npcId> <quantity>
     */
    private boolean handleRemoveStock(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます");
            return false;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§c使用方法: /shop removestock <NPC ID> <個数>");
            return false;
        }

        Player player = (Player) sender;
        int npcId;
        int quantity;

        try {
            npcId = Integer.parseInt(args[1]);
            quantity = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c無効な数値です");
            return false;
        }

        String serverId = plugin.getConfigManager().getServerId();
        ShopManager.Shop shop = shopManager.getShop(npcId, serverId);

        if (shop == null || !shop.getOwnerUUID().equals(player.getUniqueId())) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§cこの店舗を管理する権限がありません");
            return false;
        }

        if (shopManager.removeStock(npcId, serverId, quantity)) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() +
                    "§a在庫から " + quantity + "個削除しました");
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
