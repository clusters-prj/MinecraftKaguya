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

/**
 * /shop コマンドの実行およびタブ補完を処理するクラスです。
 * <p>
 * NPCをベースにしたプレイヤー店舗の作成、削除、在庫設定（追加、削除）、
 * 価格の変更、および稼働中の店舗一覧を表示するコマンドハンドラーです。
 * </p>
 */
public class ShopCommand implements CommandExecutor, TabCompleter {

    /** プラグインのメインインスタンス */
    private final FJEconomy plugin;
    
    /** 店舗データの読み書きや同期処理を行うマネージャー */
    private final ShopManager shopManager;

    /**
     * ShopCommandの新しいインスタンスを構築します。
     *
     * @param plugin プラグインのメインインスタンス
     * @param shopManager ショップマネージャー
     */
    public ShopCommand(FJEconomy plugin, ShopManager shopManager) {
        this.plugin = plugin;
        this.shopManager = shopManager;
    }

    /**
     * デバッグログが有効な場合にコンソールへデバッグ情報を出力するヘルパーです。
     *
     * @param message ログに出力するデバッグメッセージ
     */
    private void logDebug(String message) {
        if (plugin.getConfig().getString("logging.level", "INFO").equalsIgnoreCase("DEBUG")) {
            plugin.getLogger().info("[Shop-Debug] " + message);
        }
    }

    /**
     * /shop コマンドが送信された際に呼び出されます。
     * 各サブコマンド（create, delete, list, info, setprice, setstock, addstock, removestock）を解析して実行します。
     *
     * @param sender コマンドの実行者
     * @param command 実行コマンド
     * @param label コマンドのエイリアス
     * @param args コマンド引数の配列
     * @return 正常終了した場合は true、それ以外は false
     */
    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, Command command,
                            String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>/shop <subcommand>"));
            return true;
        }

        String subcommand = args[0].toLowerCase();
        logDebug("Executing command: /" + label + " " + String.join(" ", args) + " (Sender: " + sender.getName() + ")");

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
                return true;
        }
    }

    /**
     * /shop create <npcUuid> <item> <price> [stock] - 新規店舗を立ち上げます。
     * 実行者がプレイヤーであり、有効なNPC UUIDとアイテム素材名が指定される必要があります。
     *
     * @param sender コマンド送信者（プレイヤー限定）
     * @param args サブコマンド引数の配列
     * @return 処理が完了した場合は true
     */
    private boolean handleCreate(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return true;
        }

        if (args.length < 4) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>使用方法: /shop create <NPC UUID> <アイテム> <価格> [在庫]"));
            return true;
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
            logDebug("Parsed Create: NPC=" + npcUuid + ", Item=" + itemMaterial + ", Price=" + price + ", Stock=" + stock);
        } catch (IllegalArgumentException e) {
            logDebug("Create failed: Invalid UUID or Number format");
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>無効な数値、または無効なUUIDです"));
            return true;
        }

        if (price < 0 || stock < 0) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>価格と在庫は0以上である必要があります"));
            return true;
        }

        String serverId = plugin.getConfigManager().getServerId();

        logDebug("Calling ShopManager.createShop with ServerID=" + serverId + ", Owner=" + player.getUniqueId());
        if (shopManager.createShop(npcUuid, serverId, player.getUniqueId(), itemMaterial, price, stock)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<green>店舗を作成しました (NPC UUID: " + npcUuid + ", アイテム: " + itemMaterial +
                    ", 価格: " + plugin.getConfigManager().getCurrencySymbol() + price + ")"));
            return true;
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>店舗作成に失敗しました"));
            return true;
        }
    }

    /**
     * /shop delete <npcUuid> - NPCに紐づけられた店舗を完全に削除します。
     *
     * @param sender コマンド送信者（要権限 "fj.shop.delete"）
     * @param args サブコマンド引数
     * @return 処理が完了した場合は true
     */
    private boolean handleDelete(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.shop.delete")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドを実行する権限がありません"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>使用方法: /shop delete <NPC UUID>"));
            return true;
        }

        UUID npcUuid;
        try {
            npcUuid = UUID.fromString(args[1]);
            logDebug("Parsed Delete: NPC=" + npcUuid);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>無効なNPC UUIDです"));
            return true;
        }

        String serverId = plugin.getConfigManager().getServerId();
        logDebug("Deleting shop for ServerID=" + serverId);

        if (shopManager.deleteShop(npcUuid, serverId)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<green>店舗を削除しました (NPC UUID: " + npcUuid + ")"));
            return true;
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>該当する店舗が見つかりません"));
            return true;
        }
    }

    /**
     * /shop list [player] - 指定されたプレイヤー（または自分自身）の所有している店舗一覧を出力します。
     *
     * @param sender コマンド送信者
     * @param args 引数（args[1]: 対象プレイヤー（任意））
     * @return 処理が完了した場合は true
     */
    private boolean handleList(org.bukkit.command.CommandSender sender, String[] args) {
        UUID targetUUID;
        String targetName;

        if (args.length < 2) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>プレイヤー名を指定してください"));
                return true;
            }
            Player player = (Player) sender;
            targetUUID = player.getUniqueId();
            targetName = player.getName();
        } else {
            Player targetPlayer = Bukkit.getPlayer(args[1]);
            if (targetPlayer == null) {
                sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                        "<red>プレイヤーが見つかりません"));
                return true;
            }
            targetUUID = targetPlayer.getUniqueId();
            targetName = targetPlayer.getName();
        }

        String serverId = plugin.getConfigManager().getServerId();
        logDebug("Listing shops for Target=" + targetUUID + " (" + targetName + ") on Server=" + serverId);
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
     * /shop info <npcUuid> - 店舗の現在の在庫や価格を詳細に確認します。
     *
     * @param sender コマンド送信者
     * @param args 引数（args[1]: 店舗のNPC UUID）
     * @return 処理が完了した場合は true
     */
    private boolean handleInfo(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>使用方法: /shop info <NPC UUID>"));
            return true;
        }

        UUID npcUuid;
        try {
            npcUuid = UUID.fromString(args[1]);
            logDebug("Parsed Info: NPC=" + npcUuid);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>無効なNPC UUIDです"));
            return true;
        }

        String serverId = plugin.getConfigManager().getServerId();
        logDebug("Fetching shop data for ServerID=" + serverId);
        ShopManager.Shop shop = shopManager.getShop(npcUuid, serverId);

        if (shop == null) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>該当する店舗が見つかりません"));
            return true;
        }

        sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<aqua>店舗情報:"));
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<gray>  NPC UUID: <white>" + shop.getNpcUuid()));
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<gray>  アイテム: <white>" + shop.getItemMaterial()));
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<gray>  価格: <white>" + 
                plugin.getConfigManager().getCurrencySymbol() + shop.getPrice()));
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<gray>  在庫: <white>" + shop.getStock() + "個"));

        return true;
    }

    /**
     * /shop setprice <npcUuid> <price> - 所有する店舗で取り扱うアイテムの販売価格を変更します。
     *
     * @param sender コマンド送信者（店舗のオーナーであるプレイヤー）
     * @param args 引数（args[1]: 店舗のNPC UUID, args[2]: 新規設定価格）
     * @return 処理が完了した場合は true
     */
    private boolean handleSetPrice(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>使用方法: /shop setprice <NPC UUID> <価格>"));
            return true;
        }

        Player player = (Player) sender;
        UUID npcUuid;
        int price;

        try {
            npcUuid = UUID.fromString(args[1]);
            price = Integer.parseInt(args[2]);
            logDebug("Parsed SetPrice: NPC=" + npcUuid + ", Price=" + price);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>無効な数値、または無効なUUIDです"));
            return true;
        }

        String serverId = plugin.getConfigManager().getServerId();
        logDebug("Updating price on ServerID=" + serverId);
        ShopManager.Shop shop = shopManager.getShop(npcUuid, serverId);

        if (shop == null || !shop.getOwnerUUID().equals(player.getUniqueId())) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>この店舗を管理する権限がありません"));
            return true;
        }

        if (shopManager.updateShopPrice(npcUuid, serverId, price)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<green>価格を " + plugin.getConfigManager().getCurrencySymbol() + price + " に設定しました"));
            return true;
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>価格設定に失敗しました"));
            return true;
        }
    }

    /**
     * /shop setstock <npcUuid> <stock> - 所有する店舗の在庫を、任意の個数に直接上書き変更します。
     *
     * @param sender コマンド送信者（店舗のオーナーであるプレイヤー）
     * @param args 引数（args[1]: 店舗のNPC UUID, args[2]: 新規在庫数）
     * @return 処理が完了した場合は true
     */
    private boolean handleSetStock(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>使用方法: /shop setstock <NPC UUID> <在庫>"));
            return true;
        }

        Player player = (Player) sender;
        UUID npcUuid;
        int stock;

        try {
            npcUuid = UUID.fromString(args[1]);
            stock = Integer.parseInt(args[2]);
            logDebug("Parsed SetStock: NPC=" + npcUuid + ", Stock=" + stock);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>無効な数値、または無効なUUIDです"));
            return true;
        }

        String serverId = plugin.getConfigManager().getServerId();
        logDebug("Updating stock on ServerID=" + serverId);
        ShopManager.Shop shop = shopManager.getShop(npcUuid, serverId);

        if (shop == null || !shop.getOwnerUUID().equals(player.getUniqueId())) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>この店舗を管理する権限がありません"));
            return true;
        }

        if (shopManager.updateShopStock(npcUuid, serverId, stock)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<green>在庫を " + stock + "個に設定しました"));
            return true;
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>在庫設定に失敗しました"));
            return true;
        }
    }

    /**
     * /shop addstock <npcUuid> <quantity> - 既存の店舗在庫に指定された数量を補充加算します。
     *
     * @param sender コマンド送信者（店舗のオーナーであるプレイヤー）
     * @param args 引数（args[1]: 店舗のNPC UUID, args[2]: 補充数量）
     * @return 処理が完了した場合は true
     */
    private boolean handleAddStock(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>使用方法: /shop addstock <NPC UUID> <個数>"));
            return true;
        }

        Player player = (Player) sender;
        UUID npcUuid;
        int quantity;

        try {
            npcUuid = UUID.fromString(args[1]);
            quantity = Integer.parseInt(args[2]);
            logDebug("Parsed AddStock: NPC=" + npcUuid + ", Qty=" + quantity);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>無効な数値、または無効なUUIDです"));
            return true;
        }

        String serverId = plugin.getConfigManager().getServerId();
        logDebug("Adding stock on ServerID=" + serverId);
        ShopManager.Shop shop = shopManager.getShop(npcUuid, serverId);

        if (shop == null || !shop.getOwnerUUID().equals(player.getUniqueId())) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>この店舗を管理する権限がありません"));
            return true;
        }

        if (shopManager.addStock(npcUuid, serverId, quantity)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<green>在庫に " + quantity + "個追加しました"));
            return true;
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>在庫追加に失敗しました"));
            return true;
        }
    }

    /**
     * /shop removestock <npcUuid> <quantity> - 既存の店舗在庫から指定された数量を削減減算します。
     *
     * @param sender コマンド送信者（店舗のオーナーであるプレイヤー）
     * @param args 引数（args[1]: 店舗のNPC UUID, args[2]: 削減数量）
     * @return 処理が完了した場合は true
     */
    private boolean handleRemoveStock(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>使用方法: /shop removestock <NPC UUID> <個数>"));
            return true;
        }

        Player player = (Player) sender;
        UUID npcUuid;
        int quantity;

        try {
            npcUuid = UUID.fromString(args[1]);
            quantity = Integer.parseInt(args[2]);
            logDebug("Parsed RemoveStock: NPC=" + npcUuid + ", Qty=" + quantity);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>無効な数値、または無効なUUIDです"));
            return true;
        }

        String serverId = plugin.getConfigManager().getServerId();
        logDebug("Removing stock on ServerID=" + serverId);
        ShopManager.Shop shop = shopManager.getShop(npcUuid, serverId);

        if (shop == null || !shop.getOwnerUUID().equals(player.getUniqueId())) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<red>この店舗を管理する権限がありません"));
            return true;
        }

        if (shopManager.removeStock(npcUuid, serverId, quantity)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() +
                    "<green>在庫から " + quantity + "個削除しました"));
            return true;
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>在庫削除に失敗しました"));
            return true;
        }
    }

    /**
     * コマンドのタブ入力時に、各段階の引数（サブコマンド、オンラインプレイヤー、Minecraftのアイテムマテリアルなど）を候補に補完します。
     *
     * @param sender タブキーを押した送信者
     * @param command 対象のコマンド
     * @param alias コマンドのエイリアス
     * @param args 現在入力中の引数
     * @return 補完候補のリスト
     */
    @Override
    public List<String> onTabComplete(org.bukkit.command.CommandSender sender, Command command,
                                     String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            suggestions.add("create");
            if (sender.hasPermission("fj.shop.delete")) suggestions.add("delete");
            suggestions.add("list");
            suggestions.add("info");
            suggestions.add("setprice");
            suggestions.add("setstock");
            suggestions.add("addstock");
            suggestions.add("removestock");
            org.bukkit.util.StringUtil.copyPartialMatches(args[0], suggestions, completions);
        } else if (args.length == 2 && "list".equalsIgnoreCase(args[0])) {
            List<String> players = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                players.add(player.getName());
            }
            org.bukkit.util.StringUtil.copyPartialMatches(args[1], players, completions);
        } else if (args.length == 3 && "create".equalsIgnoreCase(args[0])) {
            List<String> materials = new ArrayList<>();
            for (org.bukkit.Material m : org.bukkit.Material.values()) {
                if (m.isItem()) {
                    materials.add(m.name().toLowerCase());
                }
            }
            org.bukkit.util.StringUtil.copyPartialMatches(args[2], materials, completions);
        }

        java.util.Collections.sort(completions);
        return completions;
    }
}