package com.clustersprj.fjeconomy.command;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.economy.EconomyManager;
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
 * 一般プレイヤーおよび管理者が使用する主要コマンド（/fj, /fjeadmin）を一括処理・登録するクラスです。
 * <p>
 * 一般向け（残高表示、送金）、および管理用（お金の直接付与、没収、残高の強制設定、リロード）など、
 * システム全体の基本マネーハンドリングと連携しています。
 * </p>
 */
public class CommandManager implements CommandExecutor, TabCompleter {

    /** プラグインのメインクラス */
    private final FJEconomy plugin;
    
    /** 経済システムのコア処理（データの取得・更新・送金など）を担うマネージャー */
    private final EconomyManager economyManager;

    /**
     * CommandManagerの新しいインスタンスを構築します。
     * 内部でEconomyManagerも初期化されます。
     *
     * @param plugin プラグインのメインインスタンス
     */
    public CommandManager(FJEconomy plugin) {
        this.plugin = plugin;
        this.economyManager = new EconomyManager(plugin);
    }

    /**
     * Bukkitシステムへ各種コマンド（fj、fjeadmin）のExecutorおよびTabCompleterを登録します。
     */
    public void registerCommands() {
        plugin.getCommand("fj").setExecutor(this);
        plugin.getCommand("fj").setTabCompleter(this);
        plugin.getCommand("fjeadmin").setExecutor(this);
        plugin.getCommand("fjeadmin").setTabCompleter(this);
    }

    /**
     * 登録されたコマンド（/fj, /fjeadmin）が実行されたときにトリガーされます。
     * サブコマンドが存在しない場合は自動的にヘルプを表示し、
     * 存在する場合は各コマンド処理に適切なパラメーターを転送します。
     *
     * @param sender コマンドの送信者（プレイヤーまたはコンソール）
     * @param command 実行されたコマンド
     * @param label コマンドのメインラベル（エイリアス）
     * @param args コマンド引数の配列
     * @return 処理が正常に実行された場合は true
     */
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
        } else if ("help".equalsIgnoreCase(subcommand)) {
            return handleHelp(sender);
        }

        sender.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfigManager().getMessagePrefix() + "<red>不明なコマンド: " + subcommand));
        return true;
    }

    /**
     * /fj balance (または /fj bal) - プレイヤー自身の残高を確認してフォーマット済みのテキストで表示します。
     *
     * @param sender コマンド送信者（プレイヤーのみ許可）
     * @param args コマンド引数
     * @return 処理が完了した場合は true
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
     * /fj pay <プレイヤー名> <金額> - 他のプレイヤーの口座へお金を送金します。
     * 送金側は十分な残高が必要であり、相手のオンライン状態に関係なくUUIDの特定に成功すれば送金可能です。
     *
     * @param sender コマンド送信者（送金側プレイヤー）
     * @param args 引数（args[1]: 送金先プレイヤー名, args[2]: 送金金額）
     * @return 処理が完了した場合は true
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
     * /fjeadmin give <プレイヤー名> <金額> - 管理者権限を用いて特定のプレイヤーに無からお金を給付します。
     *
     * @param sender コマンド送信者（管理者）
     * @param args 引数（args[1]: 対象プレイヤー, args[2]: 付与額）
     * @return 処理が完了した場合は true
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
     * /fjeadmin take <プレイヤー名> <金額> - 管理者権限を用いて特定のプレイヤーの口座からお金を没収します。
     *
     * @param sender コマンド送信者（管理者）
     * @param args 引数（args[1]: 対象プレイヤー, args[2]: 没収額）
     * @return 処理が完了した場合は true
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
     * /fjeadmin set <プレイヤー名> <金額> - 管理者権限でプレイヤーの残高を指定額に直接上書きします。
     *
     * @param sender コマンド送信者（管理者）
     * @param args 引数（args[1]: 対象プレイヤー, args[2]: 強制設定額）
     * @return 処理が完了した場合は true
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
     * /fjeadmin reload - 設定ファイルの再読み込みやデータベース設定の変更検知、必要に応じた接続プール等の再構築を行います。
     *
     * @param sender コマンド送信者（リロード権限所持者）
     * @return 処理が完了した場合は true
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
     * 実行者が利用可能なコマンドのヘルプテキストを色分けしてチャット欄に表示します。
     *
     * @param sender コマンド送信者
     * @return 常に true
     */
    private boolean handleHelp(org.bukkit.command.CommandSender sender) {
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<aqua>=== FJ Economy ヘルプ ==="));
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>/fj bal<reset> - 残高確認"));
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>/fj pay <プレイヤー> <金額><reset> - 送金"));
        
        if (sender.hasPermission("fj.admin")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>/fjeadmin give <プレイヤー> <金額><reset> - 付与(管理者)"));
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>/fjeadmin take <プレイヤー> <金額><reset> - 没収(管理者)"));
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>/fjeadmin set <プレイヤー> <金額><reset> - 設定(管理者)"));
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>/fjeadmin reload<reset> - リロード(管理者)"));
        }
        return true;
    }

    /**
     * コマンド（/fj, /fjeadmin）を入力中にタブキーを押した際、引数（サブコマンドやプレイヤー名など）を適切に自動補完します。
     *
     * @param sender 送信者
     * @param command コマンド
     * @param alias エイリアス
     * @param args 現在入力されている引数配列
     * @return 補完候補のリスト
     */
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
        // 2つ目の引数（プレイヤー名など）の補完
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