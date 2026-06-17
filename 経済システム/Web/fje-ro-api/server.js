// BigIntをJSONで出力できるようにシリアライズ方法を定義
BigInt.prototype.toJSON = function() {
    return this.toString();
};

const express = require('express');
const path = require('path');
const pool = require('./db');
const app = express();
const PORT = 3000;

app.use(express.json());

// 静的ファイルの配信設定（publicフォルダ内を公開）
app.use(express.static(path.join(__dirname, 'public')));

// CORSを許可（フロントエンドからAPIを叩けるようにする設定）
app.use((req, res, next) => {
    res.header("Access-Control-Allow-Origin", "*");
    res.header("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
    next();
});

// 政府口座のUUID定数
const GOV_UUID = '00000000-0000-0000-0000-000000000001';

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
        
        query += " ORDER BY s.price ASC"; // デフォルトで最安値順
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
        // 自分が購入者、または店主、または送金に関わった履歴
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
        
        // 1. 総通貨流通量 (政府口座以外)
        const totalAmountRow = await conn.query("SELECT SUM(balance) AS total FROM fje_balances WHERE uuid != ?", [GOV_UUID]);
        // 2. 政府口座残高
        const govBalanceRow = await conn.query("SELECT balance FROM fje_balances WHERE uuid = ?", [GOV_UUID]);
        // 3. 総税収額の集計
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
app.listen(PORT, () => {
    console.log(`FJ Economy RO-API Server running on port ${PORT}`);
});
