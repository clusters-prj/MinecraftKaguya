# Web 仕様書（fjew / fjeapi）

2 つの Node.js (Express) アプリが共有 MariaDB（[DB.md](DB.md)）を直接読み書きする。Minecraft サーバーとの連携は DB 経由のみ。

| アプリ | 場所 | ポート | 役割 |
|---|---|---|---|
| fjew | `Web/fjew` | 3200 固定 | 一般向けダッシュボード・送金・マーケットプレイス・アリーナ・管理画面 |
| fjeapi | `Web/fjeapi` | 3000 固定 | 外部連携用 REST API（法人口座の API キー向け） |

起動は `npm install` → `node server.js`。接続情報は各アプリの `.env`（gitignore 済み）。本番の fjew は VM 105（Web-1）で pm2（プロセス名 `fjew`）。

## 1. fjew

### 1.1 環境変数

| 変数 | 必須 | 内容 |
|---|---|---|
| `SESSION_SECRET` | 必須 | セッション署名。購入証明書コードの HMAC 鍵も兼ねる |
| `APP_BASE_URL` | 必須 | 公開 URL（CORS の許可 Origin もここから決まる） |
| `SMTP_HOST` ほか `SMTP_PORT` / `SMTP_USER` / `SMTP_PASS` / `FROM_EMAIL` | `SMTP_HOST` が必須 | 認証・再設定メール |
| `ADMIN_EMAILS` | - | 管理者のメールアドレス（カンマ区切り）。管理系 API の `requireAdmin` はこれで判定 |
| `MARKETPLACE_TAX_RATE` / `MARKETPLACE_TAX_ROUNDING` | - | マーケットプレイス税率（%）と丸め（`HALF_UP` / `DOWN`）。**ゲーム内 config.yml の `economy.*` と必ず合わせる** |
| `MINESKIN_API_KEY` | - | スキン署名（mineskin.org）。無くても動くがレート制限が厳しい |
| `GTM_ID` | - | Google Tag Manager |
| DB 接続情報 | 必須 | `dotenv` で読む |

未設定だと起動時に落ちるのは `SESSION_SECRET` / `APP_BASE_URL` / `SMTP_HOST`。

### 1.2 共通仕様
- セッション: ファイルストア（`sessions/`）、TTL 24 時間、Cookie は `secure` / `httpOnly` / `sameSite=lax`。HTTPS 前提でリバースプロキシ（Cloudflare Tunnel 等）配下を想定（`trust proxy = 1`）。
- 認可: 要ログインは `requireAuth`、管理は `requireAdmin`（`ADMIN_EMAILS`）。
- レート制限: ログイン 15 分 5 回 / 登録 1 時間 3 回 / パスワード再設定申請 1 時間 3 回 / 再設定実行 30 分 5 回 / 連携 10 分 10 回 / メール認証 10 分 20 回 / 建築集計 10 分 20 回 / 購入証明書検証 10 分 30 回。
- 金額は `BigInt`（`parseAmount` は `^-?\d+$` のみ受理）。想定外のエラーは `sendServerError` で汎用メッセージに丸め、DB 情報を漏らさない（業務エラーの `throw new Error` は 400 でメッセージをそのまま返す）。
- 複数残高を動かす処理はトランザクション（`beginTransaction` / `commit` / `rollback`）。
- 操作可能な口座 = 連携済みの Minecraft アカウント + 自分が作った（削除されていない）法人口座（`getOwnedUuids`）。

### 1.3 画面

`/`（トップ）、ログイン、パスワード再設定、メイン（残高・送金・法人口座）、履歴、設定、`arena`、`arena-admin`、`build-admin`、`tool-admin`、`pet-shop`、`marketplace`、`marketplace-item`、`marketplace-sell`、`my-collection`、`marketplace-certificate`、`marketplace-preview`、`marketplace-verify`。

### 1.4 認証・アカウント連携

| API | 内容 |
|---|---|
| `POST /api/auth/register` | メール + パスワードで仮登録し、認証メールを送る（`email_verifications`） |
| `GET /api/auth/verify` | トークン検証で `web_users` を作成 |
| `POST /api/auth/login` / `logout` | ログイン / ログアウト |
| `POST /api/auth/forgot-password` / `reset-password` | 再設定（`password_resets`） |
| `POST /api/auth/link` | ゲーム内 `/fj link` の 6 桁コードを消費して `account_links` に紐付け |

連携のルール:
- コードは `link_codes` にあり、有効期限内であること。連携対象の UUID が `fje_balances` に存在すること。
- 1 つの Minecraft アカウントは 1 つの Web アカウントにしか紐付けられない。
- 1 つの Web アカウントに紐付けられるのは **Java 版 1 つ + 統合版 1 つまで**（UUID が `00000000-0000-0000-` で始まる、またはプレイヤー名が `.` で始まるものを Bedrock とみなす）。

### 1.5 ユーザー・法人口座・送金

| API | 内容 |
|---|---|
| `GET /api/user/me` | メール・Discord ID・連携アカウントと残高・法人口座 |
| `POST /api/corporate-accounts` | 法人口座の作成（名前 100 文字以内、**1 アカウント 5 個まで**） |
| `DELETE /api/corporate-accounts/:uuid` | 法人口座の削除（**残高 0 のときのみ**。API キーを削除し `deleted_at` を立てる論理削除） |
| `POST/GET /api/corporate-accounts/:uuid/api-keys`、`DELETE …/api-keys/:keyId` | 外部 API キーの発行・一覧・失効（自分の法人口座のみ。生キーは発行時に 1 度だけ表示） |
| `POST /api/wallet/send` | 送金（`from_uuid` は自分の口座のみ、`to_player` は名前または UUID、整数の正の額） |

法人口座の内部名は、実在プレイヤーの名前と衝突・なりすましを避けるため `あ<連番><表示名>`（`fje_balances.player_name`）になる。送金先の解決は `player_name` または UUID で行い、**削除済み法人口座は送金先に解決されない**。送金は `fje_transactions` に `server_id = WEB`、`item_id = WEB_PAYPAY` で記録（税なし）。

### 1.6 経済データ・管理

| API | 認可 | 内容 |
|---|---|---|
| `GET /api/economy/balance/:uuid` | 公開 | 残高 |
| `GET /api/economy/ranking` | 公開 | 長者番付 |
| `GET /api/shops`, `/api/shops/owner/:uuid` | 公開 | ショップ一覧 |
| `GET /api/transactions`, `/api/transactions/player/:uuid` | 公開 | 取引履歴 |
| `GET /api/wallet/history` | 要ログイン | 自分の取引履歴 |
| `GET /api/analytics/shop/:uuid` | 公開 | 店別の売上分析 |
| `GET /api/admin/economy/summary` | 管理者 | 通貨総量・国庫・税収の概況 |
| `GET /api/admin/government/ledger` | 管理者 | 政府台帳 |

アリーナ API は [アリーナ.md](アリーナ.md)、建築集計 API は [建築ポイント.md](建築ポイント.md) を参照。

### 1.7 マーケットプレイス

Web 上でデジタルデータ・ツールを売買する擬似 NFT 市場。購入ごとに `marketplace_nfts` に 1 行を発行（mint）する。

出品種別: `world_data` / `skin` / `media` / `blueprint`（設計図。3D プレビューあり）/ `tool`（[スキン・ツール.md](スキン・ツール.md)）。エディション: `unique`（1 点物）/ `limited`（限定数）/ `unlimited`。出品状態: `active` / `paused` / `removed`。

| API | 内容 |
|---|---|
| `GET /api/marketplace/listings`、`my-listings`、`my-collection`、`listings/:id`、`listings/:id/blueprint` | 一覧・自分の出品・所有コレクション・詳細・設計図 JSON |
| `POST /api/marketplace/listings` | 出品（スキンは 64×64 検証と署名、ツールは `tool_code` 指定） |
| `PATCH /api/marketplace/listings/:id/status` | 出品の公開 / 停止 / 削除 |
| `POST /api/marketplace/listings/:id/purchase` | 購入 |
| `POST /api/marketplace/nfts/:nftId/use` / `unuse` | スキンの使用 / 解除 |
| `GET /api/marketplace/nfts/:nftId/download` | 所有者のみのダウンロード・閲覧 |
| `GET …/nfts/:nftId/certificate`、`certificate/verify` | 購入証明書の発行・検証 |
| `GET /api/marketplace/tool-catalog`、`/api/admin/tool-catalog` | ツールカタログ |

購入処理（1 トランザクション）:
1. 購入元は自分の口座（未指定なら最初に連携した口座）。出品行を `FOR UPDATE` でロック。
2. 自分自身の出品は不可。署名の無いスキン、`tool_code` の無いツールは不可。`unique` は 1 回、`limited` は `max_editions` まで。
3. 税額を計算（`MARKETPLACE_TAX_RATE` / `MARKETPLACE_TAX_ROUNDING`）。出品者には `価格 − 税額`、政府口座に税額を加算し、台帳 `TAX_IN`（説明 `<タイトル> (MKT_<id>) from <購入者名>`）を記録。
4. `minted_count` を compare-and-set で +1（行ロックとの二重ガード）、NFT を発行（`serial_number = minted_count + 1`）、`marketplace_transfers` に `from_uuid = NULL` で記録、`fje_transactions` に `server_id = WEB`、`item_id = MKT_<listing_id>` で記録。

購入証明書: NFT ID と日付（JST）から `SESSION_SECRET` を鍵とした HMAC で 6 桁コードを毎回計算する。DB には保存せず、日付が変わるとコードが失効する（スクリーンショットの使い回し防止）。

### 1.8 ペットショップ

CustomMobs プラグイン（別リポジトリ）が所有する `cm_pet_catalog` / `cm_pet_claims` を使う。カタログは `GET /api/pets/catalog`（公開）、購入は `POST /api/pets/purchase`。購入額は**全額を国庫の収入**にして台帳 `PET_SHOP_IN` を記録し、`fje_transactions` は `item_id = PET_<mob_type>`（owner = 政府）。受け取りはゲーム内の `/cmob claim`。これらのテーブルは Java 側が作るので、Paper サーバーが一度も起動していない環境ではエラーになる。

## 2. fjeapi（外部 API）

法人口座の所有者が fjew で発行した API キーで、残高照会・取引履歴・送金を自動化するための API。

### 2.1 共通仕様
- キー形式: `fjp_` + 48 桁の hex。発行時にのみ表示され、DB には SHA-256 ハッシュと末尾 4 桁（`key_hint`）だけを保存する。認証は `Authorization: Bearer fjp_...`。成功ごとに `last_used_at` を更新する。
- レート制限: 1 分あたり 60 リクエスト（`apiLimiter`）。
- CORS は `*`（GET / POST / OPTIONS）。
- `.env` は fjew と別に用意し、管理用に `API_MANAGEMENT_SECRET` が必要。
- エラーは `sendServerError` で汎用メッセージに丸める。金額は `BigInt`（`parseAmount`）。

### 2.2 エンドポイント

| 区分 | API | 認証 |
|---|---|---|
| 公開 | `GET /api/economy/balance/:uuid`、`/api/economy/ranking`、`/api/shops`、`/api/shops/owner/:uuid`、`/api/transactions`、`/api/transactions/player/:uuid`、`/api/analytics/shop/:uuid`、`/api/admin/economy/summary`、`/api/admin/government/ledger` | なし |
| キー認証 | `GET /api/v1/wallet/me`（残高） | API キー |
| キー認証 | `GET /api/v1/wallet/transactions`（直近 50 件） | API キー |
| キー認証 | `GET /api/v1/wallet/analytics`（日別売上 30 日） | API キー |
| キー認証 | `POST /api/v1/wallet/send`（`to_player`, `amount`） | API キー |
| 管理 | `POST /api/internal/keys/generate`、`GET /api/internal/keys`、`DELETE /api/internal/keys/:id` | `X-Management-Secret` ヘッダ |

- 送金は `fje_transactions` に `server_id = API_BOT`、`item_id = BOT_TRANSFER` で記録する（税なし）。
- 管理シークレットの比較は SHA-256 に潰してから `timingSafeEqual` で行う（時間差攻撃対策）。

## 3. 既知の差異・注意点

- 公開 API（`/api/admin/economy/summary` と `/api/admin/government/ledger` を含む）の fjeapi 側は認証が無い。公開してよい情報かは運用で判断する。
- fjeapi の `/api/v1/wallet/send` は、fjew の `/api/wallet/send` と違って**削除済み法人口座を送金先から除外しない**。
- fjeapi の API キーの送金先は名前・UUID 指定のみで、自分宛て以外の制限は無い。
- アリーナのベットは「最初に連携した UUID」固定の口座から引き落とされる（[アリーナ.md](アリーナ.md)）。
- 税率の二重管理（fjew `.env` と Paper `config.yml`）。
