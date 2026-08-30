package com.clustersprj.fjeconomy.skin;

import com.clustersprj.fjeconomy.FJEconomy;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * ログイン確定前にマーケットプレイスで「使用中」のスキンをJavaプロフィールへ焼き込むリスナー。
 * <p>
 * ここで（＝クライアントの実際のスポーン前に）テクスチャを確定させることで、Java観測者にも
 * Bedrock観測者（Geyserの既定のJava→Bedrock変換経由）にも初回スポーン時から正しいスキンが見える。
 * Floodgate経由のBedrockプレイヤーも {@link AsyncPlayerPreLoginEvent#getUniqueId()} が
 * 決定論的なUUIDを透過的に返すため、Java/Bedrockで分岐する必要はない
 * （{@code LinkManager} が player.getUniqueId() を一律に扱っているのと同じ前提）。
 * </p>
 */
public class SkinListener implements Listener {

    private final SkinManager skinManager;

    public SkinListener(FJEconomy plugin) {
        this.skinManager = plugin.getSkinManager();
    }

    @EventHandler
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        skinManager.getActiveSkinTexture(event.getUniqueId()).ifPresent(texture -> {
            PlayerProfile profile = event.getPlayerProfile();
            profile.setProperty(new ProfileProperty("textures", texture.textureValue(), texture.textureSignature()));
        });
    }
}
