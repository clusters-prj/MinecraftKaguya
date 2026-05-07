# FJ Economy - 統合経済プラグイン

Paper Minecraft サーバー向けの完全独自設計の経済システムです。Proxmox上の複数サーバー間でリアルタイムに所持金を同期し、Web経由での分析・管理を可能にします。

## 概要

- **リアルタイム同期**: 複数サーバー間で所持金を即座に同期
- **中央集約型DB**: MariaDB（10.2.1.27）を唯一の真実として運用
- **整数管理**: 浮動小数点誤差を排除、1円単位で管理
- **自動課税**: 商品売買時に自動的に税金を徴収
- **非同期処理**: ゲームサーバーのメインスレッドをブロックしない
- **リロード対応**: config.ymlを変更して `/fjeadmin reload` で設定を再読み込み可能

## 必要な環境

- **Java**: 17 以上
- **Paper**: 1.21 以上
- **MariaDB/MySQL**: 8.0 以上
- **Maven**: 3.9 以上（ビルド時）

## セットアップ手順

### 1. データベースの準備

```sql
-- MariaDB にデータベースとユーザーを作成
CREATE DATABASE fjeconomy CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ユーザーを作成（パスワードは変更してください）
CREATE USER 'fjeconomy'@'%' IDENTIFIED BY 'your_password_here';
GRANT ALL PRIVILEGES ON fjeconomy.* TO 'fjeconomy'@'%';
FLUSH PRIVILEGES;
```

### 2. プラグインのビルド

```bash
git clone <repository-url> FJEconomy
cd FJEconomy
mvn clean package
```

ビルド成功後、`target/fj-economy-1.0.0.jar` が生成されます。

### 3. インストール

```bash
# Paper サーバーの plugins フォルダにコピー
cp target/fj-economy-1.0.0.jar /path/to/server/plugins/
```

### 4. 設定ファイルの編集

サーバーを起動すると `plugins/FJEconomy/config.yml` が生成されます。

```yaml
database:
  url: "jdbc:mariadb://10.2.1.27:3306/fjeconomy?characterEncoding=utf8mb4&serverTimezone=Asia/Tokyo"
  username: "fjeconomy"
  password: "your_password_here"  # ← ここを設定したパスワードに変更
  
server:
  id: "mc1"  # サーバーID (mc1, mc2, mc3 など)
  name: "Main Server"

economy:
  tax_rate: 10.0  # 税率 (%)
  starting_balance: 1000  # 新規プレイヤーの初期資金
```

### 5. サーバーを再起動

設定を反映させるためサーバーを再起動します。

```
stop
# サーバーを再起動
```

## 使用コマンド

### プレイヤーコマンド

| コマンド | 説明 | 権限 |
|---------|------|------|
| `/fj balance` / `/fj bal` | 現在の残高を確認 | `fj.use` |
| `/fj pay <player> <amount>` | 他のプレイヤーに送金 | `fj.pay` |
| `/fj help` | ヘルプを表示 | `fj.use` |

### 管理者コマンド

| コマンド | 説明 | 権限 |
|---------|------|------|
| `/fjeadmin give <player> <amount>` | プレイヤーにお金を付与 | `fj.admin` |
| `/fjeadmin take <player> <amount>` | プレイヤーからお金を没収 | `fj.admin` |
| `/fjeadmin set <player> <amount>` | プレイヤーのお金を直接設定 | `fj.admin` |
| `/fjeadmin reload` | 設定をリロード | `fj.reload` |

## 設定ファイルの詳細

### database セクション

```yaml
database:
  url: "jdbc:mariadb://host:port/database"  # 接続URL
  username: "user"                          # ユーザー名
  password: "password"                      # パスワード
  pool_size: 10                            # コネクションプール最大数
  max_lifetime: 1800000                    # コネクション最大生存時間 (ms)
```

### server セクション

```yaml
server:
  id: "mc1"           # サーバーID（ユニークであること）
  name: "Main Server" # 表示用サーバー名
```

### currency セクション

```yaml
currency:
  symbol: "¥"              # 通貨記号
  name: "FJ Credits"       # 通貨名
  decimal_places: 0        # 小数点桁数（推奨: 0）
```

### economy セクション

```yaml
economy:
  tax_rate: 10.0           # 税率 (%)
  rounding_method: "HALF_UP"  # 端数処理 (HALF_UP or DOWN)
  starting_balance: 1000   # 新規プレイヤーの初期資金
  allow_negative: false    # 負債を許可するか
```

### shop セクション

```yaml
shop:
  default_stock: 100       # デフォルト在庫数
  sync_prices: true        # Webからの価格同期を有効にするか
  sync_interval: 300       # 同期間隔 (秒)
```

### government セクション

```yaml
government:
  uuid: "00000000-0000-0000-0000-000000000001"  # 政府口座UUID
  name: "GOVERNMENT"                            # 政府口座名
```

## リロード方法

config.yml を編集した後、以下のコマンドで変更を反映させます：

```
/fjeadmin reload
```

以下の項目が変更された場合、データベース接続が再確立されます：
- `database.url`
- `database.username`

それ以外の設定変更はメモリ内に即座に反映されます。

## データベーススキーマ

### fje_balances（プレイヤー残高）

プレイヤーと政府の所持金を管理するテーブル。

| 列 | 型 | 説明 |
|----|-----|------|
| uuid | VARCHAR(36) | プレイヤー/政府のUUID（主キー） |
| player_name | VARCHAR(16) | プレイヤー名 |
| balance | INT | 所持金（整数） |
| last_update | TIMESTAMP | 最終更新時刻 |

### fje_shops（モブショップ）

NPC店主の設定と在庫を管理。

| 列 | 型 | 説明 |
|----|-----|------|
| npc_id | INT | NPC識別子（主キー） |
| server_id | VARCHAR(20) | サーバーID（主キー） |
| owner_uuid | VARCHAR(36) | 店主のUUID |
| item_material | VARCHAR(64) | アイテムID |
| price | INT | 販売価格（整数） |
| stock | INT | 在庫数 |

### fje_transactions（取引レシート）

すべての取引履歴を記録。

| 列 | 型 | 説明 |
|----|-----|------|
| id | INT | 記録ID（主キー、自動採番） |
| timestamp | DATETIME | 取引日時 |
| server_id | VARCHAR(20) | 発生サーバー |
| buyer_uuid | VARCHAR(36) | 購入者のUUID |
| owner_uuid | VARCHAR(36) | 店主のUUID |
| item_id | VARCHAR(64) | アイテムID |
| price_total | INT | 支払総額（整数） |
| tax_amount | INT | 徴収された税金（整数） |
| net_profit | INT | 店主の受取額（整数） |

### fje_government_ledger（政府財政台帳）

政府口座の全入出金を記録。

| 列 | 型 | 説明 |
|----|-----|------|
| id | INT | 記録ID（主キー、自動採番） |
| timestamp | DATETIME | 記録日時 |
| type | VARCHAR(20) | 種類（TAX_IN, EVENT_OUT等） |
| amount | INT | 金額（整数） |
| description | TEXT | 説明 |

## トラブルシューティング

### データベース接続エラー

```
[ERROR] Database connection error: ...
```

**原因**: データベースの接続情報が正しくない可能性があります。

**確認事項**:
1. `config.yml` の `database.url`, `database.username`, `database.password` を確認
2. MariaDB/MySQL がデータベースサーバーで起動しているか確認
3. ユーザーにデータベースへのアクセス権限があるか確認

```bash
# MySQL コマンドラインで接続テスト
mysql -h 10.2.1.27 -u fjeconomy -p fjeconomy -e "SELECT 1"
```

### テーブル作成エラー

```
[ERROR] Table creation error: ...
```

**原因**: 既に同じテーブルが存在するか、権限不足の可能性があります。

**解決法**:
```sql
-- テーブルを削除してリセット（本番環境ではしないこと！）
DROP TABLE IF EXISTS fjeconomy.fje_government_ledger;
DROP TABLE IF EXISTS fjeconomy.fje_transactions;
DROP TABLE IF EXISTS fjeconomy.fje_shops;
DROP TABLE IF EXISTS fjeconomy.fje_balances;
```

その後サーバーを再起動してテーブルを再作成します。

### コマンドが実行できない

```
[ERROR] You don't have permission to perform this command
```

**原因**: プレイヤーが必要な権限を持っていません。

**解決法**: `permissions.yml` または権限プラグイン（LuckPerms等）で権限を付与します。

```yaml
permissions:
  fj.use:
    default: true
  fj.pay:
    default: true
  fj.admin:
    default: op
```

## マルチサーバー構成での運用

複数のサーバーで同じDBを使用する場合：

1. 各サーバーの `config.yml` で同じ `database.url` を指定
2. `server.id` をユニークに設定（mc1, mc2, mc3）
3. すべてのサーバーを同じMariaDBに接続

例:
```yaml
# server1 (VMID 101)
server:
  id: "mc1"
  name: "Main Server"

# server2 (VMID 301)
server:
  id: "mc2"
  name: "Survival Server"

# server3 (External PC)
server:
  id: "mc3"
  name: "Creative Server"
```

## セキュリティ上の推奨事項

- ✅ `config.yml` のパスワードを強力に設定
- ✅ MariaDB のリモートアクセスを制限（VPN/IPホワイトリスト）
- ✅ Web API token を変更
- ✅ 定期的にデータベースをバックアップ
- ✅ `fj.admin` 権限を信頼できるプレイヤーのみに付与

## ライセンス

© 2024 Clusters-Prj. All rights reserved.

## サポート

問題が発生した場合は、以下の情報を含めて報告してください：

- エラーメッセージ全文
- `plugins/FJEconomy/logs/economy.log` の関連部分
- サーバーのバージョン (Paper バージョン、Java バージョン)
- `config.yml` （パスワードを除く）
