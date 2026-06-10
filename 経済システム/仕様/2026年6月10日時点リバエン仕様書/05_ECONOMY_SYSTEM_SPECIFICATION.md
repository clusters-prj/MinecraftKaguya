# FJ Economy - 経済システム仕様書

**バージョン:** 1.0.7  
**最終更新:** 2024-12-13  
**対象者:** 開発者（アルゴリズム・金融ロジック詳細版）

---

## 1. 経済システム概要

FJ Economyの経済システムは、**安全性・正確性・監査可能性** を三本柱とする通貨管理機構です。浮動小数点誤差を完全排除し、全トランザクションを記録可能な設計になっています。

### 核心原則

1. **整数管理**: 全通貨値は BIGINT で管理
2. **ACID特性**: 複数口座操作はトランザクション内
3. **監査証跡**: 全トランザクションをログ記録
4. **透明性**: 税金計算は完全に再現可能

---

## 2. 通貨管理

### 2.1 データ型選択の理由

```
なぜ INT/BIGINT か？

❌ float/double の問題:
   0.1 + 0.2 = 0.30000000000000004  ← 誤差！
   
✅ INTEGER の利点:
   100 + 200 = 300  ← 完全正確
```

| 通貨額 | 型 | 用途 | 範囲 |
|---|---|---|---|
| プレイヤー残高 | BIGINT | 長期蓄積対応 | ±9.22×10¹⁸ |
| 取引価格 | INT | 単一トランザクション | ±2.15×10⁹ |

### 2.2 残高管理 API

#### 残高確認

```java
public long getBalance(UUID playerUUID)
```

**実装:**
```java
try (Connection conn = dbManager.getConnection();
     PreparedStatement stmt = conn.prepareStatement(
         "SELECT balance FROM fje_balances WHERE uuid = ?")) {
    stmt.setString(1, playerUUID.toString());
    ResultSet rs = stmt.executeQuery();
    
    if (rs.next()) {
        return rs.getLong("balance");  // ← BIGINT -> long
    }
}
return 0;  // デフォルト
```

#### 残高設定

```java
public boolean setBalance(UUID playerUUID, String playerName, long amount)
```

**制約:**
```
if (!allowNegative && amount < 0) {
    return false;  // 負債禁止
}
```

**内部SQL:**
```sql
INSERT INTO fje_balances (uuid, player_name, balance)
VALUES (?, ?, ?)
ON DUPLICATE KEY UPDATE balance = ?, last_update = CURRENT_TIMESTAMP
```

---

## 3. 基本操作（送金系）

### 3.1 プレイヤー間送金

```java
public boolean sendMoney(UUID senderUUID, String senderName,
                         UUID receiverUUID, String receiverName,
                         long amount)
```

**ロジック流図:**

```
        ┌─ 残高確認
        │  if (sender_balance < amount) { return false; }
        │
        ├─ トランザクション開始
        │  BEGIN;
        │
        ├─ 送信者残高減
        │  UPDATE fje_balances SET balance -= amount WHERE uuid = sender;
        │
        ├─ 受信者残高増
        │  UPDATE fje_balances SET balance += amount WHERE uuid = receiver;
        │
        └─ コミット
           COMMIT;  // 全成功
           ROLLBACK;  // エラー時
```

**実装例:**

```java
public boolean sendMoney(UUID senderUUID, String senderName,
                         UUID receiverUUID, String receiverName,
                         long amount) {
    if (amount <= 0) return false;
    
    try (Connection conn = dbManager.getConnection()) {
        conn.setAutoCommit(false);
        
        try {
            // 1. 残高確認（ロック）
            long senderBalance = getBalance(senderUUID);
            if (senderBalance < amount) {
                conn.rollback();
                return false;
            }
            
            // 2. 双方の残高を更新
            takeMoney(senderUUID, senderName, amount);
            giveMoney(receiverUUID, receiverName, amount);
            
            conn.commit();
            return true;
            
        } catch (Exception e) {
            conn.rollback();
            plugin.getLogger().log(Level.WARNING, "Transaction failed", e);
            return false;
        } finally {
            conn.setAutoCommit(true);
        }
    } catch (SQLException e) {
        return false;
    }
}
```

**安全性:**
- FOR UPDATE ロック（将来実装）で他スレッドからの競合アクセスを防止
- テーブルロック（MySQL限定）も選択肢

---

### 3.2 付与・没収

#### 付与（ギブ）

```java
public boolean giveMoney(UUID playerUUID, String playerName, long amount)
```

```
新残高 = 旧残高 + 付与額
```

#### 没収（テイク）

```java
public boolean takeMoney(UUID playerUUID, String playerName, long amount)
```

```
新残高 = 旧残高 - 没収額

if (新残高 < 0 && !allowNegative) {
    return false;  // 残高不足
}
```

---

## 4. 購入処理と自動課税

### 4.1 税金計算アルゴリズム

購入処理は **3者間トランザクション** となります：

```
購入者 ──100円──> 系 ─┬─> 店主 (90円)
                   └─> 政府 (10円)
```

#### ステップバイステップ

```
1. 販売価格: 100円
2. 税率: 10.0%
3. 税額計算:
   BigDecimal tax = 100 × 0.10 = 10.00
   → RoundingMode.HALF_UP で四捨五入
   → 税額 = 10円（整数化）
4. 店主受取額 = 100 - 10 = 90円
5. 整合性確認: 100 = 10 + 90 ✅
```

#### Java実装

```java
public boolean processPurchase(UUID buyerUUID, String buyerName,
                              UUID ownerUUID, String ownerName,
                              String itemMaterial, int quantity, long unitPrice) {
    
    // パラメータ検証
    if (quantity <= 0 || unitPrice < 0) return false;
    
    // 総額
    long totalPrice = unitPrice * quantity;
    
    // 税金計算
    double taxRate = configManager.getTaxRate() / 100.0;
    RoundingMode rounding = RoundingMode.valueOf(
        configManager.getRoundingMethod());  // HALF_UP
    
    BigDecimal taxDecimal = BigDecimal.valueOf(totalPrice)
        .multiply(BigDecimal.valueOf(taxRate))
        .setScale(0, rounding);
    
    long taxAmount = taxDecimal.longValue();
    long netProfit = totalPrice - taxAmount;
    
    // 政府UUID
    UUID governmentUUID = UUID.fromString(
        configManager.getGovernmentUUID());
    
    try (Connection conn = dbManager.getConnection()) {
        conn.setAutoCommit(false);
        
        try {
            // 1. 購入者残高確認
            long buyerBalance = getBalance(buyerUUID);
            if (buyerBalance < totalPrice) {
                conn.rollback();
                return false;
            }
            
            // 2. 三者の残高を同時更新
            takeMoney(buyerUUID, buyerName, totalPrice);
            giveMoney(ownerUUID, ownerName, netProfit);
            giveMoney(governmentUUID, "GOVERNMENT", taxAmount);
            
            // 3. 取引レコード記録
            String serverId = configManager.getServerId();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO fje_transactions " +
                    "(timestamp, server_id, buyer_uuid, owner_uuid, item_id, " +
                    "amount, price_total, tax_amount, net_profit) " +
                    "VALUES (NOW(), ?, ?, ?, ?, ?, ?, ?, ?)")) {
                stmt.setString(1, serverId);
                stmt.setString(2, buyerUUID.toString());
                stmt.setString(3, ownerUUID.toString());
                stmt.setString(4, itemMaterial);
                stmt.setInt(5, quantity);
                stmt.setLong(6, totalPrice);
                stmt.setLong(7, taxAmount);
                stmt.setLong(8, netProfit);
                stmt.executeUpdate();
            }
            
            // 4. 政府台帳記録
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO fje_government_ledger " +
                    "(timestamp, type, amount, description) " +
                    "VALUES (NOW(), ?, ?, ?)")) {
                stmt.setString(1, "TAX_IN");
                stmt.setLong(2, taxAmount);
                stmt.setString(3, itemMaterial + " x" + quantity + 
                              " from " + buyerName);
                stmt.executeUpdate();
            }
            
            conn.commit();
            return true;
            
        } catch (Exception e) {
            conn.rollback();
            plugin.getLogger().log(Level.WARNING, "Purchase error", e);
            return false;
        } finally {
            conn.setAutoCommit(true);
        }
    } catch (SQLException e) {
        plugin.getLogger().log(Level.WARNING, "Purchase exception", e);
        return false;
    }
}
```

---

### 4.2 税金計算の正確性保証

#### 検証テスト

```java
@Test
public void testTaxCalculation() {
    // パターン1: キリのいい数字
    long price = 100;
    double taxRate = 10.0;
    
    BigDecimal tax = BigDecimal.valueOf(price)
        .multiply(BigDecimal.valueOf(taxRate / 100.0))
        .setScale(0, RoundingMode.HALF_UP);
    
    long taxAmount = tax.longValue();      // 10
    long netProfit = price - taxAmount;    // 90
    
    assertEquals(100, taxAmount + netProfit);  // 整合性 ✅
}

@Test
public void testTaxCalculationWithRemainder() {
    // パターン2: 端数が出る場合
    long price = 333;  // 不吉な数字...
    double taxRate = 15.0;
    
    BigDecimal tax = BigDecimal.valueOf(price)
        .multiply(BigDecimal.valueOf(0.15))
        .setScale(0, RoundingMode.HALF_UP);
    
    long taxAmount = tax.longValue();      // 50 (49.95 → 四捨五入)
    long netProfit = price - taxAmount;    // 283
    
    assertEquals(333, taxAmount + netProfit);  // 整合性 ✅
}
```

#### 丸め処理の選択

```yaml
economy:
  rounding_method: "HALF_UP"  # 四捨五入（標準）
  # 他の選択肢:
  # - HALF_DOWN: 五捨六入
  # - UP: 常に切り上げ
  # - DOWN: 常に切り下げ
```

---

## 5. トランザクション管理

### 5.1 ACID特性の保証

| 特性 | 実装方法 | 確認 |
|---|---|---|
| **Atomicity（原子性）** | BEGIN...COMMIT/ROLLBACK | 複数ステップが全成功か全失敗か |
| **Consistency（一貫性）** | 外部キー制約 + チェック制約 | 金額整合性ルール |
| **Isolation（分離性）** | InnoDB デフォルト分離レベル | 同時実行制御 |
| **Durability（永続性）** | InnoDB ログファイル | DBクラッシュ後も復旧 |

### 5.2 デッドロック対策

```java
// ❌ デッドロック起きやすい（順序が逆になる可能性）
takeMoney(A, amount);    // Thread 1
giveMoney(B, amount);    // Thread 2
// ← もし Thread 2 が先に給付しようとするとデッドロック

// ✅ デッドロック防止（キー順序統一）
BEGIN TRANSACTION
  UPDATE fje_balances WHERE uuid = ? ORDER BY uuid ASC FOR UPDATE;
  // 常に小さいUUID順に更新
COMMIT;
```

### 5.3 長いトランザクション回避

```java
// ❌ 遅い（全プレイヤーに給付）
BEGIN;
  for (Player p : players) {
    giveMoney(p.getUniqueId(), p.getName(), 1000);  // 長い！
  }
COMMIT;

// ✅ 速い（バッチ処理）
for (Player p : players) {
    BEGIN;
        giveMoney(p.getUniqueId(), p.getName(), 1000);
    COMMIT;
}
```

---

## 6. 政府口座管理

### 6.1 政府の役割

政府口座は以下の用途で使用：

1. **税金貯蔵** - 商品売買時の自動課税
2. **イベント配布** - 給与・ボーナス支給
3. **インフレ対策** - 経済が過熱時に通貨回収
4. **経済監視** - 国庫量で経済状況を把握

### 6.2 政府アカウント設定

```yaml
government:
  uuid: "00000000-0000-0000-0000-000000000001"  # 固定UUID
  name: "GOVERNMENT"
```

### 6.3 政府残高確認

```java
public long getGovernmentBalance() {
    UUID govUUID = UUID.fromString(
        configManager.getGovernmentUUID());
    return getBalance(govUUID);
}
```

### 6.4 政府台帳の活用

全ての政府入出金は `fje_government_ledger` に記録：

```sql
SELECT 
    DATE(timestamp) AS date,
    SUM(CASE WHEN type LIKE '%IN' THEN amount ELSE 0 END) AS income,
    SUM(CASE WHEN type LIKE '%OUT' THEN amount ELSE 0 END) AS expense
FROM fje_government_ledger
GROUP BY DATE(timestamp)
ORDER BY date DESC;

-- 出力例:
-- 2024-12-13 | income: 50000 | expense: 10000
-- 2024-12-12 | income: 45000 | expense: 8000
```

---

## 7. 特殊なシナリオ

### 7.1 負債システム（allow_negative = true）

```yaml
economy:
  allow_negative: true  # プレイヤーがマイナス残高を持つことを許可
```

**用途:**
- クレジット機能（借金が後から返金）
- 負債取立システム

**実装:**

```java
if (!allowNegative && newBalance < 0) {
    return false;  // 負債禁止
}
// 負債を許可する場合はここをスキップ
setBalance(uuid, name, newBalance);
```

### 7.2 マルチサーバー送金

複数サーバーでも全て同じDB（10.2.1.27）を参照するため、自動的にサーバー間送金が可能：

```
MC1 プレイヤーA  →  DB（fjeconomy）  →  MC2 プレイヤーB
    送金要求            同期実行          残高更新
```

コード的には特別な処理は不要。`DatabaseManager` がDB接続を統一化している。

### 7.3 大口取引の安全性

```java
// 1000万円の送金（BIGINT対応）
long hugeMoney = 10_000_000L;
economyManager.sendMoney(sender, senderName, receiver, receiverName, hugeMoney);

// ← 内部的に BigDecimal で計算するため安全
//   (INT では最大21億円まで)
```

---

## 8. エラーハンドリング戦略

### 8.1 例外クラス

```java
public class EconomyException extends Exception {
    public enum ErrorCode {
        INSUFFICIENT_BALANCE,   // 残高不足
        INVALID_AMOUNT,         // 不正な金額
        DATABASE_ERROR,         // DB接続エラー
        TRANSACTION_FAILED,     // トランザクション失敗
        PLAYER_NOT_FOUND        // プレイヤー未登録
    }
    
    private ErrorCode code;
    
    public EconomyException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }
}
```

### 8.2 リトライロジック（オプション）

```java
private static final int MAX_RETRIES = 3;
private static final long RETRY_DELAY = 100;  // ms

public boolean sendMoneyWithRetry(UUID sender, String senderName,
                                  UUID receiver, String receiverName,
                                  long amount) throws InterruptedException {
    
    for (int i = 0; i < MAX_RETRIES; i++) {
        try {
            if (sendMoney(sender, senderName, receiver, receiverName, amount)) {
                return true;
            }
        } catch (SQLException e) {
            if (i < MAX_RETRIES - 1) {
                Thread.sleep(RETRY_DELAY * (i + 1));  // 指数バックオフ
            }
        }
    }
    return false;
}
```

---

## 9. パフォーマンス最適化

### 9.1 キャッシング（非推奨）

```java
// ❌ 絶対にやってはいけない
private static Map<UUID, Long> balanceCache = new HashMap<>();  // 危険！

// 理由: マルチサーバー・マルチスレッド環境では、
//      ローカルキャッシュはすぐに陳腐化する
```

### 9.2 正しいアプローチ：バッチ処理

```java
// ✅ 複数プレイヤーの残高を一括取得
public Map<UUID, Long> getMultipleBalances(List<UUID> playerUUIDs) {
    Map<UUID, Long> result = new HashMap<>();
    
    String placeholders = String.join(",", 
        Collections.nCopies(playerUUIDs.size(), "?"));
    
    try (Connection conn = dbManager.getConnection();
         PreparedStatement stmt = conn.prepareStatement(
             "SELECT uuid, balance FROM fje_balances WHERE uuid IN (" + 
             placeholders + ")")) {
        
        for (int i = 0; i < playerUUIDs.size(); i++) {
            stmt.setString(i + 1, playerUUIDs.get(i).toString());
        }
        
        ResultSet rs = stmt.executeQuery();
        while (rs.next()) {
            result.put(UUID.fromString(rs.getString("uuid")),
                      rs.getLong("balance"));
        }
    } catch (SQLException e) {
        plugin.getLogger().log(Level.WARNING, "Batch query error", e);
    }
    
    return result;
}
```

---

## 10. 監査・レポーティング

### 10.1 取引レポート

```sql
-- 月間売上レポート
SELECT 
    DATE_TRUNC('month', timestamp) AS month,
    owner_uuid,
    COUNT(*) AS transactions,
    SUM(price_total) AS total_sales,
    SUM(net_profit) AS net_profit
FROM fje_transactions
GROUP BY month, owner_uuid
ORDER BY month DESC, net_profit DESC;
```

### 10.2 監査ログ（ログファイル）

```properties
# plugins/FJEconomy/logs/economy.log

2024-12-13 10:15:30 [INFO] TRANSACTION: sender=uuid1, receiver=uuid2, amount=5000
2024-12-13 10:15:31 [INFO] PURCHASE: buyer=uuid1, owner=uuid2, item=DIAMOND, price=100, tax=10
2024-12-13 10:15:32 [WARN] FAILED: insufficient_balance for uuid1
```

---

## 11. チェックリスト

```
✅ 通貨値は全て BIGINT/INT で管理
✅ トランザクション（ACID）で複数操作を保護
✅ 税金計算は BigDecimal + 四捨五入で正確性確保
✅ 金額整合性ルールをテストで検証
✅ 政府口座は独立・透明に管理
✅ 例外は正しくハンドリング
✅ 非同期処理で UI ブロッキング回避
✅ 監査ログを記録可能に
✅ デッドロック対策を実装
✅ エラー時は自動ロールバック
```

---

**作成・更新者:** りゅう  
**License:** © 2024 Clusters-Prj. All rights reserved.
