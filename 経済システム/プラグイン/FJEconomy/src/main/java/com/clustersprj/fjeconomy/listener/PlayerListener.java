package com.clustersprj.fjeconomy.listener;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.economy.EconomyManager;
import com.clustersprj.fjeconomy.LoginBonusManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * プレイヤーの行動に関するイベントを監視し、経済システムや特典処理へ仲介するリスナークラスです。
 * <p>
 * 主にプレイヤーのログイン時におけるアカウントの自動生成や、
 * ログインボーナスのチェックおよび配布処理をトリガーする責務を持ちます。
 * </p>
 */
public class PlayerListener implements Listener {
    private final FJEconomy plugin;
    private final EconomyManager economyManager;
    private final LoginBonusManager loginBonusManager;

    /**
     * PlayerListener を構築します。
     * プラグインのメインクラスから経済マネージャーおよびログインボーナスマネージャーを取得します。
     *
     * @param plugin FJEconomy プラグインのメインクラスインスタンス
     */
    public PlayerListener(FJEconomy plugin) {
        this.plugin = plugin;
        // EconomyManager は FJEconomy から取得するように変更
        this.economyManager = plugin.getEconomyManager(); // FJEconomyにgetterを追加するか、直接インスタンスを渡す
        this.loginBonusManager = plugin.getLoginBonusManager();
    }

    /**
     * プレイヤーがサーバーに参加した際に呼び出されるイベントハンドラです。
     * <p>
     * ログインしたプレイヤーの経済アカウントが存在するか確認し、なければ自動的に新規作成します。
     * その後、該当プレイヤーに対するログインボーナスの受給資格をチェックし、条件を満たしていれば付与します。
     * </p>
     * <p>
     * <strong>注意:</strong> 他のプラグインによるログイン制御（キックやキャンセルなど）が
     * 確定した後の最終的な状態を監視するため、イベント優先度は {@link EventPriority#MONITOR} に設定されています。
     * </p>
     *
     * @param event プレイヤーの参加イベントオブジェクト
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        economyManager.ensurePlayerAccount(
                event.getPlayer().getUniqueId(),
                event.getPlayer().getName());
        
        // ログインボーナスをチェック
        loginBonusManager.checkAndGrantLoginBonus(
                event.getPlayer().getUniqueId(), event.getPlayer().getName());

        plugin.getLogger().info("プレイヤー " + event.getPlayer().getName() + " のアカウントを確認しました");
    }
}
