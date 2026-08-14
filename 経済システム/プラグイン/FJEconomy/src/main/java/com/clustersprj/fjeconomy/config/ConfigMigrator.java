package com.clustersprj.fjeconomy.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * config.yml のスキーマ変更を、既存サーバーの設定を壊さずに追従させるマイグレータです。
 * <p>
 * 従来は {@code saveResource("config.yml", false)} で「ファイルが無ければ作る」だけだったため、
 * プラグイン更新で設定項目が増えても既存サーバーの config.yml には反映されなかった。
 * 各ゲッターがコード側のデフォルト値を持っているので動作はするものの、
 * 管理者からは新しい設定項目の存在自体が見えず、値を変更する手段が無い状態だった。
 * </p>
 * <p>
 * 処理の流れ:
 * </p>
 * <ol>
 *   <li>config.yml の {@code config-version} を読む（無い場合はマイグレーション導入前とみなす）</li>
 *   <li>そのバージョンから {@link #CURRENT_VERSION} までの {@link Step} を順に適用する
 *       （キーの改名・削除・値の変換など、スキーマ変更に伴う書き換え）</li>
 *   <li>JAR に同梱された最新の config.yml と突き合わせ、不足しているキーを説明コメントごと補う</li>
 *   <li>内容が変わった場合のみ、元のファイルをバックアップしてから書き出す</li>
 * </ol>
 * <p>
 * 書き換えは {@link YamlLines} による行単位の編集で行うため、
 * 既存のコメント・並び順・管理者の手書き追記はそのまま維持されます。
 * </p>
 *
 * <h2>設定項目を変更するときの手順</h2>
 * <ol>
 *   <li>{@code src/main/resources/config.yml} を編集する（新しいキーの追加・削除など）</li>
 *   <li>キーの追加だけなら何もしなくてよい。既存ファイルへは自動で補完される</li>
 *   <li>キーの改名・削除・値の意味の変更を伴う場合は {@link #CURRENT_VERSION} を +1 し、
 *       {@link #steps()} に対応する {@link Step} を追加する</li>
 *   <li>同梱 config.yml の {@code config-version} も新しい値に更新する</li>
 * </ol>
 */
public class ConfigMigrator {

    /**
     * 現在のスキーマバージョン。
     * キーの改名・削除・意味の変更を行ったら +1 し、{@link #steps()} に手順を追加すること。
     */
    public static final int CURRENT_VERSION = 1;

    /** バージョンを記録するキー名。 */
    private static final String VERSION_KEY = "config-version";

    /** config-version が書かれていない、マイグレーション導入前の設定ファイルを表すバージョン。 */
    private static final int LEGACY_VERSION = 0;

    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final JavaPlugin plugin;
    private final Path configPath;

    public ConfigMigrator(JavaPlugin plugin, Path configPath) {
        this.plugin = plugin;
        this.configPath = configPath;
    }

    /**
     * 1つのスキーマ変更手順です。
     *
     * @param toVersion この手順を適用した結果になるバージョン
     * @param description ログに出す変更内容の説明
     * @param action 設定ファイルへの変更処理
     */
    private record Step(int toVersion, String description, Consumer<YamlLines> action) {
    }

    /**
     * スキーマ変更手順の一覧を、適用順（バージョンの昇順）で返します。
     * <p>
     * 将来キーを改名・削除する場合は、ここに追記していく。記述例:
     * </p>
     * <pre>{@code
     * new Step(2, "economy.tax_rate を economy.shop_tax_rate に改名", doc -> {
     *     doc.rename("economy.tax_rate", "shop_tax_rate");
     * }),
     * new Step(3, "廃止した web_api セクションを削除", doc -> {
     *     doc.remove("web_api");
     * }),
     * }</pre>
     *
     * @return 適用すべき手順のリスト
     */
    private List<Step> steps() {
        List<Step> steps = new ArrayList<>();

        // v1: マイグレーション機構の導入。
        // 既存の設定項目に変更は無く、config-version の付与と
        // （build_reward / account_link など後から増えたセクションの）補完のみを行う。
        steps.add(new Step(1, "config-version を導入し、不足している設定項目を補完", doc -> {
        }));

        return steps;
    }

    /**
     * 必要に応じて config.yml をマイグレーションします。
     *
     * @return ファイルを書き換えた場合は true
     * @throws IOException 読み書きに失敗した場合
     */
    public boolean migrate() throws IOException {
        if (!Files.exists(configPath)) {
            return false;
        }

        List<String> currentLines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
        YamlLines doc = new YamlLines(currentLines);
        String originalText = doc.toText();

        int fileVersion = readVersion(doc);

        if (fileVersion > CURRENT_VERSION) {
            plugin.getLogger().warning("config.yml のバージョン(" + fileVersion
                    + ")がプラグインの対応バージョン(" + CURRENT_VERSION + ")より新しいため、"
                    + "マイグレーションを行いません。プラグインを新しいものへ更新してください。");
            return false;
        }

        // 1. スキーマ変更手順の適用
        if (fileVersion < CURRENT_VERSION) {
            for (Step step : steps()) {
                if (step.toVersion() > fileVersion && step.toVersion() <= CURRENT_VERSION) {
                    step.action().accept(doc);
                    plugin.getLogger().info("config.yml を v" + step.toVersion()
                            + " へ更新: " + step.description());
                }
            }
        }

        // 2. 同梱の最新 config.yml から、不足しているキーを説明コメントごと補う
        YamlLines defaults = loadBundledDefaults();
        List<String> added = new ArrayList<>();
        if (defaults != null) {
            mergeMissingKeys(doc, defaults, "", added);
        }

        // 3. バージョンを記録
        writeVersion(doc, CURRENT_VERSION);

        String migratedText = doc.toText();
        if (migratedText.equals(originalText)) {
            return false;
        }

        // 4. 変更があったときだけ、元のファイルを退避してから書き出す
        Path backup = backupPath(fileVersion);
        Files.copy(configPath, backup);
        Files.writeString(configPath, migratedText, StandardCharsets.UTF_8);

        if (!added.isEmpty()) {
            plugin.getLogger().info("config.yml に不足していた設定項目を追加しました: "
                    + String.join(", ", added));
        }
        plugin.getLogger().info("config.yml を v" + fileVersion + " から v" + CURRENT_VERSION
                + " へ更新しました（変更前: " + backup.getFileName() + "）");
        return true;
    }

    /**
     * 同梱された config.yml と突き合わせ、利用者のファイルに無いキーを再帰的に補います。
     * <p>
     * 値が既にあるキーには一切触れません。セクションが丸ごと無い場合は、
     * そのセクションを説明コメントごと差し込み、中身の再帰はしません。
     * </p>
     *
     * @param target 利用者の設定ファイル
     * @param defaults 同梱のデフォルト設定
     * @param path 走査中のセクション（ルートは空文字）
     * @param added 追加したキーのパスを記録するリスト
     */
    static void mergeMissingKeys(YamlLines target, YamlLines defaults, String path, List<String> added) {
        for (String key : defaults.childKeys(path)) {
            String childPath = path.isEmpty() ? key : path + "." + key;

            // config-version は writeVersion() が管理するのでここでは扱わない
            if (childPath.equals(VERSION_KEY)) {
                continue;
            }

            if (target.has(childPath)) {
                // 既にある場合は、その配下に増えたキーが無いかだけを見る
                if (defaults.isSection(childPath) && target.isSection(childPath)) {
                    mergeMissingKeys(target, defaults, childPath, added);
                }
                continue;
            }

            List<String> block = defaults.extractBlock(childPath);
            if (block.isEmpty()) {
                continue;
            }

            // 差し込み先の階層に合わせて字下げを調整する
            int sourceIndent = defaults.indentAt(childPath);
            int targetIndent = target.childIndent(path);
            List<String> adjusted = YamlLines.reindent(block, targetIndent - sourceIndent);

            if (target.insertInto(path, adjusted)) {
                added.add(childPath);
            }
        }
    }

    /**
     * JAR に同梱されている最新の config.yml を読み込みます。
     *
     * @return 読み込んだ内容。取得できない場合は null
     */
    private YamlLines loadBundledDefaults() {
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                plugin.getLogger().warning("JAR内に config.yml が見つからないため、設定項目の補完を省略します");
                return null;
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            return new YamlLines(lines);
        } catch (IOException e) {
            plugin.getLogger().warning("同梱 config.yml の読み込みに失敗したため、設定項目の補完を省略します: "
                    + e.getMessage());
            return null;
        }
    }

    /** config.yml に書かれているスキーマバージョンを読みます。 */
    private int readVersion(YamlLines doc) {
        int index = doc.indexOf(VERSION_KEY);
        if (index < 0) {
            return LEGACY_VERSION;
        }

        // "config-version: 3  # コメント" のような行から数値だけを取り出す
        List<String> block = doc.extractBlock(VERSION_KEY);
        for (String line : block) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String value = line.substring(colon + 1);
            int comment = value.indexOf('#');
            if (comment >= 0) {
                value = value.substring(0, comment);
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                // 数値として読めない場合は導入前として扱う
            }
        }
        return LEGACY_VERSION;
    }

    /** スキーマバージョンを書き込みます（無ければファイル先頭に作ります）。 */
    private void writeVersion(YamlLines doc, int version) {
        if (doc.has(VERSION_KEY)) {
            doc.setScalar(VERSION_KEY, String.valueOf(version));
            return;
        }

        doc.insertAtTop(List.of(
                "# 設定ファイルのスキーマバージョン（プラグインが自動で更新します。手動で変更しないでください）",
                VERSION_KEY + ": " + version
        ));
    }

    /** 重複しないバックアップ先のパスを組み立てます。 */
    private Path backupPath(int fromVersion) {
        String stamp = LocalDateTime.now().format(BACKUP_STAMP);
        Path candidate = configPath.resolveSibling(
                "config.yml.v" + fromVersion + "." + stamp + ".bak");

        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = configPath.resolveSibling(
                    "config.yml.v" + fromVersion + "." + stamp + "-" + suffix + ".bak");
            suffix++;
        }
        return candidate;
    }
}
