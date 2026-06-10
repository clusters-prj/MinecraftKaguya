# FJ Economy - データベース仕様書

**バージョン:** 1.0.7  
**最終更新:** 2024-12-13  
**対象者:** 開発者・DBA

---

## 1. 概要

FJ Economyのデータベースは MariaDB 8.0+ で構成され、4つのコアテーブルと複数のインデックスで経済データを管理します。全通貨値は **BIGINT** で厳密に管理し、浮動小数点誤差を完全排除します。

### 接続情報

```
Host:     10.2.1.27
Port:     3306
Database: fjeconomy
Charset:  utf8mb4 (Unicode対応)
Collation: utf8mb4_unicode_ci
```

### テーブル一覧

| テーブル | 主キー | 役割 |
|---|---|---|
| `fje_balances` | `uuid` | プレイヤー・政府の残高管理 |
| `fje_shops` | `(npc_uuid, server_id)` | NPC店舗の設定・在庫 |
| `fje_transactions` | `id` | 全取引レシート（監査証跡） |
| `fje_government_ledger` | `id` | 政府収支台帳 |

---

## 2. テーブル定義

### 2.1 fje_balances（プレイヤー残高台帳）

プレイヤーと政府の通貨残高を一元管理。全サーバー間で共有される最重要テーブル。

#### CREATE文

```sql
CREATE TABLE IF NOT EXISTS fje_balances (
    uuid UUID PRIMARY KEY 
        DEFAULT '00000000-0000-0000-0000-000000000000',
    player_name VARCHAR(255) NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    last_update TIMESTAMP 
        DEFAULT CURRENT_TIMESTAMP 
        ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_name (player_name)
) ENGINE=InnoDB 
  DEFAULT CHARSET=utf8mb4 
  COLLATE=utf8mb4_unicode_ci;
```

#### カラム詳細

| カラム名 | 型 | 制約 | デフォルト | 説明 |
|---|---|---|---|---|
| `uuid` | UUID | PRIMARY KEY | - | プレイヤーまたは政府のUUID |
| `player_name` | VARCHAR(255) | NOT NULL | - | 識別用表示名（ユニークではない） |
| `balance` | BIGINT | NOT NULL | 0 | **現在の所持金（整数）** |
| `last_update` | TIMESTAMP | NOT NULL | CURRENT_TIMESTAMP | 最終更新日時（自動記録） |

#### インデックス

| インデックス名 | カラム | 用途 |
|---|---|---|
| PRIMARY | uuid | プレイヤー検索（主キー） |
| idx_name | player_name | プレイヤー名検索 |

#### 用途例

```sql
-- プレイヤーの残高を確認
SELECT balance FROM fje_balances WHERE uuid = '550e8400-e29b-41d4-a716-446655440000';

-- プレイヤー名から残高を検索
SELECT balance FROM fje_balances WHERE player_name = 'りゅう';

-- 政府口座の残高を確認
SELECT balance FROM fje_balances WHERE uuid = '00000000-0000-0000-0000-000000000001';

-- 長者番付（残高TOP10）
SELECT player_name, balance FROM fje_balances 
WHERE uuid != '00000000-0000-0000-0000-000000000001'  -- 政府を除外
ORDER BY balance DESC LIMIT 10;
```

---

### 2.2 fje_shops（NPC店舗設定）

NPCショップの設定情報と在庫を管理。各サーバーのNPC毎に1レコード。

#### CREATE文

```sql
CREATE TABLE IF NOT EXISTS fje_shops (
    npc_uuid UUID NOT NULL,
    server_id VARCHAR(20) NOT NULL,
    owner_uuid UUID NOT NULL,
    item_material VARCHAR(255) NOT NULL,
    item_nbt TEXT,
    price INT NOT NULL DEFAULT 0,
    stock INT NOT NULL DEFAULT 0,
    PRIMARY KEY (npc_uuid, server_id),
    INDEX idx_owner (owner_uuid),
    INDEX idx_item (item_material),
    FOREIGN KEY (owner_uuid) 
        REFERENCES fje_balances(uuid)
) ENGINE=InnoDB 
  DEFAULT CHARSET=utf8mb4 
  COLLATE=utf8mb4_unicode_ci;
```

#### カラム詳細

| カラム名 | 型 | 制約 | デフォルト | 説明 |
|---|---|---|---|---|
| `npc_uuid` | UUID | PRIMARY KEY | - | **NPC の UUID** |
| `server_id` | VARCHAR(20) | PRIMARY KEY | - | サーバーID（mc1, mc2, mc3等） |
| `owner_uuid` | UUID | NOT NULL | - | 店主のプレイヤーUUID |
| `item_material` | VARCHAR(255) | NOT NULL | - | アイテムID（DIAMOND等） |
| `item_nbt` | TEXT | - | NULL | NBT データ（エンチャント等、オプション） |
| `price` | INT | NOT NULL | 0 | **販売価格（整数）** |
| `stock` | INT | NOT NULL | 0 | 在庫数 |

#### 主キー・外部キー

- **主キー:** `(npc_uuid, server_id)` → 同じNPC IDでも異なるサーバーなら別レコード
- **外部キー:** `owner_uuid` → `fje_balances(uuid)`（店主は必ずプレイヤー口座を持つ）

#### インデックス

| インデックス名 | カラム | 用途 |
|---|---|---|
| PRIMARY | (npc_uuid, server_id) | NPC検索（複合主キー） |
| idx_owner | owner_uuid | プレイヤーの全店舗検索 |
| idx_item | item_material | アイテム別店舗検索 |

#### 用途例

```sql
-- 特定NPCの店舗情報を確認
SELECT * FROM fje_shops 
WHERE npc_uuid = '550e8400-e29b-41d4-a716-446655440001' 
AND server_id = 'mc1';

-- プレイヤーの全店舗一覧
SELECT npc_uuid, item_material, price, stock 
FROM fje_shops 
WHERE owner_uuid = '550e8400-e29b-41d4-a716-446655440000'
AND server_id = 'mc1';

-- ダイヤモンドを販売している店舗
SELECT owner_uuid, npc_uuid, price, stock 
FROM fje_shops 
WHERE item_material = 'DIAMOND' 
ORDER BY price ASC;
```

---

### 2.3 fje_transactions（取引レシート）

全ての売買取引を記録。監査証跡・Web分析用の詳細ログ。

#### CREATE文

```sql
CREATE TABLE IF NOT EXISTS fje_transactions (
    id INT AUTO_INCREMENT PRIMARY KEY,
    timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    server_id VARCHAR(20) NOT NULL,
    buyer_uuid UUID NOT NULL,
    owner_uuid UUID NOT NULL,
    item_id VARCHAR(255) NOT NULL,
    amount INT NOT NULL DEFAULT 1,
    price_total INT NOT NULL DEFAULT 0,
    tax_amount INT NOT NULL DEFAULT 0,
    net_profit INT NOT NULL DEFAULT 0,
    INDEX idx_timestamp (timestamp),
    INDEX idx_buyer (buyer_uuid),
    INDEX idx_owner (owner_uuid),
    FOREIGN KEY (buyer_uuid) 
        REFERENCES fje_balances(uuid),
    FOREIGN KEY (owner_uuid) 
        REFERENCES fje_balances(uuid)
) ENGINE=InnoDB 
  DEFAULT CHARSET=utf8mb4 
  COLLATE=utf8mb4_unicode_ci;
```

#### カラム詳細

| カラム名 | 型 | 制約 | デフォルト | 説明 |
|---|---|---|---|---|
| `id` | INT | PRIMARY KEY AUTO_INC | - | 一意の記録ID |
| `timestamp` | DATETIME | NOT NULL | CURRENT_TIMESTAMP | 取引日時（自動記録） |
| `server_id` | VARCHAR(20) | NOT NULL | - | 発生サーバー（mc1等） |
| `buyer_uuid` | UUID | NOT NULL | - | 購入者のUUID |
| `owner_uuid` | UUID | NOT NULL | - | 店主のUUID |
| `item_id` | VARCHAR(255) | NOT NULL | - | 購入アイテムID |
| `amount` | INT | NOT NULL | 1 | **購入個数** |
| `price_total` | INT | NOT NULL | 0 | **支払総額（tax込み）** |
| `tax_amount` | INT | NOT NULL | 0 | **徴収された税金** |
| `net_profit` | INT | NOT NULL | 0 | **店主へ渡った純利益** |

#### 整合性制約

```
price_total = tax_amount + net_profit  (常に成立)

例）
  price_total = 100
  tax_amount  = 10 (10%)
  net_profit  = 90
  
  検査: 100 = 10 + 90 ✅
```

#### インデックス

| インデックス名 | カラム | 用途 |
|---|---|---|
| PRIMARY | id | レコード取得 |
| idx_timestamp | timestamp | 日付範囲検索（統計用） |
| idx_buyer | buyer_uuid | 買い手の取引履歴 |
| idx_owner | owner_uuid | 店主の売上履歴 |

#### 用途例

```sql
-- プレイヤーの購入履歴（最近10件）
SELECT timestamp, item_id, amount, price_total 
FROM fje_transactions 
WHERE buyer_uuid = '550e8400-e29b-41d4-a716-446655440000'
ORDER BY timestamp DESC LIMIT 10;

-- 店主の売上合計
SELECT 
    DATE(timestamp) AS sale_date,
    SUM(net_profit) AS daily_profit,
    COUNT(*) AS transaction_count
FROM fje_transactions
WHERE owner_uuid = '550e8400-e29b-41d4-a716-446655440000'
GROUP BY DATE(timestamp)
ORDER BY sale_date DESC;

-- 本日の全取引（サーバー別）
SELECT 
    server_id,
    COUNT(*) AS count,
    SUM(price_total) AS total_sales,
    SUM(tax_amount) AS total_tax
FROM fje_transactions
WHERE DATE(timestamp) = CURDATE()
GROUP BY server_id;
```

---

### 2.4 fje_government_ledger（政府財政台帳）

政府口座の全入出金を記録。国庫の透明性確保・経済監視用。

#### CREATE文

```sql
CREATE TABLE IF NOT EXISTS fje_government_ledger (
    id INT AUTO_INCREMENT PRIMARY KEY,
    timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    type VARCHAR(20) NOT NULL,
    amount BIGINT NOT NULL DEFAULT 0,
    description TEXT,
    INDEX idx_timestamp (timestamp),
    INDEX idx_type (type)
) ENGINE=InnoDB 
  DEFAULT CHARSET=utf8mb4 
  COLLATE=utf8mb4_unicode_ci;
```

#### カラム詳細

| カラム名 | 型 | 制約 | デフォルト | 説明 |
|---|---|---|---|---|
| `id` | INT | PRIMARY KEY AUTO_INC | - | 一意の記録ID |
| `timestamp` | DATETIME | NOT NULL | CURRENT_TIMESTAMP | 記録日時 |
| `type` | VARCHAR(20) | NOT NULL | - | 入出金タイプ（TAX_IN, EVENT_OUT等） |
| `amount` | BIGINT | NOT NULL | 0 | **動いた金額** |
| `description` | TEXT | - | NULL | 理由・用途の詳細 |

#### トランザクションタイプ

| タイプ | 説明 | 方向 | 用途例 |
|---|---|---|---|
| `TAX_IN` | 税金収入 | ＋ | NPC購入時の自動課税 |
| `EVENT_OUT` | イベント支給 | － | 給与配布・ボーナス |
| `MANUAL_IN` | 手動入金 | ＋ | 管理者による調整 |
| `MANUAL_OUT` | 手動出金 | － | 管理者による調整 |
| `INFLATION_ADJUST` | インフレ調整 | ±| 経済危機対応 |

#### インデックス

| インデックス名 | カラム | 用途 |
|---|---|---|
| PRIMARY | id | レコード取得 |
| idx_timestamp | timestamp | 期間別集計 |
| idx_type | type | トランザクションタイプ別集計 |

#### 用途例

```sql
-- 本日の政府収入
SELECT 
    type,
    COUNT(*) AS count,
    SUM(amount) AS total
FROM fje_government_ledger
WHERE DATE(timestamp) = CURDATE() AND type = 'TAX_IN'
GROUP BY type;

-- 月間政府収支
SELECT 
    DATE_FORMAT(timestamp, '%Y-%m') AS month,
    SUM(CASE WHEN type LIKE '%IN' THEN amount ELSE 0 END) AS income,
    SUM(CASE WHEN type LIKE '%OUT' THEN amount ELSE 0 END) AS expense
FROM fje_government_ledger
GROUP BY month
ORDER BY month DESC;

-- 過去7日の政府口座推移
SELECT 
    DATE(timestamp) AS date,
    SUM(CASE WHEN type LIKE '%IN' THEN amount ELSE -amount END) AS net_flow
FROM fje_government_ledger
WHERE timestamp >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)
GROUP BY DATE(timestamp)
ORDER BY date DESC;
```

---

## 3. データ型と制約

### 3.1 UUID の使用

全UUIDはJavaの `UUID.toString()` 形式で保存：

```
例: 550e8400-e29b-41d4-a716-446655440000
```

MariaDB 8.0.22以降の `UUID` 型を使用することで、自動的に内部で最適化されます。

### 3.2 通貨値の型選択

| 用途 | 型 | 理由 | 最大値 |
|---|---|---|---|
| プレイヤー残高 | BIGINT | 大口取引対応 | ±9,223,372,036,854,775,807 |
| 取引価格 | INT | 単一取引は小さい | ±2,147,483,647 |
| 在庫数 | INT | 物理的上限あり | ±2,147,483,647 |

### 3.3 文字列長

| フィールド | 型 | 理由 |
|---|---|---|
| player_name | VARCHAR(255) | Minecraftプレイヤー名は16字だが、将来の表示名対応に備える |
| server_id | VARCHAR(20) | mc1, mc2_jp_survival等、十分な長さ |
| item_material | VARCHAR(255) | MinecraftアイテムID + NBT対応用 |

---

## 4. データ整合性ルール

### 4.1 トランザクション整合性

複数テーブルにまたがる操作は **BEGIN TRANSACTION ～ COMMIT/ROLLBACK** で保護：

```sql
-- 例：商品購入
START TRANSACTION;

  UPDATE fje_balances SET balance = balance - 100 WHERE uuid = '買い手';
  UPDATE fje_balances SET balance = balance + 90 WHERE uuid = '売り手';
  UPDATE fje_balances SET balance = balance + 10 WHERE uuid = '政府';
  INSERT INTO fje_transactions (...) VALUES (...);
  INSERT INTO fje_government_ledger (...) VALUES (...);

COMMIT;  -- 全ステップ成功
-- エラー時: ROLLBACK;
```

### 4.2 金額の整合性

```
fje_transactions における必須条件：
  price_total = tax_amount + net_profit

プレイヤー残高の満額・不足チェック：
  before_update: 
    SELECT balance FROM fje_balances WHERE uuid = ? FOR UPDATE;
    if (balance >= amount) {
      // 更新許可
    }
```

### 4.3 外部キー制約

```sql
-- fje_shops.owner_uuid → fje_balances.uuid
ALTER TABLE fje_shops 
ADD CONSTRAINT fk_shop_owner 
FOREIGN KEY (owner_uuid) 
REFERENCES fje_balances(uuid);

-- 店主が削除されても自動削除せず、orphan化させない
-- （政府口座には削除禁止フラグを設定）
```

---

## 5. 初期化・メンテナンス

### 5.1 テーブル作成スクリプト

プラグイン起動時に自動実行される DatabaseManager#createTables() 内で実行：

```java
public void createTables() throws SQLException {
    // fje_balances, fje_shops, fje_transactions, fje_government_ledger
    // を順序立てて CREATE TABLE IF NOT EXISTS で作成
}
```

### 5.2 バックアップ戦略

```bash
# 毎日深夜にフルバックアップ（cron推奨）
mysqldump -h 10.2.1.27 -u fjeconomy -p fjeconomy > /backups/fjeconomy_$(date +%Y%m%d).sql

# 増分バックアップ（オプション）
mysqlbinlog --start-datetime='2024-12-13 00:00:00' /var/log/mysql/mysql-bin.* > /backups/incremental.sql
```

### 5.3 定期メンテナンス

```sql
-- インデックス最適化（月1回）
OPTIMIZE TABLE fje_balances, fje_shops, fje_transactions, fje_government_ledger;

-- 統計更新
ANALYZE TABLE fje_balances, fje_shops, fje_transactions, fje_government_ledger;

-- 古いトランザクション削除（90日以上前、オプション）
DELETE FROM fje_transactions WHERE timestamp < DATE_SUB(CURDATE(), INTERVAL 90 DAY);
```

---

## 6. パフォーマンスチューニング

### 6.1 インデックス戦略

現在のインデックス設定は以下の用途に最適化：

| インデックス | カバリング | 想定クエリ |
|---|---|---|
| PRIMARY (uuid) | ✅ | SELECT * FROM fje_balances WHERE uuid = ? |
| idx_name | ✅ | SELECT balance FROM fje_balances WHERE player_name = ? |
| idx_owner (shop) | ✅ | SELECT * FROM fje_shops WHERE owner_uuid = ? AND server_id = ? |
| idx_timestamp | ❌ | SELECT * FROM fje_transactions WHERE timestamp BETWEEN ? AND ? |

### 6.2 クエリ最適化

```sql
-- ❌ 遅い（フルテーブルスキャン）
SELECT * FROM fje_transactions 
WHERE buyer_uuid = ? AND timestamp > DATE_SUB(NOW(), INTERVAL 30 DAY);

-- ✅ 速い（複合インデックス推奨）
SELECT * FROM fje_transactions 
WHERE timestamp > DATE_SUB(NOW(), INTERVAL 30 DAY) AND buyer_uuid = ?;
-- インデックス: (timestamp, buyer_uuid)
```

### 6.3 コネクション設定（HikariCP）

```yaml
database:
  pool_size: 10              # 同時接続数（MC1～3計）
  max_lifetime: 1800000      # 30分でリセット
  connection_timeout: 10000  # 10秒でTimeout
```

---

## 7. トラブルシューティング

### 7.1 テーブルが見つからない

```
[ERROR] java.sql.SQLException: Table 'fjeconomy.fje_balances' doesn't exist
```

**原因:** プラグイン初期化時にテーブル作成が失敗した。

**解決:**
```sql
-- 手動でテーブルを再作成
DROP DATABASE IF EXISTS fjeconomy;
CREATE DATABASE fjeconomy CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON fjeconomy.* TO 'fjeconomy'@'%';

-- プラグインを再起動（自動作成）
```

### 7.2 外部キー制約エラー

```
[ERROR] Cannot add or update a child row: a foreign key constraint fails
```

**原因:** 存在しないUUIDで店舗を作成しようとした。

**解決:**
```sql
-- 店主アカウントを先に作成
INSERT INTO fje_balances (uuid, player_name, balance) 
VALUES (UUID(), 'shopowner', 1000);

-- その後、ショップを作成
```

### 7.3 ロック・デッドロック

```
[ERROR] java.sql.SQLException: Deadlock found when trying to get lock; try restarting transaction
```

**原因:** 複数スレッドから同じプレイヤーの残高を更新しようとした。

**解決:**
- HikariCP接続プール数を増やす
- 長いトランザクションを分割
- `FOR UPDATE` クローズを使用

```sql
SELECT balance FROM fje_balances WHERE uuid = ? FOR UPDATE;
-- ← ロック獲得してから更新
```

---

## 8. モニタリングクエリ集

### 状態確認

```sql
-- テーブルサイズ
SELECT 
    TABLE_NAME,
    ROUND(((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024), 2) AS size_mb
FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_SCHEMA = 'fjeconomy';

-- コネクション状況
SHOW PROCESSLIST;

-- InnoDB状況
SHOW ENGINE INNODB STATUS\G
```

---

**作成・更新者:** りゅう  
**License:** © 2024 Clusters-Prj. All rights reserved.
