package com.example.bgmplugin;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BgmManager implements Listener {

    private final BgmPlugin plugin;
    private final Random random = new Random();

    // プレイヤーごとのループタスク
    private final Map<UUID, BukkitTask> loopTasks = new ConcurrentHashMap<>();

    // 設定キャッシュ
    private float volume;
    private float pitch;

    // サウンドリスト
    private final List<SoundEntry> soundList = new ArrayList<>();

    /** サウンドエントリ（キーと長さのペア） */
    private record SoundEntry(String key, int durationTicks) {}

    public BgmManager(BgmPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        volume = (float) plugin.getConfig().getDouble("volume", 1.0);
        pitch  = (float) plugin.getConfig().getDouble("pitch", 1.0);

        plugin.logDebug("Config reloaded. Volume: " + volume + ", Pitch: " + pitch);

        // サウンドリストを読み込み
        soundList.clear();
        List<Map<?, ?>> sounds = plugin.getConfig().getMapList("sounds");
        for (Map<?, ?> entry : sounds) {
            String key = (String) entry.get("key");
            Object durObj = entry.get("duration-seconds");
            int duration = (durObj instanceof Number n) ? n.intValue() : 240;
            if (key != null && !key.isBlank()) {
                soundList.add(new SoundEntry(key, duration * 20));
            }
        }

        if (soundList.isEmpty()) {
            plugin.getLogger().warning("config.yml の sounds が空です！BGMが再生されません。");
        } else {
            plugin.logDebug(soundList.size() + " songs loaded into soundList.");
        }

        // リロード時は全プレイヤーのループを再起動
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            restartLoop(p);
        }
    }

    // -------------------------------------------------------
    // Geyserプレイヤー判定
    // -------------------------------------------------------

    private boolean isGeyserPlayer(Player player) {
        return player.getUniqueId().toString().startsWith("00000000-0000-0000-");
    }

    // -------------------------------------------------------
    // イベントハンドラ
    // -------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.logDebug("Player joined: " + player.getName() + " (" + player.getUniqueId() + ")");

        if (isGeyserPlayer(player)) {
            plugin.logDebug(player.getName() + " is a Geyser player. Starting loop directly.");
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> startLoop(player), 300L);
            return;
        }

        plugin.logDebug("Sending resource pack request to " + player.getName());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.getResourcePackUtil().sendPack(player);
        }, 300L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.logDebug("Player quit: " + event.getPlayer().getName());
        cancelLoop(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        if (isGeyserPlayer(player)) return;

        plugin.logDebug("ResourcePack status for " + player.getName() + ": " + event.getStatus());

        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                plugin.logDebug(player.getName() + " successfully loaded the resource pack. Starting BGM.");
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> startLoop(player), 10L);
            }
            case DECLINED -> {
                plugin.logDebug(player.getName() + " declined the resource pack.");
                cancelLoop(player.getUniqueId());
            }
            case FAILED_DOWNLOAD, DISCARDED -> {
                plugin.logDebug(player.getName() + " failed to load or discarded the pack: " + event.getStatus());
                cancelLoop(player.getUniqueId());
            }
            default -> {}
        }
    }

    // -------------------------------------------------------
    // BGM再生ループ管理
    // -------------------------------------------------------

    /**
     * ランダムに1曲選んで再生し、その曲が終わったら次をランダム選択して繰り返す。
     */
    private void startLoop(Player player) {
        plugin.logDebug("Starting BGM loop for " + player.getName());
        cancelLoop(player.getUniqueId());
        if (soundList.isEmpty()) return;
        playNext(player);
    }

    private void playNext(Player player) {
        if (!player.isOnline()) {
            plugin.logDebug("Aborting playNext for " + player.getName() + " (player offline)");
            cancelLoop(player.getUniqueId());
            return;
        }

        // ランダムに1曲選ぶ
        SoundEntry entry = soundList.get(random.nextInt(soundList.size()));

        // 再生
        playSound(player, entry.key());

        plugin.logDebug(player.getName() + " now playing: " + entry.key() + " (Duration: " + (entry.durationTicks() / 20) + "s)");

        // 曲が終わったら次を再生
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> playNext(player),
                entry.durationTicks()
        );

        loopTasks.put(player.getUniqueId(), task);
    }

    private void restartLoop(Player player) {
        if (loopTasks.containsKey(player.getUniqueId())) {
            plugin.logDebug("Restarting loop for " + player.getName() + " due to reload.");
            startLoop(player);
        }
    }

    private void cancelLoop(UUID uuid) {
        BukkitTask task = loopTasks.remove(uuid);
        if (task != null && !task.isCancelled()) {
            plugin.logDebug("Cancelled task for UUID: " + uuid);
            task.cancel();
        }
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null && player.isOnline()) {
            // ループ停止時にクライアント側のBGMも停止させる
            player.stopSound(SoundStop.source(Sound.Source.MUSIC));
        }
    }

    private void playSound(Player player, String key) {
        try {
            // 重複再生を防止するため、新しい曲を再生する前に現在のBGM（MUSICソース）を停止
            player.stopSound(SoundStop.source(Sound.Source.MUSIC));

            Sound sound = Sound.sound(
                    Key.key(key),
                    Sound.Source.MUSIC,
                    volume,
                    pitch
            );
            player.playSound(sound);
        } catch (Exception e) {
            plugin.getLogger().warning("サウンド再生エラー (" + player.getName() + "): " + e.getMessage());
        }
    }

    public void cancelAll() {
        // 全プレイヤーに対してループのキャンセルと音の停止を行う
        loopTasks.keySet().forEach(this::cancelLoop);
    }

    public boolean isLooping(UUID uuid) {
        return loopTasks.containsKey(uuid);
    }
}
