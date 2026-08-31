package com.clustersprj.fjeskinbridge;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * config.yml から共有MariaDBの接続情報を読み込むだけの最小限の設定ローダー。
 * <p>
 * FJEconomy(Paper側)の {@code ConfigMigrator}/{@code YamlLines} のようなコメント保持
 * マイグレーション機構は、この拡張が持つ設定項目が数個しかないため導入せず、
 * 単純なSnakeYAMLでの読み込みに留めている。
 * </p>
 */
public class ExtensionConfig {

    private final String databaseUrl;
    private final String databaseUsername;
    private final String databasePassword;
    private final int databasePoolSize;
    private final int skinCacheTtlSeconds;

    @SuppressWarnings("unchecked")
    public ExtensionConfig(Path dataFolder) throws IOException {
        Files.createDirectories(dataFolder);
        Path configPath = dataFolder.resolve("config.yml");
        if (!Files.exists(configPath)) {
            try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                if (in == null) throw new IOException("同梱の config.yml が見つかりません");
                Files.copy(in, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(configPath)) {
            root = new Yaml().load(in);
        }
        Map<String, Object> database = (Map<String, Object>) root.get("database");

        this.databaseUrl = (String) database.get("url");
        this.databaseUsername = (String) database.get("username");
        this.databasePassword = (String) database.get("password");
        this.databasePoolSize = (int) database.getOrDefault("pool_size", 4);
        this.skinCacheTtlSeconds = (int) root.getOrDefault("skin_cache_ttl_seconds", 30);
    }

    public String getDatabaseUrl() {
        return databaseUrl;
    }

    public String getDatabaseUsername() {
        return databaseUsername;
    }

    public String getDatabasePassword() {
        return databasePassword;
    }

    public int getDatabasePoolSize() {
        return databasePoolSize;
    }

    public int getSkinCacheTtlSeconds() {
        return skinCacheTtlSeconds;
    }
}
