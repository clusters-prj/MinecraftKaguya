package com.clusters_prj.wra.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import org.bukkit.configuration.ConfigurationSection;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;


public class WorldGuardHandler {

    private final JavaPlugin plugin;
    private final WorldGuard worldGuard;

    public WorldGuardHandler(JavaPlugin plugin) {
        this.plugin = plugin;
        this.worldGuard = WorldGuard.getInstance();
    }

    /**
     * WorldGuard領域を作成
     * @return 成功時true、失敗時false
     */
    public boolean createRegion(String worldName, String regionId, String playerUuidStr,
                                 int x1, int y1, int z1, int x2, int y2, int z2) {
        try {
            // ワールドの存在確認
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("ワールドが見つかりません: " + worldName);
                return false;
            }

            // 座標の大小を整理
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            int minZ = Math.min(z1, z2);
            int maxZ = Math.max(z1, z2);

            // WorldEdit座標に変換
            BlockVector3 pos1 = BlockVector3.at(minX, minY, minZ);
            BlockVector3 pos2 = BlockVector3.at(maxX, maxY, maxZ);

            // RegionManagerを取得
            RegionManager regionManager = worldGuard.getPlatform()
                    .getRegionContainer()
                    .get(BukkitAdapter.adapt(world));

            if (regionManager == null) {
                plugin.getLogger().warning("RegionManagerが取得できません: " + worldName);
                return false;
            }

            // 既存の領域と重複チェック
            if (plugin.getConfig().getBoolean("settings.enable-overlap-check", true)) {
                ProtectedCuboidRegion testRegion = new ProtectedCuboidRegion(regionId, pos1, pos2);
                if (regionManager.getApplicableRegions(testRegion).size() > 0) {
                    plugin.getLogger().warning("領域が重複しています: " + regionId);
                    return false;
                }
            }

            // 領域オブジェクトを作成
            ProtectedCuboidRegion region = new ProtectedCuboidRegion(regionId, pos1, pos2);

            // オーナーを設定（政府 UUID: 00000000-0000-0000-0000-000000000001）
            UUID govUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
            region.getOwners().addPlayer(govUuid);

            // プレイヤーをメンバーに追加（使用権のみ）
            UUID playerUuid = UUID.fromString(playerUuidStr);
            region.getMembers().addPlayer(playerUuid);

            // デフォルトフラグを設定
            applyDefaultFlags(region);

            // 領域を登録
            regionManager.addRegion(region);

            plugin.getLogger().info("領域を作成しました: " + regionId + " (" + worldName + ")");
            return true;

        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("UUID形式が不正です: " + playerUuidStr);
            return false;
        } catch (Exception e) {
            plugin.getLogger().warning("WorldGuard処理エラー: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * デフォルトフラグを適用
     */
    private void applyDefaultFlags(ProtectedCuboidRegion region) {
        // 1. config.yml から "default-flags" セクションを取得
        ConfigurationSection flagSection = plugin.getConfig().getConfigurationSection("default-flags");
        if (flagSection == null) {
            return; // セクションがなければ何もしない
        }

        // 2. 設定されているキー（build, pvp など）をループで処理
        for (String flagName : flagSection.getKeys(false)) {
            String value = flagSection.getString(flagName);
            if (value == null) continue;

            // 3. 文字列（"build", "pvp" 等）から WorldGuard の Flag オブジェクトを探す
            Flag<?> fuzzyFlag = Flags.fuzzyMatchFlag(WorldGuard.getInstance().getFlagRegistry(), flagName);
            
            // StateFlag（ALLOW/DENYを設定するフラグ）かチェック
            if (fuzzyFlag instanceof StateFlag) {
                StateFlag stateFlag = (StateFlag) fuzzyFlag;
                
                // コンフィグの値に応じて ALLOW または DENY をマッピング
                StateFlag.State state;
                if (value.equalsIgnoreCase("ALLOW")) {
                    state = StateFlag.State.ALLOW;
                } else if (value.equalsIgnoreCase("DENY")) {
                    state = StateFlag.State.DENY;
                } else {
                    continue; // ALLOW/DENY 以外（不適切な値）ならスキップ
                }

                // 4. 領域にフラグをセット
                region.setFlag(stateFlag, state);
            }
        }
    }
}