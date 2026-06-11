package com.clustersprj.fjeconomy.command;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.economy.EconomyManager;
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
        if (args.length == 0) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c/fj <subcommand>");
            return false;
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
        } else if ("help".equalsIgnoreCase(subcommand)) {
            return handleHelp(sender);
        }

        sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c不明なコマンド: " + subcommand);
        return false;
    }

    /**
     * Handle balance command
     */
    private boolean handleBalance(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます");
            return false;
        }

        Player player = (Player) sender;
        long balance = economyManager.getBalance(player.getUniqueId());
        String message = plugin.getConfigManager().getMessagePrefix() + 
                        "残高: " + economyManager.formatMoney(balance);
        player.sendMessage(message);
        return true;
    }

    /**
     * Handle pay command
     */
    private boolean handlePay(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます");
            return false;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                             "§c使用方法: /fj pay <プレイヤー名> <金額>");
            return false;
        }

        Player sender_player = (Player) sender;
        String targetName = args[1];
        
        // DBからプレイヤーUUID取得
        UUID targetUUID = economyManager.getPlayerUUIDByName(targetName);
        if (targetUUID == null) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§cプレイヤーが見つかりません");
            return false;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c無効な金額です");
            return false;
        }

        if (amount <= 0) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c金額は正の数である必要があります");
            return false;
        }

        boolean success = economyManager.sendMoney(
                sender_player.getUniqueId(), sender_player.getName(),
                targetUUID, targetName,
                amount);

        if (success) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                             "§a" + targetName + " に " + economyManager.formatMoney(amount) + " を送金しました");
            
            // オンラインなら通知
            Player targetPlayer = Bukkit.getPlayer(targetUUID);
            if (targetPlayer != null) {
                targetPlayer.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                                       "§a" + sender_player.getName() + " から " + 
                                       economyManager.formatMoney(amount) + " を受け取りました");
            }
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c送金に失敗しました");
        }

        return true;
    }

    /**
     * Handle give command (admin)
     */
    private boolean handleGive(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.admin")) {
            sender.sendMessage("§cこのコマンドを実行する権限がありません");
            return false;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                             "§c使用方法: /fjeadmin give <プレイヤー名> <金額>");
            return false;
        }

        String targetName = args[1];
        UUID targetUUID = economyManager.getPlayerUUIDByName(targetName);

        if (targetUUID == null) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                             "§cプレイヤーが見つかりません");
            return false;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c無効な金額です");
            return false;
        }

        economyManager.ensurePlayerAccount(targetUUID, targetName);
        boolean success = economyManager.giveMoney(targetUUID, targetName, amount);

        if (success) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                             "§a" + targetName + " に " + economyManager.formatMoney(amount) + " を付与しました");
            
            // オンラインなら通知
            Player targetPlayer = Bukkit.getPlayer(targetUUID);
            if (targetPlayer != null) {
                targetPlayer.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                                       "§a管理者から " + 
                                       economyManager.formatMoney(amount) + " を付与されました");
            }
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c付与に失敗しました");
        }

        return true;
    }

    /**
     * Handle take command (admin)
     */
    private boolean handleTake(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.admin")) {
            sender.sendMessage("§cこのコマンドを実行する権限がありません");
            return false;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                             "§c使用方法: /fjeadmin take <プレイヤー名> <金額>");
            return false;
        }

        String targetName = args[1];
        UUID targetUUID = economyManager.getPlayerUUIDByName(targetName);

        if (targetUUID == null) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                             "§cプレイヤーが見つかりません");
            return false;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c無効な金額です");
            return false;
        }

        economyManager.ensurePlayerAccount(targetUUID, targetName);
        boolean success = economyManager.takeMoney(targetUUID, targetName, amount);

        if (success) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                             "§a" + targetName + " から " + economyManager.formatMoney(amount) + " を没収しました");
            
            // オンラインなら通知
            Player targetPlayer = Bukkit.getPlayer(targetUUID);
            if (targetPlayer != null) {
                targetPlayer.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                                       "§c管理者により " + 
                                       economyManager.formatMoney(amount) + " を没収されました");
            }
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c没収に失敗しました");
        }

        return true;
    }

    /**
     * Handle set command (admin)
     */
    private boolean handleSet(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.admin")) {
            sender.sendMessage("§cこのコマンドを実行する権限がありません");
            return false;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                             "§c使用方法: /fjeadmin set <プレイヤー名> <金額>");
            return false;
        }

        String targetName = args[1];
        UUID targetUUID = economyManager.getPlayerUUIDByName(targetName);

        if (targetUUID == null) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                             "§cプレイヤーが見つかりません");
            return false;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c無効な金額です");
            return false;
        }

        economyManager.ensurePlayerAccount(targetUUID, targetName);
        boolean success = economyManager.setBalance(targetUUID, targetName, amount);

        if (success) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                             "§a" + targetName + " の残高を " + economyManager.formatMoney(amount) + " に設定しました");
            
            // オンラインなら通知
            Player targetPlayer = Bukkit.getPlayer(targetUUID);
            if (targetPlayer != null) {
                targetPlayer.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                                       "§e管理者により残高を " + 
                                       economyManager.formatMoney(amount) + " に設定されました");
            }
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§c設定に失敗しました");
        }

        return true;
    }

    /**
     * Handle reload command
     */
    private boolean handleReload(org.bukkit.command.CommandSender sender) {
        if (!sender.hasPermission("fj.reload")) {
            sender.sendMessage("§cこのコマンドを実行する権限がありません");
            return false;
        }

        try {
            plugin.reloadPlugin();
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§aFJ Economy をリロードしました");
            return true;
        } catch (Exception e) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                             "§cリロードに失敗しました: " + e.getMessage());
            return false;
        }
    }

    /**
     * Handle help command
     */
    private boolean handleHelp(org.bukkit.command.CommandSender sender) {
        sender.sendMessage("§b=== FJ Economy ヘルプ ===");
        sender.sendMessage("§a/fj bal§r - 残高確認");
        sender.sendMessage("§a/fj pay <プレイヤー> <金額>§r - 送金");
        
        if (sender.hasPermission("fj.admin")) {
            sender.sendMessage("§c/fjeadmin give <プレイヤー> <金額>§r - 付与(管理者)");
            sender.sendMessage("§c/fjeadmin take <プレイヤー> <金額>§r - 没収(管理者)");
            sender.sendMessage("§c/fjeadmin set <プレイヤー> <金額>§r - 設定(管理者)");
            sender.sendMessage("§c/fjeadmin reload§r - リロード(管理者)");
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
