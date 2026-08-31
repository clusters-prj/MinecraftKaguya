# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## リポジトリ概要

映画「超かぐや姫！」の世界を Minecraft で再現するサーバー運営プロジェクト。Paper プラグイン（Java / Maven マルチモジュール）、Node.js の Web ダッシュボード、サーバー用プラグイン設定（MythicMobs / ModelEngine 等）が 1 リポジトリに同居している。ディレクトリ名・ドキュメント・コード内コメントは日本語が基本なので、コメントや UI 文言も日本語で書く。

パスに日本語が含まれるため、Bash ツールから `cd 経済システム/...` は文字化けして失敗する。Read/Glob/Grep のようなツールを絶対パスで使うか、Bash ではリポジトリルートからの相対パスをコマンド引数として渡す（`cd` せずに `grep ... 経済システム/...`）。

## ビルド・チェック

ルート `pom.xml` が `paper-plugins-parent` として 4 モジュール（BGMPlayer / FJEconomy / FJESkinBridge / WebRegionAutomator）を束ねる。

```bash
mvn -B clean package
```

```bash
mvn clean verify
```

- `verify` フェーズで SpotBugs（effort=Max, threshold=Low）と Checkstyle（google_checks）が走る。どちらも `failOnError=false` なので**ビルドは通っても指摘は出る**。CI（`java-ci.yml`）はこのログを High/Medium/Low に分類して Discord へ送るため、`mvn clean verify` の出力は常に確認すること。
- 単一モジュールだけビルドする場合はルートから `mvn -pl 経済システム/プラグイン/FJEconomy -am clean package`。
- **テストコードは存在しない**（`src/test` なし）。動作確認は実サーバーでのコマンド実行と DB の直接確認で行う運用。
- Java バージョンが揃っていない: 親 pom とルート CI は 25、BGMPlayer は 21、FJEconomy は compiler-plugin で 17 を明示。モジュールの pom を勝手に揃えないこと（意図的な設定）。

Web 側（`経済システム/Web/fjew`）:

```bash
npm install
```

```bash
node server.js
```

`PORT` は 3200 固定。`SESSION_SECRET` / `APP_BASE_URL` / `SMTP_HOST` は未設定だと起動時に落ちる。DB 接続情報などは `.env`（gitignore 済み）から `dotenv` で読む。

外部連携用 REST API（`経済システム/Web/fjeapi`、法人口座がオーナー自身で発行できる API キー向け）は別プロセス・別ポート（3000 固定）で動く。起動は同様に `npm install` → `node server.js`。`fje_api_keys` テーブル（`minecraft_uuid` / `key_hash` / `key_hint` を保持）を自前で `CREATE TABLE IF NOT EXISTS` して初期化する。エラーレスポンスは `sendServerError()` で汎用メッセージに丸め、DB のテーブル名・SQL エラーをクライアントに漏らさない設計。金額は `BigInt` でパースし（`parseAmount()`）、`double`/`isNaN` によるオーバーフローや不正文字列を避ける。`.env` は fjew とは別に用意し、`API_MANAGEMENT_SECRET` を追加で要求する。

## CI / デプロイの仕組み

- `build.yml`: main への push で全 pom のバージョンに `-b<run_number>` を付与してビルドし、JAR を Reposilite（`reposilite.clusters-prj.com`）へアップロード。`-SNAPSHOT` の有無で `snapshots` / `releases` を自動振り分けし、`<artifactId>-<baseVersion>-latest.jar` も同時に上書きする。
  - **実サーバーへの自動配布・自動再起動の仕組みは存在しない。** `-latest.jar` は用意されるがそれを自動で取得しにいくcron等は無く、各サーバーへの反映は**手動**（後述の「本番サーバー構成・デプロイ手順」）。push しただけではゲーム内には一切反映されない。
  - ダウンロードURLの実際の形式（groupIdを含まないフラット構成）: `https://reposilite.clusters-prj.com/<releases|snapshots>/<artifactId>/<baseVersion>/<artifactId>-<baseVersion>-b<run_number>.jar`（例: `https://reposilite.clusters-prj.com/releases/fj-economy/2.0.1-SNAPSHOT/fj-economy-2.0.1-SNAPSHOT-b182.jar`）。GitHub Actionsの実行番号は `gh run list --repo clusters-prj/MinecraftKaguya --workflow=build.yml` で確認できる。
- `release-pipeline.yml`: `プラグイン関連/BGMPlayer/**` の変更で `java-resourcepack/` を zip 化し `BGM-latest` タグの Release に上げ、SHA-1 を計算して**リポジトリ内の全 `config.yml` の `resource-pack-sha1:` を sed で書き換えて main に push する**。BGM 以外のプラグインの config.yml にも同名キーがあると巻き込まれる点に注意。
- `java-ci.yml`: 静的解析結果の Discord 通知。

## 本番サーバー構成・デプロイ手順

実サーバーはProxmoxクラスタ上のVM/コンテナで、SSH接続は `clusters-prj-ssh` スキルを参照（VMID→IP変換ルールはそちらに集約）。このプロジェクトに関係するVMIDは以下（役割はスキル側のVMID一覧より、随時変わりうるので疑わしい場合は本人に確認する）:

| VMID | 名前 | 役割 |
|---|---|---|
| 105 | Web-1 | `経済システム/Web/fjew` を pm2 で稼働（プロセス名 `fjew`） |
| 127 | MS-KV | 前段のVelocity（Geyser/Floodgateが動く。Bedrock接続の入口） |
| 128 | MS-K1 | Paperサーバー本体（`server.id`のmc1相当） |
| 129 | MS-K2 | Paperサーバー本体（mc2相当） |
| 130 | MS-K3 | Paperサーバー本体（mc3相当。運用上の理由で更新対象から外れることがある） |

- **fjew (105) のデプロイ**: `cd ~/MinecraftKaguya/経済システム/Web/fjew/ && git pull origin && cp -r * /opt/fjew/ && pm2 restart 1`（pm2のプロセスID`1`が`fjew`）。
- **Paperプラグイン (128/129/130) のデプロイ**: 上記ReposiliteのURLから該当jarを `~/paper/plugins/` に配置し、`systemctl restart paper.service` で再起動（ログは `journalctl -u paper.service`）。
  - **同じプラグイン名の新旧jarを`plugins/`に併存させると、Paperの`ModernPluginLoadingStrategy`が「Ambiguous plugin name」エラーを出し、意図しない方が読み込まれる（最悪ロードされない）。新jarを置く前に必ず旧バージョンのjarを削除すること。**
  - このリポジトリでは新バージョンをpushしてもjarが自動配布されないため、更新のたびにこの手順を手動で行う必要がある（忘れると新機能が反映されないまま古い挙動が続く）。
- **FJESkinBridgeはPaperプラグインではなくGeyser Extension**なので、128/129/130の`~/paper/plugins/`に置いても`paper-plugin.yml/plugin.ymlが無い`エラーで読み込まれない。Geyser/Floodgateが動いている127(MS-KV)側のGeyserの`extensions/`フォルダに配置する必要がある（2026-08-30時点でこの配置作業は未実施）。

## アーキテクチャの要点

### 中央 DB が唯一の真実

MariaDB（`10.2.1.27` / DB 名 `fjeconomy`）を複数の Minecraft サーバー（`server.id` = mc1/mc2/mc3）と Web ダッシュボードが共有する。プラグイン間・Web 間の連携はすべてこの DB 経由で、REST や RPC の直接呼び出しは存在しない。したがって**テーブルのスキーマや意味を変える変更は Java 側と `server.js` の両方に波及する**。

- Java 側のテーブル定義は `FJEconomy/.../database/DatabaseManager.java` の `createTables()` に集約（`fje_balances`, `fje_shops`, `fje_transactions`, `fje_government_ledger`, `fje_login_bonuses`, `link_codes`, `fje_arena_events`, `fje_arena_participants`, `fje_arena_bets`, `fje_active_skins`）。
- Web 側だけが作る/使うテーブル: `web_users`, `account_links`, `email_verifications`, `password_resets`, `marketplace_listings`, `marketplace_nfts`, `marketplace_transfers`。
- `fje_active_skins`（マーケットプレイスで「使用中」のスキンNFT）は `link_codes` と同じく **Web側(`server.js`)とJava側(`DatabaseManager`)の両方が同一定義で`CREATE TABLE IF NOT EXISTS`する共有テーブル**。`marketplace_nfts`（Web所有）へのFKはこのリポジトリの既存方針どおり張らない（起動順序に依存させないため）。
- 仕様書は `経済システム/仕様/`（`DB.md`, 日付つき仕様書ディレクトリ）と `FJEconomy/README.md` / `DEVELOPMENT.md` / `SHOP_SYSTEM.md`。DB 変更時はここも更新対象。

### 金額は必ず整数

残高・価格・税額はすべて `INT` / `long` で扱う。`double` は使わない。税計算は `BigDecimal` + `RoundingMode`（config の `economy.rounding_method`）で整数に丸め、「販売価格 = 税額 + 店主受取額」が常に一致することを保つ。複数残高を動かす処理は `Connection#setAutoCommit(false)` + commit/rollback のトランザクションで囲む。

### FJEconomy のマネージャ構成

`FJEconomy#onEnable()` が全マネージャを順に生成し、他クラスは `plugin.getXxxManager()` から取得する（DI なし・生成順が依存順）。DB → Economy → Command → Shop → Government → LoginBonus → Skin → Arena の順で、後段は前段の getter に依存しているため**初期化順を入れ替えないこと**。`reloadPlugin()` は config を読み直し、`database.url`/`username` が変わった時だけ DB を張り直す。

- `arena/ArenaManager`: Web 管理画面で登録されたアリーナイベントを 5 秒ごと（100 ticks）にポーリングしてキャッシュし、`PlayerMoveEvent` / `EntityDamageByEntityEvent` を高頻度パスとして扱う（毎回 DB を叩かない）。ゾーン判定は Y を無視し、未登録プレイヤーは押し出す（`fj.arena.bypass` で回避可）。参加者の GameMode とインベントリは `ParticipantState` に退避し、イベント終了時に復元する。
- `link/LinkManager`: `/fj link` で 6 桁のワンタイムコードを `link_codes` に発行し、Web 側 (`POST /api/auth/link`) が消費して `account_links` に UUID を紐づける。この 2 テーブルが Minecraft と Web の唯一の接点。
- `skin/SkinManager` + `skin/SkinListener` + `command/SkinCommand`: マーケットプレイスで購入したスキンをJava側へ適用する。所有権は**購入したUUID本人だけでなく、`account_links`経由で同じWebアカウントにリンクされた他のMinecraftアカウント（Java用・Bedrock用）でも使えるように**都度JOINで再検証する（`marketplace_nfts.owner_uuid`と一致する必要はない）。`AsyncPlayerPreLoginEvent`でログイン確定前に署名済みテクスチャを焼き込み、上書き前の本来のtexturesプロパティは`SkinManager`内のメモリキャッシュに退避して`/skin reset`で復元する。Bedrock観測者向けの表示は別モジュール`FJESkinBridge`が担当する（Java側とBedrock観測者側で経路が完全に分かれている点に注意）。
- `config/ConfigMigrator`: `loadConfig()` の前段で走る config.yml のマイグレーション。`config-version` を見て `steps()` を順に適用し、JAR 同梱の config.yml から不足キーを説明コメントごと補い、変更があれば `.bak` を残して書き出す。**設定項目を追加するだけなら `src/main/resources/config.yml` を編集すれば既存サーバーへ自動反映される**。キーの改名・削除をしたときだけ `CURRENT_VERSION` を +1 して `steps()` に手順を足すこと。書き換えはコメントを保つため `config/YamlLines` の行単位編集で行う（snakeyaml の `dump()` は使わない）。
- `shop/ShopManager` + `shop/ShopUI`: インベントリ GUI ベースのショップ。`ShopUI` は独立した Listener として登録される。

### 依存の shade と relocation

FJEconomy / WebRegionAutomator は HikariCP・JDBC ドライバ・SLF4J を `com.clustersprj.fjeconomy.libs.*` へ relocate して shade する。サーバー上の他プラグインとのクラス衝突を防ぐための必須設定なので、relocation を外したり依存を `provided` に変えたりしないこと。

### その他のモジュール

- `経済システム/プラグイン/FJESkinBridge`: **PaperプラグインではなくGeyser Extension**（`extension.yml`で読み込まれる。plugin.yml方式ではない）。マーケットプレイスで購入したスキンをBedrock観測者向けに`SessionSkinApplyEvent`で強制上書きする。FJEconomyとは別プロセス・別DBコネクションプールで、共有MariaDBを直接読みに行く点は同じ。Java 21以上必須（Geyser APIの要求。ルートpomのJava 25設定を上書きしている）。GeyserMCのMavenリポジトリ（`repo.opencollab.dev`）に依存。**Paperサーバーの`plugins/`フォルダに置いても動かない**（Geyser/Floodgateが実際に動いているホストのGeyser `extensions/`フォルダに置く必要がある）。
- `プラグイン関連/BGMPlayer`: リソースパック配布（URL + SHA-1）と BGM のループ再生。`config.yml` の `sounds[].key` はリソースパック側 `sounds.json` のキーと、`duration-seconds` は実際の曲長と一致している必要がある。Bedrock 版アセットは `BGMPlayerBE/`。
- `プラグイン関連/WebRegionAutomator`: WorldGuard/WorldEdit 依存。WorldEdit の斧選択を Web 申請として DB に登録し、`RegionTask` が非同期ポーリングして保護領域を自動作成する。`depend` に WorldGuard/WorldEdit があるためこれらが無いサーバーでは起動しない。
- `プラグイン関連/MythicMobs`, `ModelEngine`: プラグイン本体ではなくサーバーに配置する設定・アセット。ビルド対象外。

## Issue 運用（README より）

題名は内容を簡潔に要約し、タグで分類する: 改善 / 問題 / 移行対応 / 機能提案 / ユーザーからの報告。
