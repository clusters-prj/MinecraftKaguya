package com.clustersprj.fjeskinbridge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共有MariaDB(fjeconomyデータベース)へ直接接続し、Bedrock観測者向けに上書きすべきスキンPNGを
 * 取得するクライアント。Web(fjew)・FJEconomy(Paper)と同様、マーケットプレイスへHTTP APIを
 * 一切呼び出さず、DBを直読みするだけで完結させる。
 */
public class SkinDbClient {

    private final HikariDataSource dataSource;
    private final long cacheTtlMillis;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Bedrock観測者向けにGeyser APIへ渡すスキンの生データ */
    public record SkinPayload(byte[] pngData, String model) {
    }

    private record CacheEntry(Optional<SkinPayload> payload, long fetchedAtMillis) {
    }

    public SkinDbClient(ExtensionConfig config) {
        // ★ 先にドライバクラスを強制的にロードしてクラスローダーに認識させる
        // (FJEconomyのDatabaseManagerと同じ理由。pom.xmlのshadedPatternに合わせたクラス名)
        try {
            Class.forName("com.clustersprj.fjeskinbridge.libs.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("MariaDB JDBC Driver が見つかりません。shadeの設定を確認してください。", e);
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDriverClassName("com.clustersprj.fjeskinbridge.libs.mariadb.jdbc.Driver");
        hikariConfig.setJdbcUrl(config.getDatabaseUrl());
        hikariConfig.setUsername(config.getDatabaseUsername());
        hikariConfig.setPassword(config.getDatabasePassword());
        hikariConfig.setMaximumPoolSize(config.getDatabasePoolSize());
        hikariConfig.setConnectionTimeout(10000);
        this.dataSource = new HikariDataSource(hikariConfig);
        this.cacheTtlMillis = config.getSkinCacheTtlSeconds() * 1000L;
    }

    /**
     * 指定プレイヤーが「使用中」に設定しているスキンのPNGとモデル種別を取得する。
     * fje_active_skins → marketplace_nfts → marketplace_listings をJOINし、都度所有権を
     * 再検証する（購入解除・出品削除があっても古い情報をBedrock観測者へ表示し続けないため）。
     * 短時間の結果はメモリキャッシュし、Bedrockセッションが多い場合のDBラウンドトリップを減らす。
     */
    public Optional<SkinPayload> getActiveSkin(UUID uuid) {
        CacheEntry cached = cache.get(uuid);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMillis() < cacheTtlMillis) {
            return cached.payload();
        }

        Optional<SkinPayload> result = fetchFromDatabase(uuid);
        cache.put(uuid, new CacheEntry(result, System.currentTimeMillis()));
        return result;
    }

    private Optional<SkinPayload> fetchFromDatabase(UUID uuid) {
        String sql = "SELECT l.skin_png_data, l.skin_model " +
                "FROM fje_active_skins a " +
                "JOIN marketplace_nfts n ON n.id = a.nft_id AND n.owner_uuid = a.minecraft_uuid " +
                "JOIN marketplace_listings l ON l.id = n.listing_id AND l.item_type = 'skin' " +
                "WHERE a.minecraft_uuid = ? AND l.skin_png_data IS NOT NULL";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new SkinPayload(rs.getBytes("skin_png_data"), rs.getString("skin_model")));
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
