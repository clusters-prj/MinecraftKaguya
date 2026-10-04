# FJ Economy データベース仕様書 (v2.0)

複数の Minecraft サーバー（mc1 / mc2 / mc3）と Web（fjew / fjeapi）が共有する中央 MariaDB の仕様。
スキーマの実体は次のコードで、本書はそれに追従する。食い違いがあればコードが正。

- Java 側: `プラグイン/FJEconomy/.../database/DatabaseManager.java` の `createTables()`
- Web 側: `Web/fjew/server.js` の `initDatabase()`、`Web/fjeapi/server.js` の `initDatabase()`

## 1. 基本方針

- **中央集約**: DB（MariaDB `10.2.1.27` / DB 名 `fjeconomy`）が唯一の真実。サーバー間・Web 間の連携はすべてこの DB 経由で、REST/RPC の直接呼び出しは存在しない。
- **金額は整数**: 残高・ポイント・賭け金は `BIGINT`、商品価格・在庫・取引金額は `INT`。`double` は使わない。Web 側（Node.js）は `BigInt` で扱う。
- **税の端数処理**: `BigDecimal` で計算し、`economy.rounding_method`（`HALF_UP` / `DOWN`）で整数化する。常に「支払総額 = 税額 + 受取額」が成り立つ。
- **トランザクション**: 複数の残高を動かす処理は `setAutoCommit(false)` + commit/rollback で囲む。
- **UUID**: Java 側の `UUID` 型列（MariaDB 10.7+ のネイティブ UUID 型）と、Web 側が作る `VARCHAR(36)` 列が混在する。値は同じ文字列表現なので JOIN・比較は可能。
- **テーブルの所有と作成**: 各テーブルは「Java が作る」「Web が作る」「両方が同じ定義で `CREATE TABLE IF NOT EXISTS` する（共有）」のいずれか。起動順序に依存させないため、**アプリをまたぐ外部キーは張らない**。

## 2. テーブル一覧

| テーブル | 所有 | 概要 |
|---|---|---|
| `fje_balances` | Java | 残高（プレイヤー・政府・法人口座すべて） |
| `fje_shops` | Java | モブショップ設定 |
| `fje_transactions` | Java | 取引ログ（全経路共通） |
| `fje_government_ledger` | Java | 政府収支台帳 |
| `fje_login_bonuses` | Java | ログインボーナス受取日時 |
| `link_codes` | 共有 | アカウント連携用ワンタイムコード |
| `fje_arena_events` | Java（`loadout` は両方が ALTER） | アリーナイベント |
| `fje_arena_participants` | Java | アリーナ参加者 |
| `fje_arena_bets` | Java | アリーナの優勝者予想ベット |
| `fje_build_rewards` | Java | 建築量ポイントの定期集計・付与記録 |
| `fje_build_queries` | Java | 任意期間の建築量集計リクエスト |
| `fje_build_query_results` | Java | 上記の結果 |
| `fje_active_skins` | 共有 | 使用中スキン |
| `fje_tool_catalog` | 共有（Web は SELECT のみ） | ツールアイテムのカタログ |
| `fje_api_keys` | fjew / fjeapi | 外部 API キー（SHA-256 ハッシュ） |
| `web_users` | Web | Web アカウント |
| `account_links` | Web | Web アカウントと Minecraft UUID の紐付け |
| `email_verifications` | Web | メール認証待ち登録 |
| `password_resets` | Web | パスワード再設定トークン |
| `corporate_accounts` | Web | 法人口座 |
| `marketplace_listings` | Web | マーケットプレイス出品 |
| `marketplace_nfts` | Web | 擬似 NFT 本体 |
| `marketplace_transfers` | Web | 譲渡履歴 |

ペットショップ連携の `cm_pet_catalog` / `cm_pet_claims` は CustomMobs プラグイン（別リポジトリ）が所有するテーブルで、fjew は SELECT/INSERT のみ行う。

## 3. Java 側テーブル

### 3.1 fje_balances（残高）
全サーバー共通の口座。政府口座（`government.uuid`、既定 `00000000-0000-0000-0000-000000000001`）と法人口座もここに入る。

| 列名 | 型 | 制約 | 既定値 | 備考 |
|---|---|---|---|---|
| uuid | UUID | PK | - | プレイヤー / 政府 / 法人口座の ID |
| player_name | VARCHAR(255) | NOT NULL | - | 送金先名の解決キー。法人口座は `あ0<名前>` 形式の内部名 |
| balance | BIGINT | NOT NULL | 0 | 現在の所持金 |
| last_update | TIMESTAMP | NOT NULL | CURRENT_TIMESTAMP ON UPDATE | 建築ポイントの対象選定にも使う |

インデックス: `idx_name (player_name)`

### 3.2 fje_shops（モブショップ）
| 列名 | 型 | 制約 | 備考 |
|---|---|---|---|
| npc_uuid | UUID | PK (npc_uuid, server_id) | 村人エンティティの UUID |
| server_id | VARCHAR(20) | PK | mc1 / mc2 / mc3 |
| owner_uuid | UUID | NOT NULL, FK→fje_balances | 店主 |
| item_material | VARCHAR(255) | NOT NULL | Bukkit の Material 名 |
| item_nbt | TEXT | NULL | 予約（現状 GUI では未使用） |
| price | INT | NOT NULL | 販売価格（税込） |
| stock | INT | NOT NULL | 在庫数 |

インデックス: `idx_owner (owner_uuid)`, `idx_item (item_material)`

### 3.3 fje_transactions（取引ログ）
ショップ購入だけでなく、Web 送金・マーケットプレイス・アリーナ・建築ポイント・外部 API 送金もここに記録する。`server_id` / `item_id` で経路を区別する。

| 列名 | 型 | 制約 | 備考 |
|---|---|---|---|
| id | INT | PK, AUTO_INCREMENT | |
| timestamp | DATETIME | NOT NULL, 既定 CURRENT_TIMESTAMP | |
| server_id | VARCHAR(20) | NOT NULL | 下表参照 |
| buyer_uuid | UUID | NOT NULL, FK→fje_balances | 支払側 |
| owner_uuid | UUID | NOT NULL, FK→fje_balances | 受取側（店主・出品者など） |
| item_id | VARCHAR(255) | NOT NULL | 下表参照 |
| amount | INT | NOT NULL, 既定 1 | 数量 |
| price_total | INT | NOT NULL | 支払総額 |
| tax_amount | INT | NOT NULL | 税額 |
| net_profit | INT | NOT NULL | 受取側の手取り |

インデックス: `timestamp` / `buyer_uuid` / `owner_uuid`

| server_id | item_id | 発生元 |
|---|---|---|
| mc1 / mc2 / mc3 | Material 名 | ショップ購入 |
| mc1 / mc2 / mc3 | `BUILD_REWARD` | 建築ポイント付与（buyer = 政府） |
| `WEB` | `WEB_PAYPAY` | Web 画面からの送金 |
| `WEB` | `MKT_<listing_id>` | マーケットプレイス購入 |
| `WEB` | `PET_<mob_type>` | ペットショップ購入（owner = 政府） |
| `API_BOT` | `BOT_TRANSFER` | 外部 API キーによる送金 |
| `ARENA` | `ARENA_PAYOUT` / `ARENA_REFUND` | アリーナのベット払戻・返金 |

### 3.4 fje_government_ledger（政府収支台帳）
| 列名 | 型 | 制約 | 備考 |
|---|---|---|---|
| id | INT | PK, AUTO_INCREMENT | |
| timestamp | DATETIME | NOT NULL, 既定 CURRENT_TIMESTAMP | |
| type | VARCHAR(20) | NOT NULL | 下表参照 |
| amount | BIGINT | NOT NULL | 動いた金額（正の値） |
| description | TEXT | NULL | 理由・対象 |

インデックス: `timestamp` / `type`

`type` の値:

| type | 意味 | 記録元 |
|---|---|---|
| `TAX_IN` | 税収（description は Java 側 `Sale: <アイテム>`、Web のマーケットプレイスは `<タイトル> (MKT_<id>) from <購入者>`） | ショップ / マーケットプレイス |
| `FUND_ADD` | 国庫への手動入金 | `/fjegovernment add` |
| `FUND_WITHDRAW` | 国庫からの手動出金 | `/fjegovernment withdraw` |
| `FUND_DISTRIBUTE` | プレイヤーへの分配（アリーナ優勝賞金も `distributeGovernmentFunds` 経由でこの型） | `/fjegovernment distribute` / アリーナ |
| `PET_SHOP_IN` | ペットショップ売上（全額が国庫収入） | fjew |
| `BUILD_REWARD` | 建築ポイント支出 | BuildRewardManager |

ログインボーナスは国庫から出ず、台帳にも記録されない（残高が単純に加算される）。

### 3.5 fje_login_bonuses
| 列名 | 型 | 制約 | 備考 |
|---|---|---|---|
| uuid | UUID | PK, FK→fje_balances ON DELETE CASCADE | |
| last_bonus_claim | TIMESTAMP | NOT NULL | 最終受取日時 |

### 3.6 アリーナ関連
`fje_arena_events`

| 列名 | 型 | 備考 |
|---|---|---|
| id | INT | PK, AUTO_INCREMENT |
| name | VARCHAR(100) | イベント名 |
| world | VARCHAR(64) | ワールド名 |
| center_x / center_y / center_z | DOUBLE | 中心座標 |
| radius | DOUBLE | 半径（XZ 平面。Y は無視） |
| prize_amount | BIGINT | 優勝賞金（国庫から拠出） |
| status | ENUM('ACTIVE','RESOLVED','CANCELLED') | 既定 ACTIVE |
| winner_uuid | UUID NULL | 優勝者 |
| created_at / resolved_at | TIMESTAMP | |
| loadout | TEXT NULL | 初期装備。1 行 1 アイテムの `MATERIAL:個数` |

インデックス: `idx_status (status)`

`fje_arena_participants`: PK `(event_id, minecraft_uuid)`、`player_name` VARCHAR(255)。FK `event_id`→events ON DELETE CASCADE、`minecraft_uuid`→fje_balances。

`fje_arena_bets`: `id`, `event_id`(FK CASCADE), `bettor_uuid`(FK→fje_balances), `predicted_uuid`(FK→fje_balances), `amount` BIGINT, `status` ENUM('PLACED','WON','LOST','REFUNDED') 既定 PLACED, `payout_amount` BIGINT NULL, `created_at`。

### 3.7 建築ポイント関連
`fje_build_rewards`（定期集計と付与記録）

| 列名 | 型 | 備考 |
|---|---|---|
| id | INT | PK, AUTO_INCREMENT |
| server_id | VARCHAR(20) | 集計したサーバー |
| period_start / period_end | DATETIME | 集計期間 |
| minecraft_uuid | UUID | FK→fje_balances |
| player_name | VARCHAR(255) | 集計時の名前 |
| blocks_placed / blocks_broken / score | INT | |
| points_granted | BIGINT | 実際に付与した額 |
| created_at | TIMESTAMP | |

制約: `UNIQUE uk_period_player (server_id, period_start, minecraft_uuid)`（再起動後の再集計でも二重付与にならない）、`INDEX idx_period`

`fje_build_queries`（Web 管理画面からの任意期間集計リクエスト。**ポイントは付与しない**）

| 列名 | 型 | 備考 |
|---|---|---|
| id | INT | PK, AUTO_INCREMENT |
| server_id | VARCHAR(20) | 集計を実行するサーバー |
| requested_by | INT NULL | `web_users.id`（FK なし） |
| range_start / range_end | DATETIME | |
| status | ENUM('PENDING','RUNNING','DONE','ERROR') | 既定 PENDING |
| error_message | TEXT NULL | エラー内容・警告 |
| created_at / completed_at | TIMESTAMP | |

インデックス: `(status, server_id)`

`fje_build_query_results`: PK `(query_id, minecraft_uuid)`、`player_name`, `blocks_placed`, `blocks_broken`, `score`。FK `query_id`→queries ON DELETE CASCADE。

## 4. 共有テーブル（Java・Web 両方が同一定義で作成）

| テーブル | 列 |
|---|---|
| `link_codes` | `code` VARCHAR(6) PK / `minecraft_uuid` VARCHAR(36) / `expires_at` TIMESTAMP / INDEX `idx_uuid` |
| `fje_active_skins` | `minecraft_uuid` VARCHAR(36) PK / `nft_id` INT / `updated_at` TIMESTAMP。`marketplace_nfts` への FK は張らない |
| `fje_tool_catalog` | `tool_code` VARCHAR(64) PK / `material` VARCHAR(64) / `display_name` VARCHAR(255) / `lore` TEXT / `custom_model_data` INT NULL / `description` VARCHAR(500) NULL / `updated_at`。Java が `tools/*.yml` から同期し、Web は SELECT のみ |
| `fje_arena_events.loadout` | Java・Web の両方が `ALTER TABLE ... ADD COLUMN` で保証 |

## 5. Web 側テーブル

### 5.1 アカウント系
| テーブル | 列 |
|---|---|
| `web_users` | `id` PK / `email` UNIQUE / `password_hash` / `discord_id` / `created_at` |
| `account_links` | PK `(web_user_id, minecraft_uuid)` / FK `web_user_id`→web_users ON DELETE CASCADE。1 つの Web アカウントに複数の Minecraft UUID（Java・Bedrock）を紐付けられる |
| `email_verifications` | `email` PK / `password_hash` / `token` / `expires_at` |
| `password_resets` | `email` PK / `token` / `expires_at` |
| `corporate_accounts` | `minecraft_uuid` PK / `owner_web_user_id` FK→web_users / `name` VARCHAR(100) / `created_at` / `deleted_at`（論理削除） |
| `fje_api_keys` | `id` / `minecraft_uuid` / `key_name` VARCHAR(100) / `key_hash` VARCHAR(64) UNIQUE（SHA-256）/ `key_hint` VARCHAR(8)（キー末尾 4 文字）/ `created_at` / `last_used_at`。fjew と fjeapi の双方が作成する |

法人口座は `fje_balances` に実体の行を持つ。`fje_transactions` 等から参照されるため物理削除せず、`corporate_accounts.deleted_at` を立てる論理削除にする。

### 5.2 マーケットプレイス系
`marketplace_listings`

| 列名 | 型 | 備考 |
|---|---|---|
| id | INT | PK, AUTO_INCREMENT |
| seller_uuid | VARCHAR(36) | 出品者の口座（本人または法人口座） |
| seller_web_user_id | INT | FK→web_users |
| item_type | ENUM('world_data','skin','media','blueprint','tool') | |
| title / description / price | VARCHAR(100) / TEXT / INT | |
| edition_type | ENUM('unique','limited','unlimited') | |
| max_editions / minted_count | INT | limited のとき `max_editions` を使う |
| file_path / file_original_name / file_size / file_mime | NULL 可 | tool 出品などファイルを持たない種別のため |
| preview_image_path | VARCHAR(255) NULL | |
| status | ENUM('active','paused','removed') | |
| created_at | TIMESTAMP | |
| blueprint_json | LONGTEXT | 設計図（blueprint）の JSON |
| tool_code | VARCHAR(64) | tool 出品の `fje_tool_catalog.tool_code` |
| skin_model | ENUM('classic','slim') | |
| skin_png_data | LONGBLOB | スキン PNG 本体 |
| skin_texture_value / skin_texture_signature | TEXT | Mojang 署名済みテクスチャ |
| skin_signed_at | TIMESTAMP | 署名日時 |

`marketplace_nfts`: `id`, `listing_id`(FK), `serial_number`, `owner_uuid`, `minted_at`。`UNIQUE (listing_id, serial_number)`。

`marketplace_transfers`: `id`, `nft_id`(FK), `from_uuid` NULL（新規 mint 時は NULL）, `to_uuid`, `price` INT, `transferred_at`。

## 6. 外部キーとデータ整合性の注意

- `fje_balances` を参照する FK が多いため、**残高行を持つ UUID を物理削除してはいけない**。口座の無効化は論理削除で行う。
- Java 側の FK（shops / transactions / login_bonuses / arena_* / build_rewards）は `fje_balances` が先に存在することを前提に、`ensurePlayerAccount` で口座を作ってから使う。
- Web 側とのテーブル定義を変えるときは、共有テーブル（§4）を Java と Web の両方で同時に更新する。
- `fje_transactions.price_total` 等は `INT`。巨額の送金（Web / API 送金は `BIGINT` 相当で受け付ける）を記録する場合に上限を超える可能性があるため、大きな金額を扱う運用が出てきたら型の見直しが必要。

## 7. インフラ

| VMID | 名前 | 役割 |
|---|---|---|
| 105 | Web-1 | fjew（pm2、ポート 3200） |
| 127 | MS-KV | Velocity（Geyser / Floodgate。FJESkinBridge の設置先） |
| 128 / 129 / 130 | MS-K1 / K2 / K3 | Paper サーバー（`server.id` = mc1 / mc2 / mc3） |
| - | DB | MariaDB `10.2.1.27:3306`（`fjeconomy`） |

fjeapi はポート 3000 の別プロセス。接続情報は各アプリの `.env` / `config.yml` に置き、リポジトリには含めない。
