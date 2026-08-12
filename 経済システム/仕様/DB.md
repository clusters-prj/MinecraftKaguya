# FJ Economy データベース仕様書 (v1.0)
本システムは、Proxmox上の複数サーバー（MC1, MC2, MC3）間で経済データをリアルタイム同期し、Webダッシュボード（Web-1）での可視化を実現するための共通基盤である。
## 1. 基本方針
 * **整合性:** 浮動小数点による計算誤差を防ぐため、プレイヤーの残高および取引価格はすべて **整数(INT)** で管理する。
 * **端数処理:** 税金計算等で生じる1円未満の端数は、プログラム側で四捨五入し、常に整数としてDBへ記録する。
 * **中央集約:** すべてのサーバーは 10.2.1.27 のデータベースを唯一の真実（Single Source of Truth）として参照する。
## 2. テーブル定義
### ① fje_balances（プレイヤー残高）
全サーバー共通の銀行口座。政府用口座もここに集約される。
| 列名 | 型 | 制約 | デフォルト | 備考 |
|---|---|---|---|---|
| uuid | VARCHAR(36) | **Primary Key** | - | プレイヤーまたは政府のID |
| player_name | VARCHAR(16) | NOT NULL | - | 識別用のプレイヤー名 |
| balance | INT | NOT NULL | 0 | 現在の所持金 |
| last_update | TIMESTAMP | NOT NULL | CURRENT... | 最終同期日時 |
### ② fje_shops（モブショップ設定）
NPC店主の配置と販売データ。
| 列名 | 型 | 制約 | デフォルト | 備考 |
|---|---|---|---|---|
| npc_id | INT | **Primary Key** | - | サーバー内NPC識別子 |
| server_id | VARCHAR(20) | **Primary Key** | - | mc1, mc2, mc3 等 |
| owner_uuid | VARCHAR(36) | NOT NULL | - | 店主(オーナー)のUUID |
| item_material | VARCHAR(64) | NOT NULL | - | アイテムID (DIAMOND等) |
| price | INT | NOT NULL | 0 | **販売価格（整数）** |
| stock | INT | NOT NULL | 0 | 在庫数 |
### ③ fje_transactions（取引ログ）
Webでの売上分析・政府監視用の「レシート」データ。
| 列名 | 型 | 制約 | デフォルト | 備考 |
|---|---|---|---|---|
| id | INT | **Primary Key** | AUTO_INC | 記録の一意なID |
| timestamp | DATETIME | NOT NULL | CURRENT... | 取引日時 |
| server_id | VARCHAR(20) | NOT NULL | - | 発生サーバー |
| buyer_uuid | VARCHAR(36) | NOT NULL | - | 購入者のUUID |
| owner_uuid | VARCHAR(36) | NOT NULL | - | 店主のUUID |
| item_id | VARCHAR(64) | NOT NULL | - | アイテムID |
| price_total | INT | NOT NULL | - | 支払総額 |
| tax_amount | INT | NOT NULL | - | 徴収された税金 |
| net_profit | INT | NOT NULL | - | 店主へ渡った純利益 |
### ④ fje_government_ledger（政府財政台帳）
国庫（政府口座）の入出金を追跡するための公的な記録。
| 列名 | 型 | 制約 | デフォルト | 備考 |
|---|---|---|---|---|
| id | INT | **Primary Key** | AUTO_INC | 記録ID |
| timestamp | DATETIME | NOT NULL | CURRENT... | 記録日時 |
| type | VARCHAR(20) | NOT NULL | - | TAX_IN, EVENT_OUT等 |
| amount | INT | NOT NULL | 0 | 動いた金額 |
| description | TEXT | - | NULL | 理由・用途の詳細 |
### ⑤ fje_build_rewards（建築量ポイントの定期集計）
CoreProtect のブロックログを一定間隔（既定3時間）で集計した結果と、付与したポイントの記録。
`(server_id, period_start, minecraft_uuid)` にユニーク制約があり、再起動後の再集計でも二重付与にならない。
| 列名 | 型 | 制約 | デフォルト | 備考 |
|---|---|---|---|---|
| id | INT | **Primary Key** | AUTO_INC | 記録ID |
| server_id | VARCHAR(20) | NOT NULL | - | 集計したサーバー |
| period_start | DATETIME | NOT NULL | - | 集計期間の始端 |
| period_end | DATETIME | NOT NULL | - | 集計期間の終端 |
| minecraft_uuid | UUID | NOT NULL | - | プレイヤーのUUID |
| player_name | VARCHAR(255) | NOT NULL | - | 集計時のプレイヤー名 |
| blocks_placed | INT | NOT NULL | 0 | 設置ブロック数（WorldEdit含む） |
| blocks_broken | INT | NOT NULL | 0 | 破壊ブロック数 |
| score | INT | NOT NULL | 0 | ポイント算出に使ったスコア |
| points_granted | BIGINT | NOT NULL | 0 | 実際に付与したポイント |
### ⑥ fje_build_queries（任意期間の集計リクエスト）
Web管理画面（`/build-admin`）が登録し、対象サーバーのプラグインがポーリングして処理する。**ポイントは付与しない**。
| 列名 | 型 | 制約 | デフォルト | 備考 |
|---|---|---|---|---|
| id | INT | **Primary Key** | AUTO_INC | リクエストID |
| server_id | VARCHAR(20) | NOT NULL | - | 集計を実行するサーバー |
| requested_by | INT | - | NULL | web_users.id |
| range_start | DATETIME | NOT NULL | - | 集計開始日時 |
| range_end | DATETIME | NOT NULL | - | 集計終了日時 |
| status | ENUM | NOT NULL | PENDING | PENDING/RUNNING/DONE/ERROR |
| error_message | TEXT | - | NULL | エラー内容・警告 |
| completed_at | TIMESTAMP | - | NULL | 完了日時 |
### ⑦ fje_build_query_results（集計リクエストの結果）
⑥ の結果。ランキング表示用で、`score` の降順に並べて使う。
| 列名 | 型 | 制約 | デフォルト | 備考 |
|---|---|---|---|---|
| query_id | INT | **Primary Key** | - | fje_build_queries.id |
| minecraft_uuid | UUID | **Primary Key** | - | プレイヤーのUUID |
| player_name | VARCHAR(255) | NOT NULL | - | プレイヤー名 |
| blocks_placed | INT | NOT NULL | 0 | 設置ブロック数 |
| blocks_broken | INT | NOT NULL | 0 | 破壊ブロック数 |
| score | INT | NOT NULL | 0 | スコア |
## 3. インフラ構成図
 1. **DB Server (10.2.1.27):** データの永続化。
 2. **MC Servers:**
   * メイン (10.2.1.28)
   * 外部1・2 (10.2.0.10)
 3. **Web Dashboard (10.2.1.5):** clusters-prj.com 経由での統計表示。
