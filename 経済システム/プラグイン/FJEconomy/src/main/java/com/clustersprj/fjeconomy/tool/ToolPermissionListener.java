package com.clustersprj.fjeconomy.tool;

import com.clustersprj.fjeconomy.FJEconomy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionAttachment;

/**
 * ログイン中のプレイヤーに、所有しているツールアイテムの tool_code ごとに
 * {@code fje.tool.<tool_code>} パーミッションを付与する。
 * <p>
 * ツールの実際の挙動(右クリックで何が起こるか)はSkript側が実装するが、アイテム自体は
 * バニラの複製バグ(ホッパー/ロバ/シュルカー複製等)や他プレイヤーへの譲渡で増やされうる。
 * Skript側の挙動判定がアイテム名の一致だけに頼っていると、複製・転売されたコピーでも
 * 誰でも機能を使えてしまうため、この権限を「本当に購入したプレイヤーか」の実行時チェックとして
 * Skript側の判定に必ず併用してもらう(アイテム名一致 かつ
 * {@code player has permission "fje.tool.<tool_code>"})ことを前提としている。
 * </p>
 * <p>
 * 外部権限プラグイン(LuckPerms等)には依存せず、ログインのたびに {@link PermissionAttachment}
 * でセッション限りの権限を付与し直す(Bukkitの標準権限は既定で永続化しないため)。
 * </p>
 */
public class ToolPermissionListener implements Listener {

    private final FJEconomy plugin;
    private final ToolManager toolManager;

    public ToolPermissionListener(FJEconomy plugin) {
        this.plugin = plugin;
        this.toolManager = plugin.getToolManager();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PermissionAttachment attachment = player.addAttachment(plugin);
        for (ToolManager.OwnedTool tool : toolManager.listOwnedTools(player.getUniqueId())) {
            attachment.setPermission("fje.tool." + tool.toolCode(), true);
        }
    }
}
