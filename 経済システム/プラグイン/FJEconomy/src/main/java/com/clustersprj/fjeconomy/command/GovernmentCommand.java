package com.clustersprj.fjeconomy.command;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.government.GovernmentManager;
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
 * /fjegovernment コマンド
 * 
 * 政府アカウント・税金システムの管理用コマンド
 * 権限: fj.government（管理者限定）
 */
public class GovernmentCommand implements CommandExecutor, TabCompleter {

    private final FJEconomy plugin;
    private final GovernmentManager governmentManager;

    public GovernmentCommand(FJEconomy plugin, GovernmentManager governmentManager) {
        this.plugin = plugin;
        this.governmentManager = governmentManager;
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, Command command,
                            String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<red>/fjegovernment <subcommand>"));
            return false;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "balance":
                return handleBalance(sender);
            case "add":
                return handleAdd(sender, args);
            case "withdraw":
                return handleWithdraw(sender, args);
            case "distribute":
                return handleDistribute(sender, args);
            case "ledger":
                return handleLedger(sender, args);
            case "tax":
                return handleTax(sender, args);
            case "set":
                return handleSet(sender, args);
            case "info":
                return handleInfo(sender);
            default:
                sender.sendMessage(MiniMessage.miniMessage().deserialize(
                        plugin.getConfigManager().getMessagePrefix() + 
                        "<red>不明なコマンド: " + subcommand));
                return false;
        }
    }

    /**
     * /fjegovernment balance - 政府残高確認
     */
    private boolean handleBalance(org.bukkit.command.CommandSender sender) {
        if (!sender.hasPermission("fj.government")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドを実行する権限がありません"));
            return true;
        }

        long balance = governmentManager.getGovernmentBalance();
        String currencySymbol = plugin.getConfigManager().getCurrencySymbol();
        sender.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfigManager().getMessagePrefix() + 
                "<aqua>政府残高: <green>" + currencySymbol + balance));
        return true;
    }

    /**
     * /fjegovernment add <金額> [理由] - 政府資金を追加
     */
    private boolean handleAdd(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.government")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドを実行する権限がありません"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<red>使用方法: /fjegovernment add <金額> [理由]"));
            return false;
        }

        long amount;
        try {
            amount = Long.parseLong(args[1]);
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

        // 理由を構築
        StringBuilder reasonBuilder = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            if (i > 2) reasonBuilder.append(" ");
            reasonBuilder.append(args[i]);
        }
        String reason = reasonBuilder.length() > 0 ? reasonBuilder.toString() : "Manual addition";

        if (governmentManager.addGovernmentFunds(amount, reason)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<green>政府資金に " + plugin.getConfigManager().getCurrencySymbol() + 
                    amount + " を追加しました"));
            return true;
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>追加に失敗しました"));
            return true;
        }
    }

    /**
     * /fjegovernment withdraw <金額> [理由] - 政府資金を引き出す
     */
    private boolean handleWithdraw(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.government")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドを実行する権限がありません"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<red>使用方法: /fjegovernment withdraw <金額> [理由]"));
            return false;
        }

        long amount;
        try {
            amount = Long.parseLong(args[1]);
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

        // 理由を構築
        StringBuilder reasonBuilder = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            if (i > 2) reasonBuilder.append(" ");
            reasonBuilder.append(args[i]);
        }
        String reason = reasonBuilder.length() > 0 ? reasonBuilder.toString() : "Manual withdrawal";

        if (governmentManager.withdrawGovernmentFunds(amount, reason)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<green>政府資金から " + plugin.getConfigManager().getCurrencySymbol() + 
                    amount + " を引き出しました"));
            return true;
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>引き出しに失敗しました（残高不足など）"));
            return true;
        }
    }

    /**
     * /fjegovernment distribute <プレイヤー> <金額> [理由]
     * 政府からプレイヤーに給付金を配分
     */
    private boolean handleDistribute(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.government")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドを実行する権限がありません"));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<red>使用方法: /fjegovernment distribute <プレイヤー> <金額> [理由]"));
            return false;
        }

        String playerName = args[1];
        Player targetPlayer = Bukkit.getPlayer(playerName);

        if (targetPlayer == null) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<red>プレイヤーが見つかりません: " + playerName));
            return true;
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

        // 理由を構築
        StringBuilder reasonBuilder = new StringBuilder();
        for (int i = 3; i < args.length; i++) {
            if (i > 3) reasonBuilder.append(" ");
            reasonBuilder.append(args[i]);
        }
        String reason = reasonBuilder.length() > 0 ? reasonBuilder.toString() : "Government distribution";

        if (governmentManager.distributeGovernmentFunds(
                targetPlayer.getUniqueId(), targetPlayer.getName(), amount, reason)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<green>" + playerName + " に " + 
                    plugin.getConfigManager().getCurrencySymbol() + amount + 
                    " を配分しました"));
            targetPlayer.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<green>政府から " + 
                    plugin.getConfigManager().getCurrencySymbol() + amount + 
                    " を受け取りました（" + reason + "）"));
            return true;
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>配分に失敗しました"));
            return true;
        }
    }

    /**
     * /fjegovernment ledger [タイプ] [件数] - 政府台帳を表示
     */
    private boolean handleLedger(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.government")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドを実行する権限がありません"));
            return true;
        }

        int limit = 10;
        String type = null;

        if (args.length > 1) {
            type = args[1];
        }
        if (args.length > 2) {
            try {
                limit = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(MiniMessage.miniMessage().deserialize(
                        plugin.getConfigManager().getMessagePrefix() + "<red>無効な件数です"));
                return true;
            }
        }

        List<GovernmentManager.LedgerEntry> entries;
        if (type != null && !type.isEmpty()) {
            entries = governmentManager.getLedgerByType(type, limit);
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<aqua>政府台帳（タイプ: " + type + "）"));
        } else {
            entries = governmentManager.getLedgerHistory(limit);
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<aqua>政府台帳（最新 " + limit + " 件）"));
        }

        if (entries.isEmpty()) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<gray>記録がありません"));
            return true;
        }

        for (GovernmentManager.LedgerEntry entry : entries) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<gray>" + entry.toString()));
        }

        return true;
    }

    /**
     * /fjegovernment tax [期間(分)] - 税金統計
     */
    private boolean handleTax(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.government")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドを実行する権限がありません"));
            return true;
        }

        long todayTax = governmentManager.getTodayTaxIncome();
        long hourTax = governmentManager.getTaxIncome(60);

        String currencySymbol = plugin.getConfigManager().getCurrencySymbol();
        sender.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfigManager().getMessagePrefix() + "<aqua>税金統計"));
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<gray>本日の税収: <green>" + currencySymbol + todayTax));
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<gray>直近1時間: <green>" + currencySymbol + hourTax));

        return true;
    }

    /**
     * /fjegovernment set <金額> - 政府残高を直接設定（リセット用）
     */
    private boolean handleSet(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("fj.government.set")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドを実行する権限がありません"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<red>使用方法: /fjegovernment set <金額>"));
            return false;
        }

        long amount;
        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>無効な金額です"));
            return true;
        }

        if (governmentManager.setGovernmentBalance(amount)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<green>政府残高を " + plugin.getConfigManager().getCurrencySymbol() + 
                    amount + " に設定しました"));
            return true;
        } else {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessagePrefix() + "<red>設定に失敗しました"));
            return true;
        }
    }

    /**
     * /fjegovernment info - 政府アカウント情報
     */
    private boolean handleInfo(org.bukkit.command.CommandSender sender) {
        if (!sender.hasPermission("fj.government")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドを実行する権限がありません"));
            return true;
        }

        long balance = governmentManager.getGovernmentBalance();
        String currencySymbol = plugin.getConfigManager().getCurrencySymbol();

        sender.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfigManager().getMessagePrefix() + "<aqua>政府アカウント情報"));
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<gray>名前: <green>" + governmentManager.getGovernmentName()));
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<gray>UUID: <green>" + governmentManager.getGovernmentUUID()));
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<gray>残高: <green>" + currencySymbol + balance));

        return true;
    }

    @Override
    public List<String> onTabComplete(org.bukkit.command.CommandSender sender, Command command,
                                     String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            if (sender.hasPermission("fj.government")) {
                completions.add("balance");
                completions.add("add");
                completions.add("withdraw");
                completions.add("distribute");
                completions.add("ledger");
                completions.add("tax");
                completions.add("info");
                if (sender.hasPermission("fj.government.set")) {
                    completions.add("set");
                }
            }
        } else if (args.length == 2 && "distribute".equalsIgnoreCase(args[0])) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                completions.add(player.getName());
            }
        }

        return completions;
    }
}
