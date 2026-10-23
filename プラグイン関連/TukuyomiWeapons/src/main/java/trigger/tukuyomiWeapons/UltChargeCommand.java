package trigger.tukuyomiWeapons;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /ultcharge <プレイヤー> [%]: そのプレイヤーのウルトのチャージを増やす(省略すると100%=満タン)。動作確認用で、コンソール専用。
 * 発動後20秒の加算停止や、ウルトをホットバーに持っている条件は、通常の加算と同じように適用される。
 */
public final class UltChargeCommand implements CommandExecutor {
    private final TukuyomiWeapons plugin;

    UltChargeCommand(TukuyomiWeapons plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player) { // チート防止: プレイヤーからは使えない(op でも)
            sender.sendMessage("このコマンドはコンソール専用です。");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage("使い方: /ultcharge <プレイヤー> [%]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage("プレイヤーが見つかりません: " + args[0]);
            return true;
        }
        double percent = UltimateManager.FULL;
        if (args.length >= 2) {
            try {
                percent = Double.parseDouble(args[1]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("数値(%)で指定してください: " + args[1]);
                return true;
            }
        }
        plugin.ult.addChargePercent(target, percent);
        return true;
    }
}
