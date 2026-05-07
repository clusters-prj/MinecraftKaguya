# FJ Economy - 店舗管理システム (v1.0.2)

## 更新内容

### DB スキーマ変更
- `uuid` カラム: `VARCHAR(36)` → `UUID` 型
- `balance`: `INT` → `BIGINT` へ拡張（大口取引対応）
- `item_material`: `VARCHAR(64)` → `VARCHAR(255)` に拡張
- `item_nbt` カラム追加: アイテムの NBT データ保存用
- `fje_transactions` に `amount` カラム追加: アイテム個数の記録

### 新機能: ShopManager

プレイヤーが NPC を店員として使用した経済システムに対応しました。

## 店舗管理コマンド

### 基本コマンド: `/shop`

#### 1. 店舗作成: `/shop create <NPC ID> <アイテム> <価格> [在庫]`

NPC を店員として登録し、商品を販売できるようにします。

**例**:
```
/shop create 1 DIAMOND 100        # NPC ID 1 で ダイヤモンドを 100円で販売（デフォルト在庫）
/shop create 2 EMERALD 500 50     # NPC ID 2 で エメラルドを 500円で販売（在庫: 50個）
/shop create 3 IRON_INGOT 30 100  # NPC ID 3 で 鉄インゴットを 30円で販売（在庫: 100個）
```

**権限**: なし（誰でも実行可）
**返却**: 
- 成功: `店舗を作成しました (NPC ID: ..., アイテム: ..., 価格: ...)`
- 失敗: `店舗作成に失敗しました`

---

#### 2. 店舗削除: `/shop delete <NPC ID>`

不要になった店舗を削除します。

**例**:
```
/shop delete 1   # NPC ID 1 の店舗を削除
```

**権限**: `fj.shop.delete` (op のみ)
**返却**:
- 成功: `店舗を削除しました (NPC ID: ...)`
- 失敗: `該当する店舗が見つかりません`

---

#### 3. 店舗一覧: `/shop list [プレイヤー名]`

自分または指定したプレイヤーの店舗一覧を表示します。

**例**:
```
/shop list              # 自分の店舗一覧
/shop list りゅう       # りゅう さんの店舗一覧
```

**権限**: なし（誰でも実行可）
**表示例**:
```
[FJ Economy] りゅう の店舗一覧:
  NPC ID: 1 - DIAMOND: ¥100 (在庫: 50個)
  NPC ID: 2 - EMERALD: ¥500 (在庫: 30個)
```

---

#### 4. 店舗情報: `/shop info <NPC ID>`

指定した NPC の店舗詳細情報を表示します。

**例**:
```
/shop info 1   # NPC ID 1 の情報を表示
```

**権限**: なし（誰でも実行可）
**表示例**:
```
[FJ Economy] 店舗情報:
  NPC ID: 1
  アイテム: DIAMOND
  価格: ¥100
  在庫: 50個
```

---

#### 5. 価格設定: `/shop setprice <NPC ID> <価格>`

自分の店舗の商品価格を変更します。

**例**:
```
/shop setprice 1 150   # NPC ID 1 の商品価格を 150円に変更
```

**権限**: なし（ただし自分の店舗のみ）
**返却**:
- 成功: `価格を ¥150 に設定しました`
- 失敗: `この店舗を管理する権限がありません`

---

#### 6. 在庫設定: `/shop setstock <NPC ID> <在庫>`

自分の店舗の在庫数を直接設定します。

**例**:
```
/shop setstock 1 100   # NPC ID 1 の在庫を 100個に設定
```

**権限**: なし（ただし自分の店舗のみ）
**返却**:
- 成功: `在庫を 100個に設定しました`
- 失敗: `この店舗を管理する権限がありません`

---

#### 7. 在庫追加: `/shop addstock <NPC ID> <個数>`

自分の店舗の在庫に商品を追加します。

**例**:
```
/shop addstock 1 50    # NPC ID 1 の在庫に 50個追加
```

**権限**: なし（ただし自分の店舗のみ）
**返却**:
- 成功: `在庫に 50個追加しました`
- 失敗: `この店舗を管理する権限がありません`

---

#### 8. 在庫削除: `/shop removestock <NPC ID> <個数>`

自分の店舗の在庫から商品を削除します。

**例**:
```
/shop removestock 1 20    # NPC ID 1 の在庫から 20個削除
```

**権限**: なし（ただし自分の店舗のみ）
**返却**:
- 成功: `在庫から 20個削除しました`
- 失敗: `この店舗を管理する権限がありません`

---

## ShopManager クラス（プログラマ向け）

`ShopManager` クラスを使用して、コード内から店舗を管理できます。

### メソッド一覧

```java
// 店舗作成
public boolean createShop(int npcId, String serverId, UUID ownerUUID, 
                         String itemMaterial, int price, int stock)
public boolean createShop(int npcId, String serverId, UUID ownerUUID, 
                         String itemMaterial, String itemNBT, int price, int stock)

// 店舗情報取得
public Shop getShop(int npcId, String serverId)
public List<Shop> getShopsByOwner(UUID ownerUUID, String serverId)

// 価格・在庫管理
public boolean updateShopPrice(int npcId, String serverId, int newPrice)
public boolean updateShopStock(int npcId, String serverId, int newStock)
public boolean addStock(int npcId, String serverId, int quantity)
public boolean removeStock(int npcId, String serverId, int quantity)

// 削除
public boolean deleteShop(int npcId, String serverId)

// 確認
public boolean shopExists(int npcId, String serverId)
public Shop getShopByEntity(Entity entity, String serverId)
```

### 使用例

```java
FJEconomy plugin = FJEconomy.getInstance();
ShopManager shopManager = new ShopManager(plugin);

// 店舗作成
UUID ownerUUID = player.getUniqueId();
shopManager.createShop(1, "mc1", ownerUUID, "DIAMOND", 100, 50);

// 店舗情報取得
ShopManager.Shop shop = shopManager.getShop(1, "mc1");
if (shop != null) {
    System.out.println("価格: " + shop.getPrice());
    System.out.println("在庫: " + shop.getStock());
}

// 在庫確認
if (shop.hasStock(10)) {
    System.out.println("在庫あり");
}

// 在庫削除
shopManager.removeStock(1, "mc1", 10);
```

---

## Shop クラス

店舗情報を表すデータクラスです。

```java
public static class Shop {
    public int getNpcId()           // NPC ID
    public String getServerId()     // サーバーID
    public UUID getOwnerUUID()      // オーナーUUID
    public String getItemMaterial() // アイテム名
    public String getItemNBT()      // NBT データ
    public int getPrice()           // 価格
    public int getStock()           // 在庫数
    
    public boolean hasStock(int quantity)  // 在庫確認
    public String getDisplayInfo(String currencySymbol)  // 表示用文字列
}
```

---

## 実装例: NPC 販売

イベントリスナーで NPC との会話を実装する例：

```java
@EventHandler
public void onEntityInteract(PlayerInteractEntityEvent event) {
    Entity entity = event.getRightClicked();
    Player player = event.getPlayer();
    String serverId = plugin.getConfigManager().getServerId();
    
    // Shop 取得
    ShopManager.Shop shop = shopManager.getShopByEntity(entity, serverId);
    if (shop == null) return;
    
    // 在庫確認
    if (!shop.hasStock(1)) {
        player.sendMessage("売り切れました");
        return;
    }
    
    // 購入処理（EconomyManager を使用）
    EconomyManager economy = new EconomyManager(plugin);
    boolean purchased = economy.processPurchase(
        player.getUniqueId(), player.getName(),
        shop.getOwnerUUID(), "ShopOwner",
        shop.getItemMaterial(), 1, shop.getPrice()
    );
    
    if (purchased) {
        shopManager.removeStock(shop.getNpcId(), serverId, 1);
        player.sendMessage("購入しました");
    } else {
        player.sendMessage("残高不足です");
    }
}
```

---

## DB テーブル構造

### fje_shops テーブル

| カラム | 型 | 説明 |
|-------|-----|------|
| npc_id | INT | NPC ID（主キー） |
| server_id | VARCHAR(20) | サーバーID（主キー） |
| owner_uuid | UUID | オーナーのUUID |
| item_material | VARCHAR(255) | アイテムID（例: DIAMOND） |
| item_nbt | TEXT | アイテムの NBT データ（オプション） |
| price | INT | 販売価格 |
| stock | INT | 在庫数 |

**主キー**: (npc_id, server_id)

---

## 注意点

- 各店舗は NPC ID とサーバーID の組み合わせで一意に識別されます
- 複数サーバーで同じ NPC ID を使用できます（server_id が異なれば）
- 店舗の所有権は UUID で管理されます
- 在庫が 0 になると、自動的に販売できなくなります（UI側で判定が必要）
- NBT データは Minecraft のエンチャント等の高度な設定用です（通常は不要）

---

## トラブルシューティング

### 店舗を作成できない

```
[ERROR] Shop creation error: ...
```

**原因**: DB 接続エラーまたは権限不足

**確認**:
1. DB 接続が正常か確認
2. `fj_shops` テーブルが存在するか確認
3. NPC ID がすでに存在していないか確認

### 在庫管理ができない

`shop setstock` コマンドで「権限がない」と表示される場合、その店舗の所有者でない可能性があります。

**確認**: `/shop info <NPC ID>` で オーナーを確認

---

## バージョン履歴

### v1.0.2 (2024-05-07)

- ShopManager クラスを実装
- ShopCommand クラスを実装
- DB スキーマを更新（UUID 型、BIGINT へ拡張）
- item_nbt カラムを追加
- fje_transactions の amount カラムを追加

### v1.0.1

- MariaDB ドライバの shade 対応

### v1.0.0

- 初期リリース
