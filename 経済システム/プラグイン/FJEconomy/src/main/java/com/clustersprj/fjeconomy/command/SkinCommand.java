package com.clustersprj.fjeconomy.command;

import com.clustersprj.fjeconomy.FJEconomy;
import com.clustersprj.fjeconomy.skin.SkinManager;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * /skin コマンドの実行およびタブ補完を処理するクラスです。
 * <p>
 * マーケットプレイスで購入済みのスキンの一覧表示と、使用中スキンの切り替えを行います。
 * コマンド引数（番号）から解決したnftIdは、必ず {@link SkinManager} 側でDB照会し直して
 * 所有権を再検証してから反映します（未購入スキンをクライアント入力だけで使えないようにするため）。
 * </p>
 */
public class SkinCommand implements CommandExecutor, TabCompleter {

    private final FJEconomy plugin;
    private final SkinManager skinManager;

    public SkinCommand(FJEconomy plugin, SkinManager skinManager) {
        this.plugin = plugin;
        this.skinManager = skinManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>このコマンドはプレイヤーのみ実行できます"));
            return true;
        }

        if (args.length == 0) {
            return handleInfo(player);
        }

        switch (args[0].toLowerCase()) {
            case "list":
                return handleList(player);
            case "use":
                return handleUse(player, args);
            case "reset":
                return handleReset(player);
            default:
                player.sendMessage(MiniMessage.miniMessage().deserialize(
                        plugin.getConfigManager().getMessagePrefix() + "<red>使用方法: /skin [list|use <番号>|reset]"));
                return true;
        }
    }

    private boolean handleInfo(Player player) {
        Optional<SkinManager.SkinTexture> texture = skinManager.getActiveSkinTexture(player.getUniqueId());
        if (texture.isPresent()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() +
                    "<green>マーケットプレイスのスキンを使用中です (" + texture.get().model() + ")"));
        } else {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<gray>現在マーケットプレイスのスキンは使用していません"));
        }
        player.sendMessage(MiniMessage.miniMessage().deserialize("<gray>一覧: /skin list, 変更: /skin use <番号>, 元に戻す: /skin reset"));
        return true;
    }

    private boolean handleList(Player player) {
        List<SkinManager.OwnedSkin> skins = skinManager.listOwnedSkins(player.getUniqueId());
        if (skins.isEmpty()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>マーケットプレイスで購入したスキンがありません"));
            return true;
        }

        player.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfigManager().getMessagePrefix() + "<aqua>所有スキン一覧:"));
        for (int i = 0; i < skins.size(); i++) {
            SkinManager.OwnedSkin skin = skins.get(i);
            String marker = skin.active() ? "<green>[使用中] " : "<gray>";
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "  " + marker + "#" + (i + 1) + " <white>" + skin.title() + " <gray>(" + skin.model() + ")"));
        }
        return true;
    }

    private boolean handleUse(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>使用方法: /skin use <番号>（/skin list で確認）"));
            return true;
        }

        List<SkinManager.OwnedSkin> skins = skinManager.listOwnedSkins(player.getUniqueId());
        int index;
        try {
            index = Integer.parseInt(args[1]) - 1;
        } catch (NumberFormatException e) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>番号は数値で指定してください"));
            return true;
        }

        if (index < 0 || index >= skins.size()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>該当するスキンが見つかりません"));
            return true;
        }

        int nftId = skins.get(index).nftId();
        if (!skinManager.setActiveSkin(player.getUniqueId(), nftId)) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getMessagePrefix() + "<red>このスキンは使用できません"));
            return true;
        }

        skinManager.getActiveSkinTexture(player.getUniqueId()).ifPresent(texture -> {
            PlayerProfile profile = player.getPlayerProfile();
            profile.setProperty(new ProfileProperty("textures", texture.textureValue(), texture.textureSignature()));
            player.setPlayerProfile(profile);
        });
        refreshSkinForOthers(player);

        player.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfigManager().getMessagePrefix() +
                "<green>スキンを変更しました。<gray>（自分の一人称視点に反映されない場合は再接続してください）"));
        return true;
    }

    /**
     * /skin reset - マーケットプレイスのスキンの使用をやめ、元々のスキン（Mojang側で解決された
     * 本来のスキン、またはBedrockプレイヤーの実機スキン）に戻す。
     * ログイン時に {@link com.clustersprj.fjeconomy.skin.SkinListener} がキャッシュしておいた
     * 上書き前のtexturesプロパティを復元する。キャッシュが無い場合（プラグイン導入後に一度も
     * 再ログインしていない等）はプロパティ自体を削除し、次回ログイン時にMojang側の値へ戻す。
     */
    private boolean handleReset(Player player) {
        skinManager.clearActiveSkin(player.getUniqueId());

        PlayerProfile profile = player.getPlayerProfile();
        profile.removeProperty("textures");
        skinManager.getOriginalTexture(player.getUniqueId()).ifPresent(profile::setProperty);
        player.setPlayerProfile(profile);
        refreshSkinForOthers(player);

        player.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfigManager().getMessagePrefix() +
                "<green>元のスキンに戻しました。<gray>（自分の一人称視点に反映されない場合は再接続してください）"));
        return true;
    }

    /**
     * 他プレイヤーから見た外見をベストエフォートで即時更新する。
     * vanillaクライアントには「自分自身を強制的に再スポーンさせる」公式APIが無いため、
     * 変更した本人の一人称視点は再接続まで反映されない場合がある（既知の制約）。
     */
    private void refreshSkinForOthers(Player changed) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(changed)) continue;
            viewer.hidePlayer(plugin, changed);
            viewer.showPlayer(plugin, changed);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            org.bukkit.util.StringUtil.copyPartialMatches(args[0], List.of("list", "use", "reset"), completions);
        }
        return completions;
    }
}
