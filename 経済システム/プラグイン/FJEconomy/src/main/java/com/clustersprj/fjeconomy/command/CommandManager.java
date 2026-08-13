package com.clustersprj.fjeconomy.command;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.economy.EconomyManager;
import com.clustersprj.fjeconomy.link.LinkManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CommandManager implements CommandExecutor, TabCompleter {

    private final FJEconomy plugin;
    private final EconomyManager economyManager;

    public CommandManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.economyManager = new EconomyManager(plugin);
    }

    /**
     * Register commands
     */
    public void registerCommands() {
        plugin.getCommand("fj").setExecutor(this);
        plugin.getCommand("fj").setTabCompleter(this);
        plugin.getCommand("fjeadmin").setExecutor(this);
        plugin.getCommand("fjeadmin").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, Command command, 
                            String label, String[] args) {
        // 引数がない場合は各コマンドのヘルプを表示して終了(trueを返す)
        if (args.length == 0) {
            handleHelp(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase();

        // Player commands
        if ("balance".equalsIgnoreCase(subcommand) || "bal".equalsIgnoreCase(subcommand)) {
            return handleBalance(sender, args);
        } else if ("pay".equalsIgnoreCase(subcommand)) {
            return handlePay(sender, args);
        }

        // Admin commands
        else if ("give".equalsIgnoreCase(subcommand)) {
            return handleGive(sender, args);
        } else if ("take".equalsIgnoreCase(subcommand)) {
            return handleTake(sender, args);
        } else if ("set".equalsIgnoreCase(subcommand)) {
            return handleSet(sender, args);
        } else if ("reload".equalsIgnoreCase(subcommand)) {
            return handleReload(sender);
        } else if ("link".equalsIgnoreCase(subcommand)) {
            return handleLink(sender);
        } else if ("help".equalsIgnoreCase(subcommand)) {
            return handleHelp(sender);
        }

        sender.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfigManager().getMessagePrefix() + "<red>不明なコマンド: " + subcommand));
        return true;
    }

    /**
     * Handle balance command
     */
    private boolean handleBalance(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return true;
        }

        Player player = (Player) sender;
        long balance = economyManager.getBalance(player.getUniqueId());
        String message = plugin.getConfigManager().getMessagePrefix() + "残高: " + economyManager.formatMoney(balance);
        
        player.sendMessage(MiniMessage.miniMessage().deserialize(message));
        return true;
    }

    /**
     * Handle pay command
     */
    private boolean handlePay(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<red>使用方法: /fj pay <プレイヤー名> <金額>"));
            return true;
        }

        Player sender_player = (Player) sender;
        String targetName = args[1];
        
        // DBからプレイヤーUUID取得
        UUID targetUUID = economyManager.getPlayerUUIDByName(targetName);
        if (targetUUID == null) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>プレイヤーが見つかりません"));
            return true;
        }

        // 検索は大文字小文字を区別しないため、以降は入力値ではなく登録名を使う
        // （入力値のまま残高操作すると登録名が入力どおりに書き換わってしまう）
        String canonicalName = economyManager.getPlayerNameByUUID(targetUUID);
        if (canonicalName != null) {
            targetName = canonicalName;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>無効な金額です"));
            return true;
        }

        if (amount <= 0) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>金額は正の数である必要があります"));
            return true;
        }

        boolean success = economyManager.sendMoney(
                sender_player.getUniqueId(), sender_player.getName(),
                targetUUID, targetName,
                amount);

        if (success) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<green>" + targetName + " に " + economyManager.formatMoney(amount) + " を送金しました"));
            
            // オンラインなら通知
            Player targetPlayer = Bukkit.getPlayer(targetUUID);
            if (targetPlayer != null) {
                targetPlayer.sendMessage(MiniMessage.miniMessage().deserialize(
                        plugin.getConfigManager().getMessagePrefix() + 
                        "<green>" + sender_player.getName() + " から " + 
                        economyManager.formatMoney(amount) + " を受け取りました"));
            }
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>送金に失敗しました"));
        }

        return true;
    }

    /**
     * Handle give command (admin)
     */
    private boolean handleGive(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.admin")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドを実行する権限がありません"));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<red>使用方法: /fjeadmin give <プレイヤー名> <金額>"));
            return true;
        }

        String targetName = args[1];
        UUID targetUUID = economyManager.getPlayerUUIDByName(targetName);

        if (targetUUID == null) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>プレイヤーが見つかりません"));
            return true;
        }

        // 検索は大文字小文字を区別しないため、以降は入力値ではなく登録名を使う
        // （入力値のまま残高操作すると登録名が入力どおりに書き換わってしまう）
        String canonicalName = economyManager.getPlayerNameByUUID(targetUUID);
        if (canonicalName != null) {
            targetName = canonicalName;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>無効な金額です"));
            return true;
        }

        economyManager.ensurePlayerAccount(targetUUID, targetName);
        boolean success = economyManager.giveMoney(targetUUID, targetName, amount);

        if (success) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<green>" + targetName + " に " + economyManager.formatMoney(amount) + " を付与しました"));
            
            // オンラインなら通知
            Player targetPlayer = Bukkit.getPlayer(targetUUID);
            if (targetPlayer != null) {
                targetPlayer.sendMessage(MiniMessage.miniMessage().deserialize(
                        plugin.getConfigManager().getMessagePrefix() + 
                        "<green>管理者から " + economyManager.formatMoney(amount) + " を付与されました"));
            }
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>付与に失敗しました"));
        }

        return true;
    }

    /**
     * Handle take command (admin)
     */
    private boolean handleTake(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.admin")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドを実行する権限がありません"));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<red>使用方法: /fjeadmin take <プレイヤー名> <金額>"));
            return true;
        }

        String targetName = args[1];
        UUID targetUUID = economyManager.getPlayerUUIDByName(targetName);

        if (targetUUID == null) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>プレイヤーが見つかりません"));
            return true;
        }

        // 検索は大文字小文字を区別しないため、以降は入力値ではなく登録名を使う
        // （入力値のまま残高操作すると登録名が入力どおりに書き換わってしまう）
        String canonicalName = economyManager.getPlayerNameByUUID(targetUUID);
        if (canonicalName != null) {
            targetName = canonicalName;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>無効な金額です"));
            return true;
        }

        economyManager.ensurePlayerAccount(targetUUID, targetName);
        boolean success = economyManager.takeMoney(targetUUID, targetName, amount);

        if (success) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<green>" + targetName + " から " + economyManager.formatMoney(amount) + " を没収しました"));
            
            // オンラインなら通知
            Player targetPlayer = Bukkit.getPlayer(targetUUID);
            if (targetPlayer != null) {
                targetPlayer.sendMessage(MiniMessage.miniMessage().deserialize(
                        plugin.getConfigManager().getMessagePrefix() + 
                        "<red>管理者により " + economyManager.formatMoney(amount) + " を没収されました"));
            }
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>没収に失敗しました"));
        }

        return true;
    }

    /**
     * Handle set command (admin)
     */
    private boolean handleSet(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.admin")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドを実行する権限がありません"));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<red>使用方法: /fjeadmin set <プレイヤー名> <金額>"));
            return true;
        }

        String targetName = args[1];
        UUID targetUUID = economyManager.getPlayerUUIDByName(targetName);

        if (targetUUID == null) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>プレイヤーが見つかりません"));
            return true;
        }

        // 検索は大文字小文字を区別しないため、以降は入力値ではなく登録名を使う
        // （入力値のまま残高操作すると登録名が入力どおりに書き換わってしまう）
        String canonicalName = economyManager.getPlayerNameByUUID(targetUUID);
        if (canonicalName != null) {
            targetName = canonicalName;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>無効な金額です"));
            return true;
        }

        economyManager.ensurePlayerAccount(targetUUID, targetName);
        boolean success = economyManager.setBalance(targetUUID, targetName, amount);

        if (success) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<green>" + targetName + " の残高を " + economyManager.formatMoney(amount) + " に設定しました"));
            
            // オンラインなら通知
            Player targetPlayer = Bukkit.getPlayer(targetUUID);
            if (targetPlayer != null) {
                targetPlayer.sendMessage(MiniMessage.miniMessage().deserialize(
                        plugin.getConfigManager().getMessagePrefix() + 
                        "<yellow>管理者により残高を " + economyManager.formatMoney(amount) + " に設定されました"));
            }
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>設定に失敗しました"));
        }

        return true;
    }

    /**
     * Handle reload command
     */
    private boolean handleReload(org.bukkit.command.CommandSender sender) {
        if (!sender.hasPermission("fj.reload")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドを実行する権限がありません"));
            return true;
        }

        try {
            plugin.reloadPlugin();
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<green>FJ Economy をリロードしました"));
            return true;
        } catch (Exception e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<red>リロードに失敗しました: " + e.getMessage()));
            return true;
        }
    }

    /**
     * Handle link command - Webアカウント連携用のワンタイムコードを発行する
     */
    private boolean handleLink(org.bukkit.command.CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return true;
        }

        Player player = (Player) sender;
        LinkManager linkManager = plugin.getLinkManager();

        // プレイヤーの残高レコードを保証（連携時のWeb側チェックで参照されるため）
        economyManager.ensurePlayerAccount(player.getUniqueId(), player.getName());

        String code = linkManager.generateLinkCode(player.getUniqueId());

        if (code == null) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>連携コードの発行に失敗しました。時間をおいて再度お試しください。"));
            return true;
        }

        int expiryMinutes = plugin.getConfigManager().getAccountLinkCodeExpiryMinutes();
        String websiteUrl = plugin.getConfigManager().getAccountLinkWebsiteUrl();

        sender.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfigManager().getMessagePrefix() + "<aqua>Webアカウント連携コードを発行しました"));
        sender.sendMessage(MiniMessage.miniMessage().deserialize(
                "<gray>コード: <yellow><bold>" + code + "<reset>"));
        sender.sendMessage(MiniMessage.miniMessage().deserialize(
                "<gray>有効期限: <white>" + expiryMinutes + "分"));
        sender.sendMessage(MiniMessage.miniMessage().deserialize(
                "<gray>" + websiteUrl + " にアクセスし、ログイン後にこのコードを入力してください"));

        return true;
    }

    /**
     * Handle help command
     */
    private boolean handleHelp(org.bukkit.command.CommandSender sender) {
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<aqua>=== FJ Economy ヘルプ ==="));
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>/fj bal<reset> - 残高確認"));
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>/fj pay <プレイヤー> <金額><reset> - 送金"));
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>/fj link<reset> - Webアカウント連携コードの発行"));
        
        if (sender.hasPermission("fj.admin")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>/fjeadmin give <プレイヤー> <金額><reset> - 付与(管理者)"));
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>/fjeadmin take <プレイヤー> <金額><reset> - 没収(管理者)"));
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>/fjeadmin set <プレイヤー> <金額><reset> - 設定(管理者)"));
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>/fjeadmin reload<reset> - リロード(管理者)"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(org.bukkit.command.CommandSender sender, Command command, 
                                     String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        // 1つ目の引数の補完
        if (args.length == 1) {
            if (command.getName().equalsIgnoreCase("fj")) {
                completions.add("balance");
                completions.add("pay");
                completions.add("link");
                completions.add("help");
            } else if (command.getName().equalsIgnoreCase("fjeadmin")) {
                if (sender.hasPermission("fj.admin")) {
                    completions.add("give");
                    completions.add("take");
                    completions.add("set");
                    completions.add("reload");
                }
            }
        } 
        // 2つ目の引数（プレイヤー名など）の補完（ifの外に出す）
        else if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            if ("pay".equalsIgnoreCase(subCommand) || "give".equalsIgnoreCase(subCommand) || 
                "take".equalsIgnoreCase(subCommand) || "set".equalsIgnoreCase(subCommand)) {
                
                // オンラインプレイヤー優先
                for (Player player : Bukkit.getOnlinePlayers()) {
                    completions.add(player.getName());
                }
                // オフラインプレイヤーを追加
                for (String name : economyManager.getAllPlayerNames()) {
                    if (!completions.contains(name)) {
                        completions.add(name);
                    }
                }
            }
        }
        return completions;
    }
}