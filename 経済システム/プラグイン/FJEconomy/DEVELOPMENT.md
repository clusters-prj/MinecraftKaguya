# FJ Economy - 開発ガイド

## プロジェクト構造

```
FJEconomy/
├── pom.xml                                 # Maven設定ファイル
├── README.md                               # 使用方法ガイド
├── src/
│   └── main/
│       ├── java/
│       │   └── com/clustersprj/fjeconomy/
│       │       ├── FJEconomy.java           # メインプラグインクラス
│       │       ├── command/
│       │       │   └── CommandManager.java  # コマンド処理
│       │       ├── config/
│       │       │   └── ConfigManager.java   # 設定ファイル管理
│       │       ├── database/
│       │       │   └── DatabaseManager.java # データベース接続管理
│       │       ├── economy/
│       │       │   └── EconomyManager.java  # 経済システムロジック
│       │       └── listener/
│       │           └── PlayerListener.java  # イベントリスナー
│       └── resources/
│           ├── plugin.yml                  # Paperプラグインメタデータ
│           └── config.yml                  # デフォルト設定ファイル
└── target/                                  # ビルド出力（mvnコマンド後）
    └── fj-economy-1.0.0.jar               # 本番用JAR
```

## ビルド方法

### 前提条件

```bash
# Java のバージョン確認
java -version
# Java 17 以上が必要

# Maven のバージョン確認
mvn -version
# Apache Maven 3.9 以上が必要
```

### ビルド手順

```bash
# プロジェクトディレクトリに移動
cd FJEconomy

# 依存関係をダウンロードしてビルド
mvn clean package

# テストをスキップしてビルド（高速化）
mvn clean package -DskipTests

# 特定フェーズのみ実行
mvn compile          # コンパイルのみ
mvn test             # テスト実行
mvn verify           # 検証
mvn install          # ローカルリポジトリにインストール
```

### ビルド完了後

```bash
# JAR ファイルを確認
ls -lh target/fj-economy-1.0.0.jar

# Paper サーバーにコピー
cp target/fj-economy-1.0.0.jar /path/to/server/plugins/
```

## クラス設計

### FJEconomy.java (メインプラグインクラス)

プラグインのライフサイクル管理。
- `onEnable()`: プラグイン起動時の初期化
- `onDisable()`: プラグイン終了時のクリーンアップ
- `reloadPlugin()`: 設定とDB接続のリロード

**責務**:
- 各マネージャーの初期化と管理
- イベントリスナーの登録
- 全体的なエラーハンドリング

### ConfigManager.java (設定管理)

`config.yml` の読み込みと型安全なアクセスを提供。

**主なメソッド**:
- `loadConfig()`: YAMLファイルから設定を読み込み
- `getString(path, default)`: 文字列値を取得
- `getInt(path, default)`: 整数値を取得
- `getSection(path)`: ネストされた設定セクションを取得
- `isDatabaseConfigChanged()`: DB設定が変更されたか確認

**ドット記法サポート**:
```java
configManager.getString("database.url", "localhost");
configManager.getInt("economy.starting_balance", 1000);
```

### DatabaseManager.java (データベース管理)

MariaDB への接続とコネクションプーリング。

**主な機能**:
- HikariCP によるコネクションプーリング
- テーブルの自動作成
- 非同期クエリ実行
- トランザクション管理

**使用例**:
```java
// 同期的にクエリ実行
try (Connection conn = dbManager.getConnection()) {
    // SQL実行
}

// 非同期でクエリ実行
dbManager.executeAsync("SELECT * FROM fje_balances", rs -> {
    // 結果処理
});
```

### EconomyManager.java (経済ロジック)

残高管理と取引処理。

**主な機能**:
- プレイヤー残高の取得・設定
- 送金処理
- 商品購入と自動課税
- 政府口座管理

**重要な特性**:
- すべての金額は **整数(INT)** で管理
- 税金計算時の端数は `RoundingMode` で処理
- トランザクション処理で整合性を保証

**使用例**:
```java
// 残高確認
long balance = economyManager.getBalance(playerUUID);

// 送金
boolean success = economyManager.sendMoney(
    senderUUID, senderName,
    receiverUUID, receiverName,
    amount
);

// 購入処理（自動課税）
boolean purchased = economyManager.processPurchase(
    buyerUUID, buyerName,
    ownerUUID, ownerName,
    "DIAMOND", 10, 100  // 10個、1個100円
);
```

### CommandManager.java (コマンド処理)

ユーザーコマンドとアドミンコマンドの処理。

**実装されたコマンド**:
- `/fj balance` - 残高確認
- `/fj pay <player> <amount>` - 送金
- `/fjeadmin give/take/set` - 管理者コマンド
- `/fjeadmin reload` - 設定リロード

**タブ補完機能**: プレイヤー名の自動補完に対応。

### PlayerListener.java (イベントリスナー)

ゲームイベントの処理。

**現在実装中のイベント**:
- `PlayerJoinEvent`: プレイヤー参加時のアカウント作成確認

## データベース設計

### 整数管理ポリシー

すべての通貨値は **INT** で管理し、浮動小数点誤差を排除します。

```java
// ✅ 正しい方法
long amount = 10000;  // 1万円

// ❌ 誤った方法
double amount = 10000.5;  // 使用禁止
```

### 税金計算ロジック

```
販売価格 = 100円、税率 = 10%

税金計算:
  BigDecimal taxDecimal = 100 * 0.10 = 10.0
  → RoundingMode.HALF_UP で四捨五入
  → 整数化: taxAmount = 10

店主受取額 = 100 - 10 = 90円

整合性確認:
  販売価格 100 = 政府受取額 10 + 店主受取額 90 ✅
```

### トランザクション管理

複数の残高変更を行う場合、トランザクションで一括処理します。

```java
try (Connection conn = dbManager.getConnection()) {
    conn.setAutoCommit(false);
    try {
        // 複数の更新
        deductBalance(buyer);
        addBalance(owner);
        addBalance(government);
        
        conn.commit();  // すべて成功したらコミット
    } catch (Exception e) {
        conn.rollback();  // 1つ失敗したらすべてロールバック
    }
}
```

## 拡張機能の開発例

### ショップシステムの実装

```java
// フューチャー: NPC ショップの販売機能

public class ShopManager {
    public boolean sellItem(NPCInteractEvent event) {
        UUID buyerUUID = event.getPlayer().getUniqueId();
        UUID ownerUUID = getNPCOwner(event.getNPC());
        
        return economyManager.processPurchase(
            buyerUUID, event.getPlayer().getName(),
            ownerUUID, ownerName,
            "DIAMOND", quantity, unitPrice
        );
    }
}
```

### Web API の実装

```java
// フューチャー: REST API でダッシュボード連携

public class ApiEndpoint {
    @GetMapping("/balance/{uuid}")
    public long getBalance(@PathVariable String uuid) {
        return economyManager.getBalance(UUID.fromString(uuid));
    }
    
    @GetMapping("/transactions")
    public List<Transaction> getTransactions() {
        // fje_transactions をクエリ
    }
}
```

## パフォーマンス最適化

### コネクションプール設定

```yaml
database:
  pool_size: 10           # 同時接続数
  max_lifetime: 1800000   # 30分でリセット
```

多数のプレイヤーがいる場合は `pool_size` を増やします。

### 非同期処理の活用

```java
// ❌ ブロッキング（避けるべき）
long balance = economyManager.getBalance(playerUUID);  // メインスレッド待機

// ✅ 非同期（推奨）
Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
    long balance = economyManager.getBalance(playerUUID);
    Bukkit.getScheduler().runTask(plugin, () -> {
        player.sendMessage("残高: " + balance);
    });
});
```

## テスト方法

### 基本的なコマンドテスト

```
# オペレーター で実行
/fjeadmin give <player> 10000
/fj pay <other_player> 5000
/fj balance
/fjeadmin reload
```

### データベースの確認

```sql
-- MySQL で直接確認
SELECT * FROM fjeconomy.fje_balances;
SELECT * FROM fjeconomy.fje_transactions ORDER BY timestamp DESC LIMIT 10;
SELECT * FROM fjeconomy.fje_government_ledger;
```

## トラブルシューティング

### コンパイルエラー

```
[ERROR] cannot find symbol
```

**原因**: 依存関係が不足している可能性。

**解決**:
```bash
mvn clean install -U  # 依存関係を強制再ダウンロード
```

### テスト実行時のエラー

```
[ERROR] Database connection failed
```

**原因**: テスト実行中に DB に接続しようとしている可能性。

**解決**: テストをスキップしてビルド
```bash
mvn package -DskipTests
```

## ライセンスと貢献

© 2024 Clusters-Prj. All rights reserved.

プラグインの改良案や不具合報告は welcomes です。
