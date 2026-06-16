// BigIntをJSONで出力できるようにシリアライズ方法を定義
BigInt.prototype.toJSON = function() {
    return this.toString();
};

const express = require('express');
const path = require('path');
const session = require('express-session'); // 追加
const bcrypt = require('bcrypt');           // 追加
const pool = require('./db');
const app = express();
const PORT = 3200;

app.use(express.json());

// 静的ファイルの配信設定（publicフォルダ内を公開）
app.use(express.static(path.join(__dirname, 'public')));

// CORSを許可
app.use((req, res, next) => {
    res.header("Access-Control-Allow-Origin", "*");
    res.header("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
    next();
});

// セッションの設定
app.use(session({
    secret: 'fje-paypay-secret-key-change-this',
    resave: false,
    saveUninitialized: false,
    cookie: {
        secure: false, // ローカル環境(http)のためfalse
        maxAge: 1000 * 60 * 60 * 24 // 1日間
    }
}));

// 政府口座のUUID定数
const GOV_UUID = '00000000-0000-0000-0000-000000000001';

// ログインチェック用ミドルウェア
const requireAuth = (req, res, next) => {
    if (!req.session.webUserId) {
        return res.status(401).json({ error: "ログインが必要です" });
    }
    next();
};

// データベース初期化関数（テーブル自動作成）
async function initDatabase() {
    let conn;
    try {
        conn = await pool.getConnection();
        
        // 1. Webユーザーテーブル
        await conn.query(`
            CREATE TABLE IF NOT EXISTS web_users (
                id INT AUTO_INCREMENT PRIMARY KEY,
                email VARCHAR(255) NOT NULL UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                discord_id VARCHAR(64) DEFAULT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `);

        // 2. アカウント連携テーブル
        await conn.query(`
            CREATE TABLE IF NOT EXISTS account_links (
                web_user_id INT NOT NULL,
                minecraft_uuid VARCHAR(36) NOT NULL,
                PRIMARY KEY (web_user_id, minecraft_uuid),
                FOREIGN KEY (web_user_id) REFERENCES web_users(id) ON DELETE CASCADE
            )
        `);

        // 3. 連携コード一時保持テーブル
        await conn.query(`
            CREATE TABLE IF NOT EXISTS link_codes (
                code VARCHAR(6) PRIMARY KEY,
                minecraft_uuid VARCHAR(36) NOT NULL,
                expires_at TIMESTAMP NOT NULL
            )
        `);

        console.log("Database tables initialized successfully.");
    } catch (err) {
        console.error("Failed to initialize database tables:", err);
    } finally {
        if (conn) conn.release();
    }
}

// ==========================================
// 新設：ユーザー認証・アカウント連携関連
// ==========================================

// アカウント新規登録
app.post('/api/auth/register', async (req, res) => {
    const { email, password } = req.body;
    if (!email || !password) return res.status(400).json({ error: "メアドとパスワードを入力してください" });

    let conn;
    try {
        conn = await pool.getConnection();
        const existing = await conn.query("SELECT id FROM web_users WHERE email = ?", [email]);
        if (existing.length > 0) return res.status(400).json({ error: "このメールアドレスは既に登録されています" });

        const saltRounds = 10;
        const passwordHash = await bcrypt.hash(password, saltRounds);

        await conn.query("INSERT INTO web_users (email, password_hash) VALUES (?, ?)", [email, passwordHash]);
        res.json({ success: true, message: "アカウントを作成しました" });
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// ログイン
app.post('/api/auth/login', async (req, res) => {
    const { email, password } = req.body;
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT id, password_hash FROM web_users WHERE email = ?", [email]);
        if (rows.length === 0) return res.status(401).json({ error: "メアドまたはパスワードが間違っています" });

        const user = rows[0];
        const match = await bcrypt.compare(password, user.password_hash);
        if (!match) return res.status(401).json({ error: "メアドまたはパスワードが間違っています" });

        req.session.webUserId = user.id;
        res.json({ success: true, message: "ログインしました" });
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// ログアウト
app.post('/api/auth/logout', (req, res) => {
    req.session.destroy(err => {
        if (err) return res.status(500).json({ error: "ログアウトに失敗しました" });
        res.clearCookie('connect.sid');
        res.json({ success: true, message: "ログアウトしました" });
    });
});

// マイクラアカウント連携（ワンタイムコード検証）
app.post('/api/auth/link', requireAuth, async (req, res) => {
    const { code } = req.body;
    if (!code || code.length !== 6) {
        return res.status(400).json({ error: "6桁のコードを入力してください" });
    }

    let conn;
    try {
        conn = await pool.getConnection();
        await conn.beginTransaction();

        // 1. コードの有効性をチェック（時間のズレで弾かれる場合は「AND expires_at > NOW()」を削ると緩くなります）
        const rows = await conn.query(
            "SELECT minecraft_uuid FROM link_codes WHERE code = ? AND expires_at > NOW()",
            [code]
        );

        if (rows.length === 0) {
            throw new Error("コードが無効か、有効期限が切れています");
        }

        const minecraftUuid = rows[0].minecraft_uuid;

        // 2. 重複チェック（手動テストの残りがある場合はここで引っかかります）
        const existingLink = await conn.query("SELECT web_user_id FROM account_links WHERE minecraft_uuid = ?", [minecraftUuid]);
        if (existingLink.length > 0) {
            throw new Error("このマイクラアカウントは既に別のWebアカウントに連携されています");
        }

        // 3. 紐づけ情報を保存
        await conn.query(
            "INSERT INTO account_links (web_user_id, minecraft_uuid) VALUES (?, ?) ON DUPLICATE KEY UPDATE minecraft_uuid = ?",
            [req.session.webUserId, minecraftUuid, minecraftUuid]
        );

        // 4. 使い終わったコードを削除
        await conn.query("DELETE FROM link_codes WHERE code = ?", [code]);

        await conn.commit();
        res.json({ success: true, message: "マイクラアカウントとの連携が完了しました！" });

    } catch (err) {
        if (conn) await conn.rollback();
        res.status(400).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// ログイン中のユーザー情報取得
app.get('/api/user/me', requireAuth, async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query(`
            SELECT u.email, u.discord_id, b.player_name, b.balance, b.uuid 
            FROM web_users u
            LEFT JOIN account_links l ON u.id = l.web_user_id
            LEFT JOIN fje_balances b ON l.minecraft_uuid = b.uuid
            WHERE u.id = ?
        `, [req.session.webUserId]);
        
        res.json(rows[0]);
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// ログイン中のユーザーから他プレイヤーへの送金（PayPayコア機能）
app.post('/api/wallet/send', requireAuth, async (req, res) => {
    const { to_player, amount } = req.body;
    const parsedAmount = BigInt(amount);

    if (!to_player || parsedAmount <= 0n) {
        return res.status(400).json({ error: "送金先、または金額が正しくありません" });
    }

    let conn;
    try {
        conn = await pool.getConnection();
        await conn.beginTransaction();

        const links = await conn.query("SELECT minecraft_uuid FROM account_links WHERE web_user_id = ?", [req.session.webUserId]);
        if (links.length === 0) throw new Error("マイクラアカウントが連携されていません");
        const fromUuid = links[0].minecraft_uuid;

        const fromRows = await conn.query("SELECT balance FROM fje_balances WHERE uuid = ?", [fromUuid]);
        if (fromRows.length === 0) throw new Error("あなたのマイクラデータが見つかりません");
        const fromBalance = BigInt(fromRows[0].balance);

        if (fromBalance < parsedAmount) throw new Error("残高が不足しています");

        const toRows = await conn.query("SELECT uuid FROM fje_balances WHERE player_name = ? OR uuid = ?", [to_player, to_player]);
        if (toRows.length === 0) throw new Error("送金先のプレイヤーが見つかりませんでした");
        const toUuid = toRows[0].uuid;

        if (fromUuid === toUuid) throw new Error("自分自身には送金できません");

        await conn.query("UPDATE fje_balances SET balance = balance - ? WHERE uuid = ?", [parsedAmount, fromUuid]);
        await conn.query("UPDATE fje_balances SET balance = balance + ? WHERE uuid = ?", [parsedAmount, toUuid]);

        await conn.query(
            "INSERT INTO fje_transactions (buyer_uuid, owner_uuid, price_total, type, timestamp) VALUES (?, ?, ?, 'WEB_PAYPAY', NOW())",
            [fromUuid, toUuid, parsedAmount]
        );

        await conn.commit();
        res.json({ success: true, message: `${to_player} さんに ${amount} 円送金しました` });

    } catch (err) {
        if (conn) await conn.rollback();
        res.status(400).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// 【開発用デバッグAPI】プラグインの代わりにコードを強制発行する
app.post('/api/debug/generate-code', async (req, res) => {
    const { uuid } = req.body;
    if (!uuid) return res.status(400).json({ error: "マイクラのUUIDが必要です" });

    let conn;
    try {
        conn = await pool.getConnection();
        const code = Math.floor(100000 + Math.random() * 900000).toString();
        
        await conn.query(
            "INSERT INTO link_codes (code, minecraft_uuid, expires_at) VALUES (?, ?, DATE_ADD(NOW(), INTERVAL 10 MINUTE)) ON DUPLICATE KEY UPDATE minecraft_uuid = ?, expires_at = DATE_ADD(NOW(), INTERVAL 10 MINUTE)",
            [code, uuid, uuid]
        );
        
        res.json({ success: true, code, message: `コードを発行しました。` });
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});


// ==========================================
// 1. プレイヤー・経済関連 (fje_balances)
// ==========================================

// 特定プレイヤーの残高・情報取得
app.get('/api/economy/balance/:uuid', async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT uuid, player_name, balance FROM fje_balances WHERE uuid = ?", [req.params.uuid]);
        if (rows.length === 0) return res.status(404).json({ error: "Player not found" });
        res.json(rows[0]);
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// 長者番付（ランキング）TOP100 - 政府口座は除外
app.get('/api/economy/ranking', async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query(
            "SELECT player_name, balance FROM fje_balances WHERE uuid != ? ORDER BY balance DESC LIMIT 100", 
            [GOV_UUID]
        );
        res.json(rows);
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// ==========================================
// 2. ショップ関連 (fje_shops)
// ==========================================

// 全ショップ一覧（ショッピングモール画面・アイテム検索用）
app.get('/api/shops', async (req, res) => {
    let conn;
    try {
        const { item, server_id } = req.query;
        conn = await pool.getConnection();
        
        let query = `
            SELECT s.*, b.player_name AS owner_name 
            FROM fje_shops s
            JOIN fje_balances b ON s.owner_uuid = b.uuid
            WHERE 1=1
        `;
        const params = [];

        if (item) {
            query += " AND s.item_material = ?";
            params.push(item);
        }
        if (server_id) {
            query += " AND s.server_id = ?";
            params.push(server_id);
        }
        
        query += " ORDER BY s.price ASC"; 
        const rows = await conn.query(query, params);
        res.json(rows);
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// 特定の店主(UUID)が持つショップ一覧（店主用ダッシュボード）
app.get('/api/shops/owner/:uuid', async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT * FROM fje_shops WHERE owner_uuid = ?", [req.params.uuid]);
        res.json(rows);
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// ==========================================
// 3. トランザクション履歴 (fje_transactions)
// ==========================================

// 全取引履歴（管理者監査用・最新100件）
app.get('/api/transactions', async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT * FROM fje_transactions ORDER BY timestamp DESC LIMIT 100");
        res.json(rows);
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// 特定プレイヤーに関わる取引履歴（一般ユーザーマイページ用）
app.get('/api/transactions/player/:uuid', async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query(
            "SELECT * FROM fje_transactions WHERE buyer_uuid = ? OR owner_uuid = ? ORDER BY timestamp DESC LIMIT 50",
            [req.params.uuid, req.params.uuid]
        );
        res.json(rows);
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// 店主の売上統計（アナリティクス用グラフデータ）
app.get('/api/analytics/shop/:uuid', async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query(`
            SELECT 
                DATE_FORMAT(timestamp, '%Y-%m-%d') AS date,
                COUNT(*) AS sales_count,
                SUM(price_total) AS total_revenue,
                SUM(net_profit) AS total_profit
            FROM fje_transactions
            WHERE owner_uuid = ?
            GROUP BY date
            ORDER BY date DESC LIMIT 30
        `, [req.params.uuid]);
        res.json(rows);
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// ==========================================
// 4. マクロ経済・政府公文書 (fje_government_ledger)
// ==========================================

// サーバー全体の経済指標サマリー（管理者ダッシュボード用）
app.get('/api/admin/economy/summary', async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        
        const totalAmountRow = await conn.query("SELECT SUM(balance) AS total FROM fje_balances WHERE uuid != ?", [GOV_UUID]);
        const govBalanceRow = await conn.query("SELECT balance FROM fje_balances WHERE uuid = ?", [GOV_UUID]);
        const totalTaxRow = await conn.query("SELECT SUM(tax_amount) AS total_tax FROM fje_transactions");

        res.json({
            market_money_supply: totalAmountRow[0].total || 0,
            government_reserve: govBalanceRow[0]?.balance || 0,
            total_tax_collected: totalTaxRow[0].total_tax || 0
        });
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// 政府収支台帳の取得
app.get('/api/admin/government/ledger', async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT * FROM fje_government_ledger ORDER BY timestamp DESC LIMIT 100");
        res.json(rows);
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// 起動
app.listen(PORT, async () => {
    await initDatabase(); // 自動テーブル初期化を走らせる
    console.log(`FJ Economy Full-Featured API Server running on port ${PORT}`);
});
