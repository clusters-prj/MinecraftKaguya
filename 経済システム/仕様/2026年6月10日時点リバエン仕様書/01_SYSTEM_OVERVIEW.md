# FJ Economy - システム概要書

**バージョン:** 1.0.7  
**最終更新:** 2024-12-13  
**対象者:** 開発者・システム管理者

---

## 1. プロジェクト概要

FJ Economyは、Proxmox上で運用される複数のPaper Minecraftサーバー間で通貨・経済データをリアルタイム同期する、完全独自設計の経済システムです。既存のVault等に依存せず、MariaDBを中央管理層として、Web連携が可能な構成になっています。

### 主な特徴

- **リアルタイム同期**: 複数サーバー間での通貨データ即座反映
- **整数管理**: 浮動小数点誤差を完全排除
- **ショップシステム**: NPC店員による自動売買・自動課税
- **Web連携**: clusters-prj.comダッシュボードとの統計連携
- **非同期処理**: ゲームメインスレッドをブロッキングしない設計
- **トランザクション管理**: 複数口座操作の整合性保証

---

## 2. ネットワーク構成

### インフラマップ

```
┌─────────────────────────────────────────────────┐
│              Proxmox Environment                 │
├─────────────────────────────────────────────────┤
│                                                  │
│  ┌──────────────────┐    ┌─────────────────┐  │
│  │  MC Server (MC1) │    │ MariaDB (10.2.  │  │
│  │   10.2.1.28      │    │    1.27)        │  │
│  │   VMID: 101      │    │   fjeconomy DB  │  │
│  │   Paper 1.21+    │    │                 │  │
│  └──────────────────┘    └─────────────────┘  │
│                                                  │
│  ┌──────────────────┐    ┌─────────────────┐  │
│  │  MC Server (MC2) │    │ Web Dashboard   │  │
│  │   10.2.1.28      │    │  10.2.1.5       │  │
│  │   VMID: 301      │    │ clusters-prj.   │  │
│  │   Paper 1.21+    │    │ com (Nginx)     │  │
│  └──────────────────┘    └─────────────────┘  │
│                                                  │
│  ┌──────────────────┐                          │
│  │  MC Server (MC3) │                          │
│  │   External PC    │                          │
│  │   10.2.0.10      │                          │
│  │   Paper 1.21+    │                          │
│  └──────────────────┘                          │
│                                                  │
└─────────────────────────────────────────────────┘
```

### 各コンポーネント仕様

| コンポーネント | IP / ホスト | 役割 | 技術スタック |
|---|---|---|---|
| **MC1 (メインサーバー)** | 10.2.1.28 | ゲームサーバー・経済処理 | Paper 1.21, Java 17 |
| **MC2 (拡張サーバー)** | 10.2.1.28 | ゲームサーバー・経済処理 | Paper 1.21, Java 17 |
| **MC3 (外部PC)** | 10.2.0.10 | ゲームサーバー・経済処理 | Paper 1.21, Java 17 |
| **DB Server** | 10.2.1.27 | 通貨・取引データ永続化 | MariaDB 8.0+, UTF8MB4 |
| **Web Dashboard** | 10.2.1.5 | 統計表示・管理UI | Nginx, PHP/Node.js |

---

## 3. 基本設計方針

### 3.1 通貨管理

**原則：整数（INT）管理**

- 全通貨値は **BIGINT** で管理（大口取引対応）
- 1円単位での厳密な管理
- 浮動小数点計算を一切使用しない

```java
// ✅ 正しい方法
long balance = 10000L;  // 1万円

// ❌ 誤った方法
double balance = 10000.5;  // 使用禁止
```

### 3.2 税金計算

全トランザクションで自動課税。税率はconfig.ymlで設定可能。

```
販売価格 = 100円、税率 = 10%

計算ステップ：
1. 税額（BigDecimal）= 100 × 0.10 = 10.0
2. RoundingMode.HALF_UP で整数化 → 10円
3. 店主受取額 = 100 - 10 = 90円

整合性チェック：
  販売価格(100) = 政府受取額(10) + 店主受取額(90) ✅
```

### 3.3 トランザクション管理

複数の口座操作が必要な場合、**ACID特性を保証**するトランザクション内で一括処理。

```
例：商品購入（購入者 → 店主＋政府）

BEGIN TRANSACTION
  ↓
  1. 購入者残高 -= 総額
  2. 店主残高 += 純利益
  3. 政府残高 += 税金
  4. 取引レコード記録
  5. 政府台帳記録
  ↓
COMMIT ✅ または ROLLBACK（エラー時）
```

### 3.4 非同期処理

全てのDB操作をBukkitスケジューラで非同期実行。メインスレッドのブロッキングを完全防止。

```java
// ❌ 同期的（ブロッキング）
long balance = economyManager.getBalance(uuid);  // メインスレッド待機

// ✅ 非同期（推奨）
Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
    long balance = economyManager.getBalance(uuid);
    Bukkit.getScheduler().runTask(plugin, () -> {
        player.sendMessage("残高: " + balance);
    });
});
```

---

## 4. アーキテクチャパターン

### 4.1 マネージャーパターン

各機能領域を専門マネージャーで責務分離：

| マネージャー | 責務 |
|---|---|
| **ConfigManager** | config.yml読込・型安全アクセス |
| **DatabaseManager** | HikariCP接続プール管理・テーブル初期化 |
| **EconomyManager** | 残高操作・送金・購入処理 |
| **ShopManager** | NPC店舗の作成・編集・削除・在庫管理 |
| **CommandManager** | ユーザーコマンド解析・実行 |

### 4.2 ドット記法サポート

ConfigManager は YAML階層構造をドット記法で安全にアクセス：

```java
// YAML構造
// database:
//   url: "jdbc:mariadb://..."
//   pool_size: 10

// ドット記法アクセス
configManager.getString("database.url", "default");
configManager.getInt("database.pool_size", 10);
```

---

## 5. セキュリティと信頼性

### 5.1 シングルソース・オブ・トゥルース

全サーバーの経済データは **10.2.1.27 の MariaDB を唯一の真実** とします。

- ローカルキャッシュは一切保持しない
- DB接続断時は書き込み禁止ロック機構を搭載（実装予定）
- 全トランザクションをログ記録（監査証跡）

### 5.2 権限体系

```
権限ツリー：
├─ fj.use (全プレイヤー)
│  ├─ /fj balance
│  ├─ /fj pay
│  └─ /fj help
│
├─ fj.shop.create (全プレイヤー)
│  └─ /shop create <...>
│
├─ fj.admin (OP)
│  ├─ /fjeadmin give
│  ├─ /fjeadmin take
│  ├─ /fjeadmin set
│  └─ /fjeadmin reload
│
└─ fj.shop.delete (OP)
   └─ /shop delete <...>
```

### 5.3 データベース接続セキュリティ

- MariaDB認証 (username/password)
- HikariCP接続プール（max 10同時接続）
- コネクション timeout: 10秒
- Idle timeout: 10分

---

## 6. パフォーマンス特性

### 6.1 スケーラビリティ

**予想規模：**
- プレイヤー数：100～500名
- 日次トランザクション：1000～5000件
- 同時接続数：最大10（HikariCP設定）

### 6.2 応答時間目標

| 操作 | 目標応答時間 |
|---|---|
| 残高確認 | < 100ms |
| 送金 | < 500ms |
| 購入（課税含む） | < 1000ms |
| ショップ作成 | < 500ms |

### 6.3 接続プール設定

```yaml
database:
  pool_size: 10              # 同時接続数
  max_lifetime: 1800000      # 30分でリセット
  connection_timeout: 10000  # 10秒でタイムアウト
```

---

## 7. 拡張ポイント

### 7.1 Web API（実装予定）

```
GET  /api/economy/balance/{uuid}     → 残高照会
GET  /api/economy/transactions       → 取引履歴
POST /api/economy/transactions       → (Webhook) 取引記録
GET  /api/economy/shops              → ショップ一覧
PUT  /api/economy/shops/{id}         → ショップ更新
```

### 7.2 イベント連動（実装予定）

- 定期給付イベント（給与配布）
- 季節イベント手当
- 経済危機介入（インフレーション対策）

### 7.3 統計ダッシュボード（実装予定）

- 長者番付（富豪ランキング）
- 業種別売上分析
- 税収推移グラフ
- 経済成長率メーター

---

## 8. 開発ロードマップ

### v1.0.7 (現在)
- ✅ コア経済システム（残高・送金）
- ✅ ショップシステム（NPC店舗）
- ✅ 自動課税機構
- ✅ トランザクションログ
- ✅ 政府口座管理

### v1.1.0 (予定)
- 🔄 Web API実装
- 🔄 ダッシュボード機能拡張
- 🔄 イベント連動給付

### v2.0.0 (長期計画)
- 📋 プレイヤー間クレジット機能
- 📋 投資・配当システム
- 📋 通貨レート変動機構

---

## 9. トラブルシューティングリソース

詳細は各仕様書を参照：

- **DB接続エラー** → `02_DATABASE_SPECIFICATION.md`
- **コマンド実行不可** → `03_COMMAND_SPECIFICATION.md`
- **ショップ動作異常** → `04_SHOP_SYSTEM_SPECIFICATION.md`
- **ビルド・デプロイ** → `05_BUILD_AND_DEPLOYMENT.md`

---

## 10. ドキュメント体系

```
仕様フォルダ構成：
├── 01_SYSTEM_OVERVIEW.md               (本ファイル)
├── 02_DATABASE_SPECIFICATION.md
├── 03_COMMAND_SPECIFICATION.md
├── 04_SHOP_SYSTEM_SPECIFICATION.md
├── 05_ECONOMY_SYSTEM_SPECIFICATION.md
├── 06_BUILD_AND_DEPLOYMENT.md
└── MIGRATION_NOTES.md                  (アップデート時の参考)
```

---

**作成・更新者:** りゅう  
**License:** © 2024 Clusters-Prj. All rights reserved.
