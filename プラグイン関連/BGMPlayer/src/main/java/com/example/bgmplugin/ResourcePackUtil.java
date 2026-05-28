package com.example.bgmplugin;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.entity.Player;

import java.net.URI;
import java.util.UUID;

public class ResourcePackUtil {

    private final BgmPlugin plugin;

    private String url;
    private String sha1;
    private boolean force;

    public ResourcePackUtil(BgmPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        url   = plugin.getConfig().getString("resource-pack-url", "");
        sha1  = plugin.getConfig().getString("resource-pack-sha1", "");
        force = plugin.getConfig().getBoolean("force-resource-pack", true);
    }

    /**
     * プレイヤーにリソースパックを送信する。
     * Paper 1.21 の Adventure API (ResourcePackRequest) を使用。
     */
    public void sendPack(Player player) {
        plugin.logDebug("Preparing to send resource pack to " + player.getName());
        plugin.logDebug("URL: " + url + " | Force: " + force);

        if (url == null || url.isBlank()) {
            plugin.getLogger().warning("resource-pack-url が設定されていません！");
            return;
        }

        try {
            ResourcePackInfo.Builder builder = ResourcePackInfo.resourcePackInfo()
                    .id(UUID.nameUUIDFromBytes(url.getBytes()))
                    .uri(URI.create(url));

            if (!sha1.isBlank()) {
                builder.hash(sha1);
            }

            ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                    .packs(builder.build())
                    .required(force)
                    .prompt(net.kyori.adventure.text.Component.text("§6BGMを楽しむためにリソースパックを適用してください！"))
                    .build();

            player.sendResourcePacks(request);

        } catch (Exception e) {
            plugin.getLogger().severe("リソースパック送信中にエラーが発生しました: " + e.getMessage());
        }
    }
}
