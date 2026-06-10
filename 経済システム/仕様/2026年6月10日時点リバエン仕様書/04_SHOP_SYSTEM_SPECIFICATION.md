# FJ Economy - ショップシステム仕様書

**バージョン:** 1.0.7  
**最終更新:** 2024-12-13  
**対象者:** 開発者（プログラマー向け詳細版）

---

## 1. ショップシステム概要

FJ Economyのショップシステムは、Paper Minecraftサーバー上のNPC（Entity）を「店員」として機能させ、プレイヤーが直接インタラクトして商品を購入できる仕組みを提供します。

### 主要機能

- **NPC店舗登録:** 任意のエンティティをショップ店員に指定
- **自動課税:** 購入時に自動的に税金を徴収
- **マルチサーバー対応:** 同じ NPC ID でも異なるサーバー間で独立管理
- **在庫管理:** DB経由での在庫追跡
- **オーナーシップ:** 各店舗にプレイヤーを紐付けて売上管理

---

## 2. データモデル

### 2.1 Shop クラス

ショップの情報を保持するデータクラス。

```java
public static class Shop {
    private final UUID npcUuid;           // NPC の UUID
    private final String serverId;        // サーバーID（mc1, mc2, mc3）
    private final UUID ownerUUID;         // 店主のプレイヤーUUID
    private final String itemMaterial;    // アイテムID（DIAMOND等）
    private final String itemNBT;         // NBT データ（オプション）
    private final int price;              // 販売価格（整数）
    private final int stock;              // 在庫数
    
    public Shop(UUID npcUuid, String serverId, UUID ownerUUID, 
               String itemMaterial, String itemNBT, int price, int stock) {
        this.npcUuid = npcUuid;
        this.serverId = serverId;
        this.ownerUUID = ownerUUID;
        this.itemMaterial = itemMaterial;
        this.itemNBT = itemNBT;
        this.price = price;
        this.stock = stock;
    }
    
    // Getters
    public UUID getNpcUuid() { return npcUuid; }
    public String getServerId() { return serverId; }
    public UUID getOwnerUUID() { return ownerUUID; }
    public String getItemMaterial() { return itemMaterial; }
    public String getItemNBT() { return itemNBT; }
    public int getPrice() { return price; }
    public int getStock() { return stock; }
    
    // ビジネスロジック
    public boolean hasStock(int quantity) {
        return stock >= quantity;
    }
    
    public String getDisplayInfo(String currencySymbol) {
        return String.format("%s: %s%d (在庫: %d個)", 
            itemMaterial, currencySymbol, price, stock);
    }
}
```

---

## 3. ShopManager API

ShopManager は全ショップ操作の中心インターフェース。DatabaseManager経由でDB操作を行います。

### 3.1 ショップ作成

#### 基本版（NBT無し）

```java
public boolean createShop(UUID npcUuid, String serverId, UUID ownerUUID, 
                         String itemMaterial, int price, int stock)
```

**実装例：**

```java
ShopManager shopManager = new ShopManager(plugin);

boolean success = shopManager.createShop(
    UUID.fromString("550e8400-e29b-41d4-a716-446655440001"),
    "mc1",
    player.getUniqueId(),
    "DIAMOND",
    100,  // 価格
    50    // 在庫
);

if (success) {
    player.sendMessage("§a店舗を作成しました");
} else {
    player.sendMessage("§c店舗作成に失敗しました");
}
```

#### NBT対応版

```java
public boolean createShop(UUID npcUuid, String serverId, UUID ownerUUID, 
                         String itemMaterial, String itemNBT, int price, int stock)
```

**用途：** エンチャント付きアイテム等の販売

```java
String itemNBT = "{Enchantments:[{id:\"minecraft:sharpness\",lvl:5}]}";

shopManager.createShop(
    npcUuid,
    "mc1",
    ownerUUID,
    "DIAMOND_SWORD",
    itemNBT,
    5000,  // 価格
    10
);
```

**内部実装：**

```sql
INSERT INTO fje_shops 
  (npc_uuid, server_id, owner_uuid, item_material, item_nbt, price, stock)
VALUES 
  (?, ?, ?, ?, ?, ?, ?)
ON DUPLICATE KEY UPDATE 
  owner_uuid = ?, 
  item_material = ?, 
  item_nbt = ?, 
  price = ?, 
  stock = ?
```

---

### 3.2 ショップ情報取得

#### 単一ショップ取得

```java
public Shop getShop(UUID npcUuid, String serverId)
```

**実装例：**

```java
Shop shop = shopManager.getShop(
    UUID.fromString("550e8400-e29b-41d4-a716-446655440001"),
    "mc1"
);

if (shop != null) {
    System.out.println("価格: " + shop.getPrice());
    System.out.println("在庫: " + shop.getStock());
} else {
    System.out.println("店舗が見つかりません");
}
```

#### オーナーの全ショップ取得

```java
public List<Shop> getShopsByOwner(UUID ownerUUID, String serverId)
```

**実装例：**

```java
List<Shop> shops = shopManager.getShopsByOwner(
    player.getUniqueId(),
    "mc1"
);

shops.forEach(shop -> {
    System.out.println(shop.getDisplayInfo("¥"));
});

// 出力例:
// DIAMOND: ¥100 (在庫: 50個)
// EMERALD: ¥500 (在庫: 30個)
```

---

### 3.3 価格・在庫操作

#### 価格更新

```java
public boolean updateShopPrice(UUID npcUuid, String serverId, int newPrice)
```

```java
boolean success = shopManager.updateShopPrice(
    npcUuid,
    "mc1",
    150  // 新価格
);

if (success) {
    player.sendMessage("§a価格を ¥150 に更新しました");
}
```

**検証：**
```
- newPrice >= 0 のみ許可
```

#### 在庫設定

```java
public boolean updateShopStock(UUID npcUuid, String serverId, int newStock)
```

```java
shopManager.updateShopStock(npcUuid, "mc1", 100);  // 在庫を100に設定
```

#### 在庫追加

```java
public boolean addStock(UUID npcUuid, String serverId, int quantity)
```

```java
// 入荷：50個追加
shopManager.addStock(npcUuid, "mc1", 50);
```

**内部SQL：**
```sql
UPDATE fje_shops SET stock = stock + ? 
WHERE npc_uuid = ? AND server_id = ?
```

#### 在庫削減

```java
public boolean removeStock(UUID npcUuid, String serverId, int quantity)
```

```java
// 販売：10個削減（残高確認付き）
if (shopManager.removeStock(npcUuid, "mc1", 10)) {
    System.out.println("販売完了");
} else {
    System.out.println("在庫不足");
}
```

**内部SQL（在庫チェック付き）：**
```sql
UPDATE fje_shops 
SET stock = stock - ? 
WHERE npc_uuid = ? AND server_id = ? AND stock >= ?
```

**特徴：** 在庫が不足していても、UPDATE実行まで進めば安全。

---

### 3.4 ショップ削除

```java
public boolean deleteShop(UUID npcUuid, String serverId)
```

```java
if (shopManager.deleteShop(npcUuid, "mc1")) {
    player.sendMessage("§a店舗を削除しました");
}
```

---

### 3.5 存在確認・エンティティ検索

#### 存在確認

```java
public boolean shopExists(UUID npcUuid, String serverId)
```

#### エンティティから検索

```java
public Shop getShopByEntity(Entity entity, String serverId)
```

**実装例：**

```java
@EventHandler
public void onEntityInteract(PlayerInteractEntityEvent event) {
    Entity entity = event.getRightClicked();
    String serverId = plugin.getConfigManager().getServerId();
    
    Shop shop = shopManager.getShopByEntity(entity, serverId);
    if (shop == null) return;
    
    // ショップ処理を実行
}
```

---

## 4. コマンド統合（ShopCommand）

ShopCommand は ユーザーが `/shop` コマンドで操作するインターフェース。

### 4.1 コマンド登録

```java
// FJEconomy.java の onEnable() 内
ShopManager shopManager = new ShopManager(this);
ShopCommand shopCommand = new ShopCommand(this, shopManager);
getCommand("shop").setExecutor(shopCommand);
```

### 4.2 各サブコマンド実装

#### `/shop create <npc_uuid> <item> <price> [stock]`

```java
private boolean handleCreate(CommandSender sender, String[] args) {
    if (!(sender instanceof Player)) {
        sender.sendMessage("§cプレイヤーのみ実行可能");
        return false;
    }
    
    Player player = (Player) sender;
    UUID npcUuid = UUID.fromString(args[1]);
    String itemMaterial = args[2];
    int price = Integer.parseInt(args[3]);
    int stock = args.length > 4 ? Integer.parseInt(args[4]) : 
               plugin.getConfigManager().getDefaultStock();
    
    String serverId = plugin.getConfigManager().getServerId();
    
    if (shopManager.createShop(npcUuid, serverId, player.getUniqueId(), 
                              itemMaterial, price, stock)) {
        player.sendMessage(plugin.getConfigManager().getMessagePrefix() +
            "§a店舗を作成しました");
        return true;
    }
    
    return false;
}
```

#### `/shop setprice <npc_uuid> <price>`

```java
private boolean handleSetPrice(CommandSender sender, String[] args) {
    Player player = (Player) sender;
    UUID npcUuid = UUID.fromString(args[1]);
    int price = Integer.parseInt(args[2]);
    
    String serverId = plugin.getConfigManager().getServerId();
    Shop shop = shopManager.getShop(npcUuid, serverId);
    
    // オーナー確認
    if (!shop.getOwnerUUID().equals(player.getUniqueId())) {
        player.sendMessage("§cこの店舗を管理する権限がありません");
        return false;
    }
    
    if (shopManager.updateShopPrice(npcUuid, serverId, price)) {
        player.sendMessage("§a価格を ¥" + price + " に設定しました");
        return true;
    }
    
    return false;
}
```

---

## 5. 購入処理との統合

ショップシステムと経済システムの統合例。

### 5.1 イベントベースの購入処理

```java
@EventHandler
public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
    Entity entity = event.getRightClicked();
    Player player = event.getPlayer();
    String serverId = plugin.getConfigManager().getServerId();
    
    // ショップ情報を取得
    ShopManager.Shop shop = shopManager.getShopByEntity(entity, serverId);
    if (shop == null) return;
    
    event.setCancelled(true);  // デフォルト動作をキャンセル
    
    // 在庫確認
    if (!shop.hasStock(1)) {
        player.sendMessage("§c売り切れました");
        return;
    }
    
    // 購入処理（非同期）
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
        EconomyManager economy = new EconomyManager(plugin);
        boolean purchased = economy.processPurchase(
            player.getUniqueId(), player.getName(),
            shop.getOwnerUUID(), "ShopOwner",
            shop.getItemMaterial(),
            1,              // 個数
            shop.getPrice()
        );
        
        if (purchased) {
            shopManager.removeStock(shop.getNpcUuid(), serverId, 1);
            
            // UI更新（メインスレッド）
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage("§a購入しました");
                // アイテム付与等の処理
            });
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage("§c購入に失敗しました");
            });
        }
    });
}
```

---

## 6. データベーススキーマ

### fje_shops テーブル

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
    FOREIGN KEY (owner_uuid) REFERENCES fje_balances(uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### キーポイント

- **主キー:** `(npc_uuid, server_id)` の複合キー
  - 同じ NPC でも異なるサーバーなら別レコード
  
- **外部キー:** `owner_uuid` → `fje_balances(uuid)`
  - 店主は必ずプレイヤーアカウントを持つ
  
- **インデックス:**
  - `idx_owner`: プレイヤーの全店舗検索（高速化）
  - `idx_item`: アイテム別検索（Web分析用）

---

## 7. 設定ファイル（config.yml）

```yaml
shop:
  # デフォルト在庫数
  default_stock: 100
  
  # Web API 経由での価格同期（実装予定）
  sync_prices: true
  
  # 同期間隔（秒）
  sync_interval: 300
```

---

## 8. 権限設定

```yaml
permissions:
  fj.shop.create:
    description: ショップ作成・管理
    default: true
  
  fj.shop.delete:
    description: ショップ削除
    default: op
```

---

## 9. エラーハンドリング

### SQLエラー時の動作

```java
try {
    // DB操作
} catch (SQLException e) {
    plugin.getLogger().log(Level.WARNING, "Shop operation error", e);
    return false;  // コマンド送信者に失敗を通知
}
```

### 権限チェック

```java
if (!shop.getOwnerUUID().equals(player.getUniqueId())) {
    sender.sendMessage("§cこの店舗を管理する権限がありません");
    return false;
}
```

---

## 10. マイグレーション（旧版からの更新）

### SHOP_SYSTEM v1.0 → v1.0.7 の変更点

#### スキーマ変更

```diff
- npc_id INT PRIMARY KEY
+ npc_uuid UUID NOT NULL
+ (複合主キー: npc_uuid, server_id)
```

#### Java API 変更

```diff
- public Shop getShop(int npcId, String serverId)
+ public Shop getShop(UUID npcUuid, String serverId)

- shopManager.getShop(1, "mc1")  // old
+ shopManager.getShop(UUID.fromString("550e8400..."), "mc1")  // new
```

#### コマンド変更

```diff
- /shop create 1 DIAMOND 100
+ /shop create 550e8400-e29b-41d4-a716-446655440001 DIAMOND 100
```

---

## 11. パフォーマンス特性

### DB クエリ応答時間（予想）

| 操作 | 応答時間 |
|---|---|
| getShop() | < 10ms |
| createShop() | < 50ms |
| updateShopPrice() | < 30ms |
| removeStock() | < 20ms |
| getShopsByOwner() | < 50ms |

### インデックス最適化

```sql
-- 複合インデックス推奨
CREATE INDEX idx_owner_server ON fje_shops(owner_uuid, server_id);
```

---

## 12. 実装チェックリスト

```
☑ Shop クラス実装
☑ ShopManager 実装
☑ ShopCommand 実装
☑ fje_shops テーブル CREATE
☑ 権限設定（plugin.yml）
☑ コマンド登録（FJEconomy.java）
☑ 購入イベント統合（PlayerListener または別EventHandler）
☑ DB トランザクション保護
☑ 非同期処理（Bukkit.getScheduler）
☑ エラーハンドリング
☑ ユニットテスト
☑ ドキュメント更新
```

---

**作成・更新者:** りゅう  
**License:** © 2024 Clusters-Prj. All rights reserved.
