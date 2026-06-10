# FJ Economy - ビルド・デプロイメント仕様書

**バージョン:** 1.0.7  
**最終更新:** 2024-12-13  
**対象者:** 開発者・システム管理者・DevOps

---

## 1. 開発環境構築

### 1.1 前提条件

| 項目 | バージョン | 備考 |
|---|---|---|
| **Java** | 17 以上 | OpenJDK推奨、OracleJDKも可 |
| **Maven** | 3.9 以上 | ビルドツール |
| **Git** | 2.0 以上 | バージョン管理（オプション） |
| **IDE** | IntelliJ IDEA / Eclipse | 開発用統合環境 |
| **OS** | Linux / Windows / macOS | ネイティブコンパイル不要 |

### 1.2 Java インストール確認

```bash
# バージョン確認
java -version

# 出力例:
# openjdk version "17.0.2" 2022-01-18
# OpenJDK Runtime Environment (build 17.0.2+8-Ubuntu-0ubuntu0.22.04.1)

# JAVA_HOME設定
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$PATH:$JAVA_HOME/bin
```

### 1.3 Maven インストール

```bash
# MacOS（Homebrew）
brew install maven

# Ubuntu/Debian
sudo apt-get install maven

# 確認
mvn -version

# 出力例:
# Apache Maven 3.9.0 (...)
# Java version: 17.0.2, vendor: Oracle Corporation
```

### 1.4 IDE セットアップ（IntelliJ IDEA）

```
1. File → Open → FJEconomy フォルダを選択
2. Intellij が pom.xml を自動検出して依存関係をダウンロード
3. Maven → Reload Projects
4. src/main/java が「Sources」でマーク
5. 準備完了
```

---

## 2. Maven ビルド

### 2.1 基本的なビルドコマンド

```bash
# プロジェクトディレクトリに移動
cd FJEconomy

# 標準ビルド（テスト実行含む）
mvn clean package

# テストをスキップして高速ビルド
mvn clean package -DskipTests

# 特定フェーズのみ実行
mvn compile          # コンパイル
mvn test             # ユニットテスト
mvn verify           # 検証
mvn install          # ローカルリポジトリへインストール
mvn deploy           # リモートリポジトリへデプロイ
```

### 2.2 pom.xml 設定（重要）

りゅうさんの指示通り、`pom.xml` を基準として使用：

```xml
<!-- 常にこのpom.xmlを基準に使用 -->
<!-- 変更は version 番号のみ -->
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.clusters-prj</groupId>
    <artifactId>fj-economy</artifactId>
    <version>1.0.7</version>  <!-- ← このみ変更 -->
    
    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <paper.version>1.21-R0.1-SNAPSHOT</paper.version>
    </properties>
    
    <!-- Shade Plugin 設定 -->
    <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.0</version>
        <configuration>
            <!-- Shading（ドライバ関連のクラス名変更） -->
            <relocations>
                <relocation>
                    <pattern>com.zaxxer.hikari</pattern>
                    <shadedPattern>com.clustersprj.fjeconomy.libs.hikari</shadedPattern>
                </relocation>
                <relocation>
                    <pattern>org.mariadb.jdbc</pattern>
                    <shadedPattern>com.clustersprj.fjeconomy.libs.mariadb.jdbc</shadedPattern>
                </relocation>
                <relocation>
                    <pattern>org.slf4j</pattern>
                    <shadedPattern>com.clustersprj.fjeconomy.libs.slf4j</shadedPattern>
                </relocation>
            </relocations>
        </configuration>
    </plugin>
</project>
```

**重要:** Shade Plugin の `<relocation>` は、MariaDB JDBC ドライバのパス衝突を防ぐための必須設定です。

### 2.3 ビルド出力の確認

```bash
# ビルド成功後、以下のファイルが生成される
ls -lh target/

# 出力例:
# fj-economy-1.0.7.jar         ← 本番JAR（全クラス・依存関係含む）
# fj-economy-1.0.7-sources.jar ← ソースコード
```

**確認ポイント:**
```bash
# JAR の内容確認
jar tf target/fj-economy-1.0.7.jar | grep -i mariadb

# 出力例（Shading成功）:
# com/clustersprj/fjeconomy/libs/mariadb/jdbc/Driver.class
```

---

## 3. ビルドエラーとその対処

### 3.1 "cannot find symbol" エラー

```
[ERROR] cannot find symbol: class Material
```

**原因:** Paper API が classpath に含まれていない

**対処:**
```bash
# Maven キャッシュをクリア
rm -rf ~/.m2/repository/io/papermc

# 再度ダウンロード
mvn clean install -U
```

### 3.2 "package ... does not exist" エラー

```
[ERROR] package com.zaxxer.hikari does not exist
```

**原因:** 依存関係のダウンロード失敗

**対処:**
```bash
# pom.xml を確認（HikariCP 依存関係が記述されているか）
grep -A2 "HikariCP" pom.xml

# Maven リポジトリをリセット
mvn clean dependency:resolve

# 再ビルド
mvn clean package -DskipTests
```

### 3.3 "cannot access" エラー（リモートリポジトリ）

```
[ERROR] Cannot access https://repo.papermc.io/repository/maven-public/
```

**原因:** インターネット接続不可、またはリポジトリ一時的に停止

**対処:**
```bash
# オフラインモード（ローカルキャッシュのみ）
mvn clean package -o

# または接続を確認
curl -I https://repo.papermc.io/repository/maven-public/
```

---

## 4. デプロイメント

### 4.1 Paper サーバーへのコピー

```bash
# MC1 サーバーへコピー
cp target/fj-economy-1.0.7.jar /path/to/MC1/plugins/

# MC2 サーバーへコピー
cp target/fj-economy-1.0.7.jar /path/to/MC2/plugins/

# MC3 サーバーへコピー
cp target/fj-economy-1.0.7.jar /path/to/MC3/plugins/

# 確認
ls -lh /path/to/*/plugins/fj-economy-*.jar
```

### 4.2 設定ファイルの配置

初回起動時に自動生成される：

```
plugins/FJEconomy/
├── config.yml              (← 手動で編集)
└── logs/
    └── economy.log         (← 自動生成)
```

### 4.3 config.yml の設定

```yaml
# 例: MC1 メインサーバー
database:
  url: "jdbc:mariadb://10.2.1.27:3306/fjeconomy?characterEncoding=utf8mb4&serverTimezone=Asia/Tokyo"
  username: "fjeconomy"
  password: "your_secure_password_here"  # ← 変更必須
  pool_size: 10
  max_lifetime: 1800000

server:
  id: "mc1"
  name: "Main Server"

economy:
  tax_rate: 10.0
  starting_balance: 1000
  allow_negative: false

shop:
  default_stock: 100
```

**セキュリティ注意:**
- `password` は強力なパスワードに変更
- config.yml は `/etc/sudoers` 相当の権限で保護（644ではなく640）

```bash
chmod 640 /path/to/plugins/FJEconomy/config.yml
```

### 4.4 サーバー起動・テスト

```bash
# サーバーを起動
cd /path/to/server
./start.sh

# ログを監視
tail -f logs/latest.log

# 出力例（成功）:
# [10:15:30] [Server thread/INFO]: [FJEconomy] ===================================
# [10:15:30] [Server thread/INFO]: [FJEconomy] FJ Economy v1.0.7 を読み込み中...
# [10:15:31] [Server thread/INFO]: [FJEconomy] ✓ 設定ファイルを読み込みました
# [10:15:31] [Server thread/INFO]: [FJEconomy] ✓ データベースに接続しました
# [10:15:32] [Server thread/INFO]: [FJEconomy] ✓ テーブルを作成/確認しました
# [10:15:32] [Server thread/INFO]: [FJEconomy] ✓ コマンドを登録しました
# [10:15:32] [Server thread/INFO]: [FJEconomy] ✓ イベントリスナーを登録しました
# [10:15:32] [Server thread/INFO]: [FJEconomy] FJ Economy が有効になりました
```

### 4.5 デプロイメント確認テスト

```bash
# オンラインプレイヤーに対してコマンドを実行
/fj balance

# 出力例:
# [FJ Economy] 残高: ¥1000

# 管理者コマンドテスト
/fjeadmin give TestPlayer 50000
/fj pay TestPlayer 5000
/fj balance
```

---

## 5. マルチサーバーデプロイメント

### 5.1 同期設定

全サーバーが同じDB（10.2.1.27）を参照することで自動同期：

```yaml
# MC1 config.yml
database:
  url: "jdbc:mariadb://10.2.1.27:3306/fjeconomy"
server:
  id: "mc1"

# MC2 config.yml
database:
  url: "jdbc:mariadb://10.2.1.27:3306/fjeconomy"  # ← 同じDB
server:
  id: "mc2"  # ← サーバーID異なる

# MC3 config.yml
database:
  url: "jdbc:mariadb://10.2.1.27:3306/fjeconomy"  # ← 同じDB
server:
  id: "mc3"  # ← サーバーID異なる
```

### 5.2 デプロイメント順序

```
1. DB サーバー (10.2.1.27) 確認
   → MariaDB が起動しているか、fjeconomy DB が存在するか
   
2. MC1 (メイン) 起動
   → テーブル作成を担当
   
3. MC2, MC3 起動
   → 既存テーブルを使用
```

### 5.3 バージョンアップ時の注意

```bash
# 全サーバーを停止
# サーバー1
stop
# サーバー2, 3
stop

# 全て JAR を置き換え
cp target/fj-economy-1.0.8.jar /path/to/MC1/plugins/
cp target/fj-economy-1.0.8.jar /path/to/MC2/plugins/
cp target/fj-economy-1.0.8.jar /path/to/MC3/plugins/

# MC1 を最初に起動（マイグレーションがあれば実行）
./start.sh

# ログで成功を確認してから MC2, MC3 を起動
```

---

## 6. バージョン管理

### 6.1 バージョン番号体系

```
FJ Economy v1.0.7
               ↓
         メジャー.マイナー.パッチ

- v1.0.0: 初期リリース
- v1.0.1～v1.0.7: パッチ・バグ修正
- v1.1.0: 新機能追加（WebAPI実装等）
- v2.0.0: 大規模リデザイン
```

### 6.2 バージョンアップ手順

```bash
# 1. バージョン番号を変更
sed -i 's/<version>1.0.7<\/version>/<version>1.0.8<\/version>/g' pom.xml

# 2. ビルド
mvn clean package -DskipTests

# 3. Git タグを打つ（オプション）
git tag v1.0.8
git push origin v1.0.8

# 4. デプロイメント
cp target/fj-economy-1.0.8.jar /path/to/plugins/
```

---

## 7. トラブルシューティング

### 7.1 DB 接続エラー

```
[ERROR] Database connection error: ...
```

**診断:**
```bash
# DB への接続テスト
mysql -h 10.2.1.27 -u fjeconomy -p fjeconomy -e "SELECT 1"

# ファイアウォール確認（Proxmox内）
ping 10.2.1.27
telnet 10.2.1.27 3306
```

**対処:**
```yaml
# config.yml を確認
# ① ホスト・ポート
# ② ユーザー名・パスワード
# ③ データベース名
```

### 7.2 テーブル作成エラー

```
[ERROR] Table creation error: ...
```

**原因:** 権限不足、またはテーブルが既に存在

**対処:**
```sql
-- MariaDB でテーブルの存在確認
SHOW TABLES FROM fjeconomy;

-- テーブル削除（テスト環境のみ！）
DROP TABLE IF EXISTS fjeconomy.fje_balances;

-- サーバーを再起動（自動作成）
```

### 7.3 Shade Plugin エラー

```
[ERROR] Shade failed: sun.reflect.Reflection.getCallerClass
```

**原因:** JVM バージョン互換性

**対処:**
```xml
<!-- pom.xml の shade plugin を更新 -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.5.0</version>  <!-- ← 最新バージョンを使用 -->
</plugin>
```

### 7.4 クラスロード順序の問題

```
[ERROR] ClassNotFoundException: com.clustersprj.fjeconomy.libs.mariadb.jdbc.Driver
```

**原因:** Shade 後のクラスパスが正しくない

**対処:**
```java
// DatabaseManager.java で明示的にロード
try {
    Class.forName("com.clustersprj.fjeconomy.libs.mariadb.jdbc.Driver");
} catch (ClassNotFoundException e) {
    plugin.getLogger().severe("MariaDB JDBC Driver が見つかりません");
    throw e;
}
```

---

## 8. CI/CD パイプライン（オプション）

### 8.1 GitHub Actions 例

```yaml
# .github/workflows/build.yml
name: Build FJEconomy

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: 17
          distribution: 'openjdk'
      - name: Build with Maven
        run: mvn clean package -DskipTests
      - name: Upload artifact
        uses: actions/upload-artifact@v3
        with:
          name: fj-economy-${{ github.ref_name }}
          path: target/fj-economy-*.jar
```

---

## 9. チェックリスト

### ビルド前

```
☑ Java 17 以上がインストール済み
☑ Maven 3.9 以上がインストール済み
☑ JAVA_HOME が正しく設定
☑ pom.xml が正しい（version番号以外は変更無し）
☑ .gitignore に target/ が含まれている
```

### ビルド中

```
☑ mvn clean package を実行
☑ テスト実行（またはスキップ）
☑ ビルドが 成功した旨のメッセージ
☑ target/ に JAR ファイルが生成
☑ JAR サイズが 5～10MB 程度（依存関係含む）
```

### デプロイ後

```
☑ サーバーログに "[FJEconomy] FJ Economy が有効になりました" が表示
☑ /fj balance コマンドが正常に動作
☑ データベース接続が確立
☑ テーブルが作成されている
☑ プレイヤーがサーバーに参加したら自動でアカウント作成
```

---

## 10. リリースノート形式

```markdown
# FJ Economy v1.0.8 リリース

## 新機能
- Web API エンドポイント追加 (#123)
- ダッシュボード機能改善 (#124)

## バグ修正
- Shade Plugin の クラスパス問題 (#120)
- テーブル作成時の外部キー制約エラー (#121)

## 破壊的変更
- なし

## 移行手順
1. サーバーを停止
2. JAR を置き換え
3. サーバーを起動

## 動作確認済み環境
- Paper 1.21, Java 17
- MariaDB 8.0.32
- HikariCP 5.1.0

---

**ダウンロード:** [fj-economy-1.0.8.jar](...)
```

---

**作成・更新者:** りゅう  
**License:** © 2024 Clusters-Prj. All rights reserved.
