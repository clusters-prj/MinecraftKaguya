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
 * /fjegovernment コマンドの実行およびタブ補完を管理するクラスです。
 * <p>
 * 政府用共通アカウントの残高確認、資金の追加・引き出し、給付金（資金分配）、
 * 税収統計の確認、および監査用の政府取引台帳（Ledger）の表示機能を提供します。
 * </p>
 * 権限: fj.government（管理者限定、setコマンドのみ fj.government.set が必要）
 */
public class GovernmentCommand implements CommandExecutor, TabCompleter {

    /** プラグインのメインインスタンス */
    private final FJEconomy plugin;
    
    /** 政府資金や台帳を管理するマネージャー */
    private final GovernmentManager governmentManager;

    /**
     * GovernmentCommandの新しいインスタンスを構築します。
     *
     * @param plugin プラグインのメインインスタンス
     * @param governmentManager 政府マネージャーのインスタンス
     */
    public GovernmentCommand(FJEconomy plugin, GovernmentManager governmentManager) {
        this.plugin = plugin;
        this.governmentManager = governmentManager;
    }

    /**
     * /fjegovernment コマンドが実行された際に呼び出されます。
     * 各サブコマンド（balance, add, withdraw, distribute, ledger, tax, set, info）へ処理を振り分けます。
     *
     * @param sender コマンドの実行者（プレイヤー、コンソールなど）
     * @param command 実行されたコマンド
     * @param label コマンドのエイリアス名
     * @param args コマンドに渡された引数の配列
     * @return コマンドの処理が正常に行われた場合は true、それ以外は false
     */
    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, Command command,
                            String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + 
                    "<red>/fjegovernment <subcommand>"));
            return true;
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
                return true;
        }
    }

    /**
     * 政府アカウントの現在の残高を表示します。
     *
     * @param sender コマンド送信者
     * @return 処理が完了した場合は true
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
     * 政府アカウントに手動で指定金額の資金を追加（発行）します。
     *
     * @param sender コマンド送信者
     * @param args 引数（args[1]: 金額, args[2...]: 理由）
     * @return 処理が完了した場合は true
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
            return true;
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
     * 政府アカウントから指定金額の資金を引き出します（回収・消却）。
     *
     * @param sender コマンド送信者
     * @param args 引数（args[1]: 金額, args[2...]: 理由）
     * @return 処理が完了した場合は true
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
            return true;
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
     * 政府資金を特定のオンラインプレイヤーに対して給付金として配分します。
     *
     * @param sender コマンド送信者
     * @param args 引数（args[1]: プレイヤー名, args[2]: 金額, args[3...]: 理由）
     * @return 処理が完了した場合は true
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
            return true;
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
     * 政府アカウントの入出金台帳（ログ履歴）を表示します。オプションで取引タイプや表示件数を絞り込めます。
     *
     * @param sender コマンド送信者
     * @param args 引数（args[1]: タイプ（省略可）, args[2]: 取得件数（省略可））
     * @return 処理が完了した場合は true
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
     * 本日および直近1時間の税収統計（税額の合計値）を表示します。
     *
     * @param sender コマンド送信者
     * @param args コマンド引数
     * @return 処理が完了した場合は true
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
     * 政府アカウントの残高を直接上書き設定します（トラブルシューティングやデバッグ向け）。
     *
     * @param sender コマンド送信者
     * @param args 引数（args[1]: 設定する新規残高）
     * @return 処理が完了した場合は true
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
            return true;
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
     * 政府アカウントの基本情報（アカウント名、UUID、現在の残高）を表示します。
     *
     * @param sender コマンド送信者
     * @return 処理が完了した場合は true
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

    /**
     * コマンドのタブ入力時に、サブコマンドや引数の補完候補リストを提供します。
     *
     * @param sender タブキーを押した送信者
     * @param command 対象のコマンド
     * @param alias コマンドのエイリアス
     * @param args 入力中の引数
     * @return 補完候補の文字列リスト
     */
    @Override
    public List<String> onTabComplete(org.bukkit.command.CommandSender sender, Command command,
                                     String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        List<String> subcommands = new ArrayList<>();

        if (args.length == 1) {
            if (sender.hasPermission("fj.government")) {
                subcommands.add("balance");
                subcommands.add("add");
                subcommands.add("withdraw");
                subcommands.add("distribute");
                subcommands.add("ledger");
                subcommands.add("tax");
                subcommands.add("info");
                if (sender.hasPermission("fj.government.set")) {
                    subcommands.add("set");
                }
            }
            org.bukkit.util.StringUtil.copyPartialMatches(args[0], subcommands, completions);
        } else if (args.length == 2 && "distribute".equalsIgnoreCase(args[0])) {
            List<String> players = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                players.add(player.getName());
            }
            org.bukkit.util.StringUtil.copyPartialMatches(args[1], players, completions);
        }

        java.util.Collections.sort(completions);
        return completions;
    }
}