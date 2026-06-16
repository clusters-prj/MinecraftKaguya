// BigIntをJSONで出力できるようにシリアライズ方法を定義
BigInt.prototype.toJSON = function() {
    return this.toString();
};

const express = require('express');
const path = require('path');
const session = require('express-session');
const bcrypt = require('bcrypt');
const pool = require('./db');

const app = express();
const PORT = 3000;

// ミドルウェア設定
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// CORS設定
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
        secure: false,
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

// ==========================================
// 【新設】 データベース初期化関数（テーブル自動作成）
// ==========================================
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
// ユーザー認証関連 (web_users)
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

// ==========================================
// PayPayのコア機能（送金トランザクション）
// ==========================================

// ログイン中のユーザーから他プレイヤーへの送金
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

// ==========================================
// ログイン中のユーザー情報取得
// ==========================================
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

// ==========================================
// 既存のGET系API（ランキングやショップ情報など）
// ==========================================
app.get('/api/economy/balance/:uuid', async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT uuid, player_name, balance FROM fje_balances WHERE uuid = ?", [req.params.uuid]);
        if (rows.length === 0) return res.status(404).json({ error: "Player not found" });
        res.json(rows[0]);
    } catch (err) { res.status(500).json({ error: err.message }); } finally { if (conn) conn.release(); }
});

app.get('/api/economy/ranking', async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT player_name, balance FROM fje_balances WHERE uuid != ? ORDER BY balance DESC LIMIT 100", [GOV_UUID]);
        res.json(rows);
    } catch (err) { res.status(500).json({ error: err.message }); } finally { if (conn) conn.release(); }
});

// （必要に応じて元の server.js にあった残りの参照用APIをここにペーストしてください）

// 起動
app.listen(PORT, async () => {
    await initDatabase(); // サーバー起動時にテーブル作成を走らせる
    console.log(`FJ PayPay Web Server running on port ${PORT}`);
});
