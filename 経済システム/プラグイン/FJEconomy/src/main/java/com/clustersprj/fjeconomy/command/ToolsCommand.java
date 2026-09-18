package com.clustersprj.fjeconomy.command;

import com.clustersprj.fjeconomy.tool.ToolGUI;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /tools コマンドの実行を処理するクラスです。
 * <p>
 * マーケットプレイスで購入済みの便利アイテム(ツールアイテム)一覧GUIを開くだけのコマンドです。
 * ツールバー(ホットバー)からこのコマンドを呼び出す導線は、サーバー上のSkriptスクリプト側が
 * 担当します(このプラグインはコマンドの公開のみを担う)。
 * </p>
 */
public class ToolsCommand implements CommandExecutor {

    private final ToolGUI toolGui;

    public ToolsCommand(ToolGUI toolGui) {
        this.toolGui = toolGui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return true;
        }

        toolGui.openGui(player);
        return true;
    }
}
