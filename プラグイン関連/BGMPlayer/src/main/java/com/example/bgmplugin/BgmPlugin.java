package com.example.bgmplugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class BgmPlugin extends JavaPlugin {

    private BgmManager bgmManager;
    private ResourcePackUtil resourcePackUtil;
    private Logger debugLogger;
    private FileHandler debugFileHandler;
    private boolean debugEnabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadDebugConfig();
        resourcePackUtil = new ResourcePackUtil(this);
        bgmManager = new BgmManager(this);

        getServer().getPluginManager().registerEvents(bgmManager, this);
        getLogger().info("BgmPlugin enabled!");
    }

    @Override
    public void onDisable() {
        if (bgmManager != null) {
            bgmManager.cancelAll();
        }
        closeDebugLogger();
        getLogger().info("BgmPlugin disabled.");
    }

    private void reloadDebugConfig() {
        closeDebugLogger();
        debugEnabled = getConfig().getBoolean("debug", false);
        
        if (debugEnabled) {
            try {
                if (!getDataFolder().exists()) getDataFolder().mkdirs();
                File logFile = new File(getDataFolder(), "debug.log");
                
                debugFileHandler = new FileHandler(logFile.getAbsolutePath(), true);
                // ログを1行で見やすくするためのカスタムフォーマット
                debugFileHandler.setFormatter(new java.util.logging.Formatter() {
                    @Override
                    public String format(java.util.logging.LogRecord record) {
                        return String.format("[%1$tF %1$tT] [%2$s] %3$s%n",
                                record.getMillis(), record.getLevel(), record.getMessage());
                    }
                });
                debugLogger = Logger.getLogger("BgmPluginDebug");
                
                // 既存のハンドラがあれば削除（リロード時の二重出力防止）
                for (java.util.logging.Handler h : debugLogger.getHandlers()) {
                    debugLogger.removeHandler(h);
                }

                debugLogger.setUseParentHandlers(false); // 通常のコンソールログには流さない
                debugLogger.addHandler(debugFileHandler);
                
                getLogger().info("Debug mode enabled. Logging to: " + logFile.getPath());
                logDebug("=== Debug Session Started ===");
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Could not create debug log file", e);
                debugEnabled = false;
            }
        }
    }

    private void closeDebugLogger() {
        if (debugLogger != null && debugFileHandler != null) {
            debugLogger.removeHandler(debugFileHandler);
        }
        if (debugFileHandler != null) {
            debugFileHandler.close();
            debugFileHandler = null;
        }
    }

    /**
     * デバッグログを記録する。
     * debug: true の場合はプラグインフォルダの debug.log へ、
     * false の場合はコンソール（ターミナル）へ出力する。
     */
    public void logDebug(String message) {
        if (debugEnabled && debugLogger != null) {
            debugLogger.info(message);
        } else {
            getLogger().info("[DEBUG] " + message);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "bgmreload" -> {
                reloadConfig();
                reloadDebugConfig();
                resourcePackUtil.reload();
                bgmManager.reload();
                sender.sendMessage("§a[BGM] 設定をリロードしました。");
                return true;
            }
            case "bgmresend" -> {
                if (args.length == 0) {
                    if (sender instanceof Player p) {
                        resourcePackUtil.sendPack(p);
                        sender.sendMessage("§a[BGM] リソースパックを再送信しました。");
                    } else {
                        sender.sendMessage("§c[BGM] プレイヤー名を指定してください。");
                    }
                } else {
                    Player target = getServer().getPlayer(args[0]);
                    if (target == null) {
                        sender.sendMessage("§c[BGM] プレイヤーが見つかりません: " + args[0]);
                    } else {
                        resourcePackUtil.sendPack(target);
                        sender.sendMessage("§a[BGM] " + target.getName() + " にリソースパックを再送信しました。");
                    }
                }
                return true;
            }
        }
        return false;
    }

    public BgmManager getBgmManager() {
        return bgmManager;
    }

    public ResourcePackUtil getResourcePackUtil() {
        return resourcePackUtil;
    }
}
