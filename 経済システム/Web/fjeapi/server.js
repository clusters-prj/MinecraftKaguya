// BigIntをJSONで出力できるようにシリアライズ方法を定義
BigInt.prototype.toJSON = function() {
    return this.toString();
};

require('dotenv').config(); // 環境変数のロード
const express = require('express');
const path = require('path');
const crypto = require('crypto');
const rateLimit = require('express-rate-limit');
const pool = require('./db');
const app = express();
const PORT = 3000;

app.use(express.json());

// 静的ファイルの配信設定
app.use(express.static(path.join(__dirname, 'public')));

// CORSを許可
app.use((req, res, next) => {
    res.header("Access-Control-Allow-Origin", "*");
    res.header("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Authorization");
    res.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    if (req.method === "OPTIONS") {
        return res.sendStatus(204);
    }
    next();
});

// 政府口座のUUID定数
const GOV_UUID = '00000000-0000-0000-0000-000000000001';

// ==========================================
// 0. データベース初期化 ＆ レートリミット設定
// ==========================================

// APIキーを管理するテーブルの自動作成
async function initDatabase() {
    let conn;
    try {
        conn = await pool.getConnection();
        await conn.query(`
            CREATE TABLE IF NOT EXISTS fje_api_keys (
                id INT AUTO_INCREMENT PRIMARY KEY,
                minecraft_uuid VARCHAR(36) NOT NULL,
                key_name VARCHAR(100) NOT NULL,
                key_hash VARCHAR(64) NOT NULL UNIQUE,
                key_hint VARCHAR(8) NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                last_used_at TIMESTAMP NULL DEFAULT NULL
            )
        `);
        print("[Init] fje_api_keys table checked/created.");
    } catch (err) {
        console.error("Database initialization failed:", err);
    } finally {
        if (conn) conn.release();
    }
}

// Botや外部スクリプトの過剰アクセスを防ぐレートリミット設定（1分間に60回まで）
const apiLimiter = rateLimit({
    windowMs: 1 * 60 * 1000,
    max: 60,
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: "リクエスト回数の制限を超えました。しばらく待ってから再度お試しください。" }
});

// ==========================================
// 0.5. 認証用ミドルウェア
// ==========================================

// 外部プログラムからのAPIキー（Bearerトークン）を検証するミドルウェア
const requireApiKey = async (req, res, next) => {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return res.status(401).json({ error: "APIキーが必要です (Format: Bearer fjp_...)" });
    }

    const rawKey = authHeader.split(' ')[1];
    // SHA-256でハッシュ化して照合
    const keyHash = crypto.createHash('sha256').update(rawKey).digest('hex');

    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query(`
            SELECT minecraft_uuid, id FROM fje_api_keys WHERE key_hash = ?
        `, [keyHash]);

        if (rows.length === 0) {
            return res.status(401).json({ error: "無効なAPIキーです" });
        }

        // 最後に使用された日時を非同期で更新
        conn.query("UPDATE fje_api_keys SET last_used_at = NOW() WHERE id = ?", [rows[0].id]).catch(console.error);

        // 認証されたマイクラUUIDをリクエストに紐付ける
        req.minecraftUuid = rows[0].minecraft_uuid;
        next();
    } catch (err) {
        res.status(500).json({ error: "認証処理中にエラーが発生しました" });
    } finally {
        if (conn) conn.release();
    }
};

// ==========================================
// 1. プレイヤー・経済関連 (fje_balances)
// ==========================================

// 特定プレイヤーの残高・情報取得 (パブリック)
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

// 長者番付（ランキング）TOP100 - 政府口座は除外 (パブリック)
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

// 全ショップ一覧 (パブリック)
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

// 特定の店主(UUID)が持つショップ一覧 (パブリック)
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

// 全取引履歴 (パブリック)
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

// 特定プレイヤーに関わる取引履歴 (パブリック)
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

// 店主の売上統計 (パブリック)
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

// サーバー全体の経済指標サマリー (パブリック)
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

// 政府収支台帳の取得 (パブリック)
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


// ==========================================
// 5. 【新規】デベロッパー向け 自動送金・残高取得 API (v1)
// ==========================================

// [APIキー認証専用] キーに紐づくマイクラアカウントの残高を確認する
app.get('/api/v1/wallet/me', apiLimiter, requireApiKey, async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT uuid, player_name, balance FROM fje_balances WHERE uuid = ?", [req.minecraftUuid]);
        if (rows.length === 0) return res.status(404).json({ error: "マイクラデータが見つかりません" });
        res.json({ success: true, account: rows[0] });
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// [APIキー認証専用] キーに紐づくマイクラアカウント本人の取引履歴を取得する
app.get('/api/v1/wallet/transactions', apiLimiter, requireApiKey, async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query(
            "SELECT * FROM fje_transactions WHERE buyer_uuid = ? OR owner_uuid = ? ORDER BY timestamp DESC LIMIT 50",
            [req.minecraftUuid, req.minecraftUuid]
        );
        res.json(rows);
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// [APIキー認証専用] キーに紐づくマイクラアカウント本人の売上統計を取得する
app.get('/api/v1/wallet/analytics', apiLimiter, requireApiKey, async (req, res) => {
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
        `, [req.minecraftUuid]);
        res.json(rows);
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// [APIキー認証専用] 自動送金エンドポイント
app.post('/api/v1/wallet/send', apiLimiter, requireApiKey, async (req, res) => {
    const { to_player, amount } = req.body;
    
    if (!amount || isNaN(amount)) return res.status(400).json({ error: "金額を正しい数値形式で指定してください" });
    const parsedAmount = BigInt(amount);
    if (!to_player || parsedAmount <= 0n) return res.status(400).json({ error: "送金先、または金額が正しくありません" });

    const fromUuid = req.minecraftUuid; // APIキーから特定した送金元UUID

    let conn;
    try {
        conn = await pool.getConnection();
        await conn.beginTransaction();

        // 送金元の残高チェック
        const fromRows = await conn.query("SELECT balance FROM fje_balances WHERE uuid = ?", [fromUuid]);
        if (fromRows.length === 0) throw new Error("あなたのマイクラデータが見つかりません");
        
        const fromBalance = BigInt(fromRows[0].balance);
        if (fromBalance < parsedAmount) throw new Error("残高が不足しています");

        // 送金先チェック (プレイヤー名、または直接UUID指定でも通るようにする)
        const toRows = await conn.query("SELECT uuid, player_name FROM fje_balances WHERE player_name = ? OR uuid = ?", [to_player, to_player]);
        if (toRows.length === 0) throw new Error("送金先のプレイヤーが見つかりませんでした");
        
        const toUuid = toRows[0].uuid;
        const toPlayerName = toRows[0].player_name;
        if (fromUuid === toUuid) throw new Error("自分自身には送金できません");

        // 資金移動
        await conn.query("UPDATE fje_balances SET balance = balance - ? WHERE uuid = ?", [parsedAmount, fromUuid]);
        await conn.query("UPDATE fje_balances SET balance = balance + ? WHERE uuid = ?", [parsedAmount, toUuid]);

        // トランザクション履歴の挿入（item_id を 'BOT_TRANSFER' にして通常と区別可能に）
        await conn.query(
            "INSERT INTO fje_transactions (buyer_uuid, owner_uuid, price_total, server_id, item_id, net_profit, timestamp) VALUES (?, ?, ?, 'API_BOT', 'BOT_TRANSFER', ?, NOW())",
            [fromUuid, toUuid, parsedAmount, parsedAmount]
        );

        await conn.commit();
        res.json({ 
            success: true, 
            message: `${toPlayerName} さんに ${amount} 円送金しました`,
            tx: { from: fromUuid, to: toUuid, amount: amount.toString() }
        });
    } catch (err) {
        if (conn) await conn.rollback();
        res.status(400).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});


// ==========================================
// 6. 【新規】管理者用：APIキー管理エンドポイント
// ==========================================
// メインWebと切り離されているため、管理者が特定のマイクラUUIDに向けてキーを発行・削除するための口。
// 悪用を防ぐため、.env の「API_MANAGEMENT_SECRET」を知っている人だけが叩ける仕組み。

const requireManagementSecret = (req, res, next) => {
    const secret = req.headers['x-management-secret'];
    if (!process.env.API_MANAGEMENT_SECRET || secret !== process.env.API_MANAGEMENT_SECRET) {
        return res.status(403).json({ error: "アクセストークンが無効または設定されていません" });
    }
    next();
};

// キーの発行 (POST)
app.post('/api/internal/keys/generate', requireManagementSecret, async (req, res) => {
    const { minecraft_uuid, key_name } = req.body;
    if (!minecraft_uuid || !key_name) return res.status(400).json({ error: "minecraft_uuid と key_name が必要です" });

    const rawKey = 'fjp_' + crypto.randomBytes(24).toString('hex');
    const keyHash = crypto.createHash('sha256').update(rawKey).digest('hex');
    const keyHint = rawKey.slice(-4);

    let conn;
    try {
        conn = await pool.getConnection();
        await conn.query(`
            INSERT INTO fje_api_keys (minecraft_uuid, key_name, key_hash, key_hint) VALUES (?, ?, ?, ?)
        `, [minecraft_uuid, key_name, keyHash, keyHint]);

        res.json({
            success: true,
            api_key: rawKey,
            note: "このキーは一度しか表示されません。安全に記録してください。"
        });
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// キーの一覧確認 (GET)
app.get('/api/internal/keys', requireManagementSecret, async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT id, minecraft_uuid, key_name, key_hint, created_at, last_used_at FROM fje_api_keys");
        res.json(rows);
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// キーの削除 (DELETE)
app.delete('/api/internal/keys/:id', requireManagementSecret, async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        await conn.query("DELETE FROM fje_api_keys WHERE id = ?", [req.params.id]);
        res.json({ success: true, message: "APIキーを削除しました" });
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});


// 起動
app.listen(PORT, async () => {
    await initDatabase();
    console.log(`FJ Economy External-API Server running on port ${PORT}`);
});