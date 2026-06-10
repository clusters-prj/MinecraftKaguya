# FJ Economy - コマンド仕様書

**バージョン:** 1.0.7  
**最終更新:** 2024-12-13  
**対象者:** 開発者・サーバー管理者

---

## 1. コマンド体系概要

FJ Economyのコマンドは3つのグループに分類されます：

| グループ | コマンド | 権限 | 対象プレイヤー |
|---|---|---|---|
| **プレイヤーコマンド** | `/fj` | `fj.use` | 全プレイヤー |
| **ショップコマンド** | `/shop` | `fj.shop.create` | 全プレイヤー |
| **管理者コマンド** | `/fjeadmin` | `fj.admin` | OP・管理者 |

---

## 2. プレイヤーコマンド (`/fj`)

### 2.1 `/fj balance` / `/fj bal`

**説明:** 現在の所持金を表示します。

**使用方法:**
```
/fj balance
/fj bal
```

**権限:** `fj.use` (デフォルト: true)  
**対象者:** プレイヤー本人のみ  
**応答時間:** < 100ms

**実行例:**
```
プレイヤー: /fj balance
サーバー: [FJ Economy] 残高: ¥50000
```

**出力形式:**
```
[FJ Economy] 残高: {symbol}{amount}
```

**エラーケース:**

| エラー | 原因 | 対処 |
|---|---|---|
| プレイヤーが見つかりません | 登録前の初回実行 | 一度サーバーから退出して再入場 |
| データベースエラー | DB接続不可 | サーバー管理者に報告 |

---

### 2.2 `/fj pay <player> <amount>`

**説明:** 他のプレイヤーに所持金を送金します。

**使用方法:**
```
/fj pay りゅう 5000
/fj pay PlayerName 1000
```

**権限:** `fj.pay` (デフォルト: true)  
**パラメータ:**

| パラメータ | 型 | 説明 | 例 |
|---|---|---|---|
| `<player>` | String | 受取プレイヤー名 | りゅう |
| `<amount>` | Integer | 送金額（整数） | 5000 |

**応答時間:** < 500ms  
**トランザクション:** ACID保証

**実行例:**
```
プレイヤー: /fj pay りゅう 5000
サーバー: [FJ Economy] りゅう に ¥5000 を送金しました
りゅう: [FJ Economy] PlayerName から ¥5000 を受け取りました
```

**検証ロジック:**

```
1. 受取プレイヤー存在確認
   → 見つからない場合: 「プレイヤーが見つかりません」
   
2. 送金額チェック
   → 負数: 「金額は正の数である必要があります」
   → 0: 「金額は正の数である必要があります」
   
3. 残高確認
   → 残高不足: 「残高が足りません」
   
4. トランザクション実行
   → 成功: 両者にメッセージ通知
   → 失敗: 「送金に失敗しました」
```

**内部処理:**

```java
// Pseudo-code
BEGIN TRANSACTION {
    balance_sender = SELECT balance WHERE uuid = sender_uuid FOR UPDATE;
    
    if (balance_sender < amount) {
        ROLLBACK;
        return false;  // 残高不足
    }
    
    UPDATE balance WHERE uuid = sender_uuid: balance -= amount;
    UPDATE balance WHERE uuid = receiver_uuid: balance += amount;
    
    COMMIT;
    return true;
}
```

**タブ補完:** 
- `<player>` → オンラインプレイヤーのリストを表示

---

### 2.3 `/fj help`

**説明:** FJ Economyのコマンドヘルプを表示します。

**使用方法:**
```
/fj help
```

**権限:** `fj.use` (デフォルト: true)

**実行例:**
```
プレイヤー: /fj help
サーバー:
=== FJ Economy ヘルプ ===
/fj bal - 残高確認
/fj pay <プレイヤー> <金額> - 送金

(管理者権限がある場合)
/fjeadmin give <プレイヤー> <金額> - 付与(管理者)
/fjeadmin take <プレイヤー> <金額> - 没収(管理者)
/fjeadmin set <プレイヤー> <金額> - 設定(管理者)
/fjeadmin reload - リロード(管理者)
```

---

## 3. ショップコマンド (`/shop`)

### 3.1 `/shop create <npc_uuid> <item> <price> [stock]`

**説明:** NPC店員を登録して商品販売ショップを開設します。

**使用方法:**
```
/shop create 550e8400-e29b-41d4-a716-446655440001 DIAMOND 100
/shop create 550e8400-e29b-41d4-a716-446655440002 EMERALD 500 50
```

**権限:** `fj.shop.create` (デフォルト: true)  
**パラメータ:**

| パラメータ | 型 | 必須 | 説明 | 例 |
|---|---|---|---|---|
| `<npc_uuid>` | UUID | ○ | NPC の UUID | 550e8400-e29b-41d4-a716-446655440001 |
| `<item>` | String | ○ | アイテムID（Bukkit形式） | DIAMOND, IRON_INGOT |
| `<price>` | Integer | ○ | 販売価格（整数） | 100, 500 |
| `[stock]` | Integer | ✗ | 在庫数（デフォルト: 100） | 50, 100 |

**実行例:**
```
プレイヤー: /shop create 550e8400-e29b-41d4-a716-446655440001 DIAMOND 100
サーバー: [FJ Economy] 店舗を作成しました (NPC UUID: 550e8400-e29b-41d4-a716-446655440001, アイテム: DIAMOND, 価格: ¥100)
```

**検証ロジック:**

```
1. NPC UUID形式確認
   → 不正: 「無効なNPC UUIDです」
   
2. 価格・在庫チェック
   → 負数: 「価格と在庫は0以上である必要があります」
   
3. DB登録
   → 成功: 「店舗を作成しました」
   → 既に存在: 情報を上書き（ON DUPLICATE KEY UPDATE）
```

**内部処理:**

```sql
INSERT INTO fje_shops 
  (npc_uuid, server_id, owner_uuid, item_material, price, stock)
VALUES 
  (:npc_uuid, :server_id, :player_uuid, :item, :price, :stock)
ON DUPLICATE KEY UPDATE 
  owner_uuid = :player_uuid, 
  item_material = :item, 
  price = :price, 
  stock = :stock;
```

---

### 3.2 `/shop list [player]`

**説明:** プレイヤーの店舗一覧を表示します。

**使用方法:**
```
/shop list              # 自分の店舗一覧
/shop list りゅう       # りゅうの店舗一覧
```

**権限:** `fj.shop.create` (デフォルト: true)

**実行例:**
```
プレイヤー: /shop list
サーバー: [FJ Economy] りゅう の店舗一覧:
  NPC UUID: 550e8400-e29b-41d4-a716-446655440001 - DIAMOND: ¥100 (在庫: 50個)
  NPC UUID: 550e8400-e29b-41d4-a716-446655440002 - EMERALD: ¥500 (在庫: 30個)
```

**出力形式:**
```
[FJ Economy] {player_name} の店舗一覧:
  NPC UUID: {uuid} - {item}: {symbol}{price} (在庫: {stock}個)
```

---

### 3.3 `/shop info <npc_uuid>`

**説明:** 特定NPCの店舗詳細情報を表示します。

**使用方法:**
```
/shop info 550e8400-e29b-41d4-a716-446655440001
```

**権限:** `fj.shop.create` (デフォルト: true)

**実行例:**
```
プレイヤー: /shop info 550e8400-e29b-41d4-a716-446655440001
サーバー: [FJ Economy] 店舗情報:
  NPC UUID: 550e8400-e29b-41d4-a716-446655440001
  アイテム: DIAMOND
  価格: ¥100
  在庫: 50個
```

---

### 3.4 `/shop setprice <npc_uuid> <price>`

**説明:** 自分の店舗の商品価格を変更します。

**使用方法:**
```
/shop setprice 550e8400-e29b-41d4-a716-446655440001 150
```

**権限:** `fj.shop.create` (デフォルト: true)  
**制限:** 自分の店舗のみ変更可能

**実行例:**
```
プレイヤー: /shop setprice 550e8400-e29b-41d4-a716-446655440001 150
サーバー: [FJ Economy] 価格を ¥150 に設定しました
```

**エラーケース:**

```
エラー: この店舗を管理する権限がありません
原因: 他人の店舗を編集しようとした
```

---

### 3.5 `/shop setstock <npc_uuid> <stock>`

**説明:** 在庫を直接設定します。

**使用方法:**
```
/shop setstock 550e8400-e29b-41d4-a716-446655440001 100
```

**権限:** `fj.shop.create` (デフォルト: true)  
**制限:** 自分の店舗のみ

---

### 3.6 `/shop addstock <npc_uuid> <quantity>`

**説明:** 在庫に商品を追加します。

**使用方法:**
```
/shop addstock 550e8400-e29b-41d4-a716-446655440001 50
```

---

### 3.7 `/shop removestock <npc_uuid> <quantity>`

**説明:** 在庫から商品を削除します。

**使用方法:**
```
/shop removestock 550e8400-e29b-41d4-a716-446655440001 20
```

---

### 3.8 `/shop delete <npc_uuid>`

**説明:** 店舗を削除します。

**使用方法:**
```
/shop delete 550e8400-e29b-41d4-a716-446655440001
```

**権限:** `fj.shop.delete` (デフォルト: op)

---

## 4. 管理者コマンド (`/fjeadmin`)

### 4.1 `/fjeadmin give <player> <amount>`

**説明:** プレイヤーに所持金を付与します。

**使用方法:**
```
/fjeadmin give りゅう 10000
```

**権限:** `fj.admin` (デフォルト: op)  
**パラメータ:**

| パラメータ | 型 | 説明 |
|---|---|---|
| `<player>` | String | プレイヤー名 |
| `<amount>` | Integer | 付与額 |

**実行例:**
```
管理者: /fjeadmin give りゅう 10000
サーバー: [FJ Economy] りゅう に ¥10000 を付与しました
```

**内部処理:**

```
1. プレイヤー存在確認（オンライン不要）
2. アカウント自動作成（初回）
3. 残高更新
```

---

### 4.2 `/fjeadmin take <player> <amount>`

**説明:** プレイヤーから所持金を没収します。

**使用方法:**
```
/fjeadmin take りゅう 5000
```

**権限:** `fj.admin` (デフォルト: op)

**実行例:**
```
管理者: /fjeadmin take りゅう 5000
サーバー: [FJ Economy] りゅう から ¥5000 を没収しました
```

**検証:**
```
新残高 = 現在残高 - 没収額
if (新残高 < 0) {
    // allow_negative = true の場合のみ許可
}
```

---

### 4.3 `/fjeadmin set <player> <amount>`

**説明:** プレイヤーの残高を直接設定します。

**使用方法:**
```
/fjeadmin set りゅう 50000
```

**権限:** `fj.admin` (デフォルト: op)

**実行例:**
```
管理者: /fjeadmin set りゅう 50000
サーバー: [FJ Economy] りゅう の残高を ¥50000 に設定しました
```

**危険性:** **全プレイヤーの残高を上書き可能。使用時は慎重に。**

---

### 4.4 `/fjeadmin reload`

**説明:** config.ymlを再読み込みしてプラグイン設定をリロードします。

**使用方法:**
```
/fjeadmin reload
```

**権限:** `fj.reload` (デフォルト: op)

**実行例:**
```
管理者: /fjeadmin reload
サーバー: [FJ Economy] FJ Economy をリロードしました
```

**リロード対象:**

```yaml
✅ リロード可能（即座に反映）:
  - currency.symbol
  - economy.tax_rate
  - economy.starting_balance
  - shop.default_stock
  - messages.*

⚠️  リロード時に再接続（接続が死ぬ可能性）:
  - database.url
  - database.username
  - database.password
  - database.pool_size
```

**内部処理:**

```java
if (isDatabaseConfigChanged()) {
    // DB接続を切断・再確立
    databaseManager.shutdown();
    databaseManager = new DatabaseManager(plugin, configManager);
    databaseManager.initialize();
} else {
    // メモリ内の設定のみ更新（高速）
}
```

---

## 5. 権限体系（Permission Tree）

```
fj.use
  ├─ /fj balance
  ├─ /fj pay
  └─ /fj help

fj.pay (fj.use に含まれる)
  └─ /fj pay に特別権限は不要

fj.shop.create (デフォルト: true)
  ├─ /shop create
  ├─ /shop list
  ├─ /shop info
  ├─ /shop setprice
  ├─ /shop setstock
  ├─ /shop addstock
  └─ /shop removestock

fj.shop.delete (デフォルト: op)
  └─ /shop delete

fj.admin (デフォルト: op)
  ├─ /fjeadmin give
  ├─ /fjeadmin take
  └─ /fjeadmin set

fj.reload (デフォルト: op)
  └─ /fjeadmin reload
```

---

## 6. plugin.yml 権限設定

```yaml
permissions:
  fj.use:
    description: FJ Economy基本コマンド実行
    default: true
  
  fj.pay:
    description: プレイヤー間送金
    default: true
  
  fj.shop.create:
    description: ショップ作成・管理
    default: true
  
  fj.shop.delete:
    description: ショップ削除
    default: op
  
  fj.admin:
    description: 管理者コマンド（give/take/set）
    default: op
  
  fj.reload:
    description: プラグイン再読込み
    default: op
```

---

## 7. LuckPerms 設定例

```
# LuckPerms CLI で権限を設定する場合

# デフォルトグループに fj.use を付与
lp group default permission set fj.use true

# VIP グループに fj.shop.create を制限（デフォルト許可から制外）
lp group vip permission set fj.shop.create true

# Admin グループに全権限を付与
lp group admin permission set fj.* true

# 特定プレイヤーに管理者権限を付与
lp user りゅう permission set fj.admin true
lp user りゅう permission set fj.reload true
```

---

## 8. タブ補完

### 対応コマンド

| コマンド | 補完内容 | 例 |
|---|---|---|
| `/fj <tab>` | balance, pay, help | balance, pay |
| `/fj pay <player> <tab>` | オンラインプレイヤー | Player1, Player2 |
| `/fjeadmin <tab>` | give, take, set, reload | give, take, set |
| `/fjeadmin give <player> <tab>` | オンラインプレイヤー | Player1, Player2 |
| `/shop <tab>` | create, delete, list, info, setprice, ... | create, delete |
| `/shop list <tab>` | オンラインプレイヤー | Player1, Player2 |

---

## 9. エラーメッセージリファレンス

### 一般的なエラー

| メッセージ | 原因 | 対処法 |
|---|---|---|
| プレイヤーが見つかりません | プレイヤー名が間違っている、またはオンラインではない | プレイヤー名を確認 |
| 無効な金額です | 金額が数値でない、または極端に大きい | 正の整数を入力 |
| 金額は正の数である必要があります | 0または負数を入力 | 正の値のみ |
| 残高が足りません | 送金額が残高を超えている | 残高を確認してから実行 |
| このコマンドを実行する権限がありません | 権限不足 | 管理者に権限付与を申請 |
| この店舗を管理する権限がありません | 他人の店舗を編集 | 自分の店舗のみ編集可能 |

---

## 10. 実装時の注意点

### 10.1 비동기 처리

送金・購入等の金銭操作は **必ず非同期** で実行：

```java
Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
    // DB操作
    boolean result = economyManager.sendMoney(...);
    
    // UI更新はメインスレッドで
    Bukkit.getScheduler().runTask(plugin, () -> {
        if (result) {
            player.sendMessage("成功");
        } else {
            player.sendMessage("失敗");
        }
    });
});
```

### 10.2 トランザクション保護

複数の残高更新が必要な場合：

```java
// ❌ 非保護（データ不整合の危険）
economyManager.takeMoney(buyer, 100);
economyManager.giveMoney(seller, 90);
economyManager.giveMoney(government, 10);

// ✅ トランザクション保護
try (Connection conn = dbManager.getConnection()) {
    conn.setAutoCommit(false);
    try {
        // 3つの操作を一括処理
        conn.commit();
    } catch (Exception e) {
        conn.rollback();
    }
}
```

---

**作成・更新者:** りゅう  
**License:** © 2024 Clusters-Prj. All rights reserved.
