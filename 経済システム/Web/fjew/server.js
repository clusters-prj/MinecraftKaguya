// ★ 一番最初に追加して環境変数をロードする
require('dotenv').config();

// BigIntをJSONで出力できるようにシリアライズ方法を定義
BigInt.prototype.toJSON = function() {
    return this.toString();
};

const express = require('express');
const path = require('path');
const session = require('express-session');
const FileStore = require('session-file-store')(session);
const bcrypt = require('bcrypt');
const crypto = require('crypto');
const nodemailer = require('nodemailer');
const pool = require('./db'); // この中で process.env が正常に使える
const app = express();
const PORT = 3200;

// ==========================================
// SMTPメール送信設定 (環境変数から柔軟に取得)
// ==========================================
// SMTP_HOST / SMTP_PORT / SMTP_USER / SMTP_PASS は .env で設定してください（特定のメールサービスに依存しません）
if (!process.env.SMTP_HOST) {
    console.warn("[Mail Warning] SMTP_HOST が .env に設定されていません。メール送信に失敗する可能性があります。");
}

const transporter = nodemailer.createTransport({
    host: process.env.SMTP_HOST,
    port: parseInt(process.env.SMTP_PORT || '587', 10),
    secure: process.env.SMTP_PORT === '465', // 465ポートの場合はtrue、587の場合はfalse
    auth: {
        user: process.env.SMTP_USER,
        pass: process.env.SMTP_PASS
    }
});

// 送信元アドレスを.envから取得、なければフォールバック
const FROM_EMAIL = process.env.FROM_EMAIL || `"ふじゅ〜ペイ" <no-reply@clusters-prj.com>`;

// ==========================================
// アプリのベースURL (.envから取得)
// ==========================================
// メール内のリンク生成にはリクエストのHostヘッダーを使わず、
// 信頼できる.envの値のみを使用する (Host Header Poisoning対策)
// 例: APP_BASE_URL=https://fjew.clusters-prj.com
if (!process.env.APP_BASE_URL) {
    console.warn("[Config Warning] APP_BASE_URL が .env に設定されていません。メール内のリンクが正しく生成できません。");
}
const APP_BASE_URL = (process.env.APP_BASE_URL || '').replace(/\/+$/, '');

// リバースプロキシ(Cloudflare Tunnel等)配下で動かす場合の設定
app.set('trust proxy', 1);

app.use(express.json());

// 静的ファイルの配信設定（publicフォルダ内を公開）
app.use(express.static(path.join(__dirname, 'public')));

// Webページ配信ルート
app.get('/', (req, res) => {
    const queryString = req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : '';
    res.redirect(`/login${queryString}`);
});
app.get('/login', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'login.html'));
});
app.get('/reset-password', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'reset-password.html'));
});
app.get('/main', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'main.html'));
});
app.get('/settings', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'settings.html'));
});

// CORSを許可
app.use((req, res, next) => {
    res.header("Access-Control-Allow-Origin", req.headers.origin || "*");
    res.header("Access-Control-Allow-Credentials", "true");
    res.header("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
    next();
});

// セッションの設定
app.use(session({
    store: new FileStore({
        path: path.join(__dirname, 'sessions'),
        ttl: 60 * 60 * 24,
        retries: 0,
        logFn: () => {}
    }),
    secret: process.env.SESSION_SECRET || 'fje-paypay-secret-key-fallback',
    resave: false,
    saveUninitialized: false,
    cookie: {
        secure: false,
        maxAge: 1000 * 60 * 60 * 24
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

        // 4. メール認証リンク一時保存テーブル
        await conn.query(`
            CREATE TABLE IF NOT EXISTS email_verifications (
                email VARCHAR(255) PRIMARY KEY,
                password_hash VARCHAR(255) NOT NULL,
                token VARCHAR(255) NOT NULL,
                expires_at TIMESTAMP NOT NULL
            )
        `);

        // 5. パスワードリセット一時保存テーブル
        await conn.query(`
            CREATE TABLE IF NOT EXISTS password_resets (
                email VARCHAR(255) PRIMARY KEY,
                token VARCHAR(255) NOT NULL,
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
// ユーザー認証・アカウント連携関連
// ==========================================

// アカウント仮登録 ＆ 認証メール送信
app.post('/api/auth/register', async (req, res) => {
    const { email, password } = req.body;
    if (!email || !password) return res.status(400).json({ error: "メアドとパスワードを入力してください" });

    let conn;
    try {
        conn = await pool.getConnection();
        
        // すでに本登録が完了しているかチェック
        const existing = await conn.query("SELECT id FROM web_users WHERE email = ?", [email]);
        if (existing.length > 0) return res.status(400).json({ error: "このメールアドレスは既に登録されています" });

        const saltRounds = 10;
        const passwordHash = await bcrypt.hash(password, saltRounds);

        // 安全なランダムトークンを生成
        const token = crypto.randomBytes(32).toString('hex');

        // 一時テーブルに保存
        await conn.query(`
            INSERT INTO email_verifications (email, password_hash, token, expires_at) 
            VALUES (?, ?, ?, DATE_ADD(NOW(), INTERVAL 30 MINUTE))
            ON DUPLICATE KEY UPDATE password_hash = ?, token = ?, expires_at = DATE_ADD(NOW(), INTERVAL 30 MINUTE)
        `, [email, passwordHash, token, passwordHash, token]);

        // URL構築（Hostヘッダーではなく.envの信頼済みベースURLを使用）
        const verifyUrl = `${APP_BASE_URL}/api/auth/verify?token=${token}`;

        // 認証メールの送信
        const mailOptions = {
            from: FROM_EMAIL,
            to: email,
            subject: '【ふじゅ〜ペイ】新規登録アカウントの有効化',
            text: `ふじゅ〜ペイをご利用いただきありがとうございます。\n\n以下のリンクをクリックして、アカウント登録を完了させてください。\n\n▼ 登録を完了する\n${verifyUrl}\n\n※有効期限: 30分\n※このメールに心当たりがない場合は、破棄してください。`
        };

        const info = await transporter.sendMail(mailOptions);
        
        console.log(`[Mail Success] メール送信完了しました！`);
        console.log(`  - To: ${email}`);
        console.log(`  - Message-ID: ${info.messageId}`);
        console.log(`  - Response: ${info.response}`);

        res.json({ success: true, message: "認証用メールを送信しました。メール内のリンクをクリックしてください。" });
    } catch (err) {
        console.error("[Mail Error] ユーザー登録・メール送信プロセスでエラーが発生しました:", err);
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// メール認証リンクの検証 ＆ 本登録（GETリクエスト） ★デバッグログ追加
app.get('/api/auth/verify', async (req, res) => {
    const { token } = req.query;
    if (!token) {
        console.log("[Verify Debug] トークンなしの不正アクセスを検知しました");
        return res.status(400).send('<h1>無効なリクエストです</h1><p>トークンが存在しません。</p>');
    }

    // フロント側で分岐できるようにエラーコードのみをクエリで渡す（メッセージ本文はURLに乗せない）
    const redirectWithError = (code) => {
        res.redirect(`/login?verify_error_code=${encodeURIComponent(code)}`);
    };

    let conn;
    try {
        conn = await pool.getConnection();
        await conn.beginTransaction();

        // トークンが一致し、期限内の一時登録情報を取得
        const rows = await conn.query(
            "SELECT email, password_hash FROM email_verifications WHERE token = ? AND expires_at > NOW()",
            [token]
        );

        console.log("[Verify Debug] email_verifications 検索結果:", rows);

        if (rows.length === 0) {
            const err = new Error("リンクの有効期限が切れているか、すでに使用されています。");
            err.code = "TOKEN_EXPIRED_OR_USED";
            throw err;
        }

        const { email, password_hash } = rows[0];
        console.log("[Verify Debug] 取り出した本登録情報:", { email, password_hash });

        // 本登録（web_users）に挿入
        const insertRes = await conn.query(
            "INSERT INTO web_users (email, password_hash) VALUES (?, ?)",
            [email, password_hash]
        );
        console.log("[Verify Debug] web_users へのインサート結果:", insertRes);

        // 一時テーブルから削除
        const deleteRes = await conn.query("DELETE FROM email_verifications WHERE email = ?", [email]);
        console.log("[Verify Debug] 一時テーブルからの削除結果:", deleteRes);

        await conn.commit();
        console.log("[Verify Debug] 本登録コミット完了！ email:", email);

        // 認証完了フラグをURLパラメータに付けて、ログインページへリダイレクト
        res.redirect('/login?verified=true');
    } catch (err) {
        if (conn) await conn.rollback();
        console.error("[Verify Debug] 認証処理中にエラーが発生しロールバックしました:", err);
        redirectWithError(err.code || "VERIFY_FAILED");
    } finally {
        if (conn) conn.release();
    }
});

// ログイン ★デバッグログ追加
app.post('/api/auth/login', async (req, res) => {
    const { email, password } = req.body;
    console.log("[Login Debug] ログイン試行:", { email });
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT id, password_hash FROM web_users WHERE email = ?", [email]);
        
        console.log("[Login Debug] web_users 検索結果:", rows);

        if (rows.length === 0) {
            console.log("[Login Debug] ユーザーが見つかりません。 email:", email);
            return res.status(401).json({ error: "メアドまたはパスワードが間違っています" });
        }

        const user = rows[0];
        console.log("[Login Debug] DBから取得したレコード:", { id: user.id, has_hash: !!user.password_hash });

        // パスワード比較
        const match = await bcrypt.compare(password, user.password_hash);
        console.log("[Login Debug] bcrypt 比較結果 (match):", match);

        if (!match) {
            console.log("[Login Debug] パスワードが一致しませんでした。");
            return res.status(401).json({ error: "メアドまたはパスワードが間違っています" });
        }

        req.session.webUserId = user.id;
        console.log("[Login Debug] ログイン成功。セッションを確立しました。 WebUserID:", user.id);
        res.json({ success: true, message: "ログインしました" });
    } catch (err) {
        console.error("[Login Debug] ログイン処理中に例外エラーが発生しました:", err);
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

// パスワードリセット申請（メール送信）
app.post('/api/auth/forgot-password', async (req, res) => {
    const { email } = req.body;
    if (!email) return res.status(400).json({ error: "メールアドレスを入力してください" });

    let conn;
    try {
        conn = await pool.getConnection();

        // ユーザーが実在するか確認（存在有無を外部に漏らさないよう、常に同じレスポンスを返す）
        const existing = await conn.query("SELECT id FROM web_users WHERE email = ?", [email]);

        if (existing.length > 0) {
            const token = crypto.randomBytes(32).toString('hex');

            await conn.query(`
                INSERT INTO password_resets (email, token, expires_at)
                VALUES (?, ?, DATE_ADD(NOW(), INTERVAL 30 MINUTE))
                ON DUPLICATE KEY UPDATE token = ?, expires_at = DATE_ADD(NOW(), INTERVAL 30 MINUTE)
            `, [email, token, token]);

            // Hostヘッダーではなく.envの信頼済みベースURLを使用（Host Header Poisoning対策）
            const resetUrl = `${APP_BASE_URL}/reset-password?token=${token}`;

            const mailOptions = {
                from: FROM_EMAIL,
                to: email,
                subject: '【ふじゅ〜ペイ】パスワード再設定のご案内',
                text: `パスワード再設定のリクエストを受け付けました。\n\n以下のリンクをクリックして、新しいパスワードを設定してください。\n\n▼ パスワードを再設定する\n${resetUrl}\n\n※有効期限: 30分\n※このメールに心当たりがない場合は、破棄してください。`
            };

            try {
                const info = await transporter.sendMail(mailOptions);
                console.log(`[Mail Success] パスワードリセットメール送信完了: ${email} (${info.messageId})`);
            } catch (mailErr) {
                console.error("[Mail Error] パスワードリセットメール送信に失敗しました:", mailErr);
            }
        } else {
            console.log(`[Reset Debug] 未登録メールへのリセット申請: ${email}`);
        }

        // アカウントの存在有無に関わらず同じメッセージを返す（メールアドレス列挙対策）
        res.json({ success: true, message: "パスワード再設定用のメールを送信しました。メールをご確認ください。" });
    } catch (err) {
        console.error("[Reset Debug] パスワードリセット申請処理でエラー:", err);
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// パスワードリセット実行（トークン検証 ＆ 更新）
app.post('/api/auth/reset-password', async (req, res) => {
    const { token, password } = req.body;
    if (!token || !password) return res.status(400).json({ error: "トークンと新しいパスワードを入力してください" });
    if (password.length < 8) return res.status(400).json({ error: "パスワードは8文字以上で入力してください" });

    let conn;
    try {
        conn = await pool.getConnection();
        await conn.beginTransaction();

        const rows = await conn.query(
            "SELECT email FROM password_resets WHERE token = ? AND expires_at > NOW()",
            [token]
        );

        if (rows.length === 0) {
            throw new Error("リンクの有効期限が切れているか、無効なリンクです。もう一度お試しください。");
        }

        const { email } = rows[0];
        const saltRounds = 10;
        const passwordHash = await bcrypt.hash(password, saltRounds);

        await conn.query("UPDATE web_users SET password_hash = ? WHERE email = ?", [passwordHash, email]);
        await conn.query("DELETE FROM password_resets WHERE email = ?", [email]);

        await conn.commit();
        console.log(`[Reset Debug] パスワード再設定完了: ${email}`);

        res.json({ success: true, message: "パスワードを再設定しました。新しいパスワードでログインしてください。" });
    } catch (err) {
        if (conn) await conn.rollback();
        res.status(400).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
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

        // 1. コードの有効性をチェック
        const rows = await conn.query(
            "SELECT minecraft_uuid FROM link_codes WHERE code = ? AND expires_at > NOW()",
            [code]
        );
        if (rows.length === 0) {
            throw new Error("コードが無効か、有効期限が切れています");
        }
        const minecraftUuid = rows[0].minecraft_uuid;

        // 1.5 連携しようとしているプレイヤーの名前を取得して、Javaか統合版か判定
        const pRows = await conn.query("SELECT player_name FROM fje_balances WHERE uuid = ?", [minecraftUuid]);
        if (pRows.length === 0) {
            throw new Error("マイクラのプレイヤーデータが存在しません");
        }
        const playerName = pRows[0].player_name;
        const isBedrock = minecraftUuid.startsWith('00000000-0000-0000-') || (playerName && playerName.startsWith('.'));
        const accountType = isBedrock ? 'BEDROCK' : 'JAVA';

        // 2. 重複チェック
        const existingLink = await conn.query("SELECT web_user_id FROM account_links WHERE minecraft_uuid = ?", [minecraftUuid]);
        if (existingLink.length > 0) {
            throw new Error("このマイクラアカウントは既に別のWebアカウントに連携されています");
        }

        // 2.5 同一タイプ重複チェック
        const myLinks = await conn.query(`
            SELECT l.minecraft_uuid, b.player_name 
            FROM account_links l
            JOIN fje_balances b ON l.minecraft_uuid = b.uuid
            WHERE l.web_user_id = ?
        `, [req.session.webUserId]);

        for (const link of myLinks) {
            const linkIsBedrock = link.minecraft_uuid.startsWith('00000000-0000-0000-') || (link.player_name && link.player_name.startsWith('.'));
            const linkType = linkIsBedrock ? 'BEDROCK' : 'JAVA';
            if (linkType === accountType) {
                throw new Error(`既に${accountType === 'JAVA' ? 'Java版' : '統合版'}のアカウントが連携されています。それぞれ1つずつのみ連携可能です。`);
            }
        }

        // 3. 紐づけ情報を保存
        await conn.query(
            "INSERT INTO account_links (web_user_id, minecraft_uuid) VALUES (?, ?)",
            [req.session.webUserId, minecraftUuid]
        );

        // 4. 使い終わったコードを削除
        await conn.query("DELETE FROM link_codes WHERE code = ?", [code]);

        await conn.commit();
        res.json({ success: true, message: `${accountType === 'JAVA' ? 'Java版' : '統合版'}アカウントの連携が完了しました！` });

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
        const userRows = await conn.query("SELECT email, discord_id FROM web_users WHERE id = ?", [req.session.webUserId]);
        if (userRows.length === 0) return res.status(404).json({ error: "ユーザーが見つかりません" });

        const mcRows = await conn.query(`
            SELECT b.uuid, b.player_name, b.balance 
            FROM account_links l
            JOIN fje_balances b ON l.minecraft_uuid = b.uuid
            WHERE l.web_user_id = ?
        `, [req.session.webUserId]);

        const accounts = mcRows.map(row => {
            const isBedrock = row.uuid.startsWith('00000000-0000-0000-') || (row.player_name && row.player_name.startsWith('.'));
            return {
                uuid: row.uuid,
                player_name: row.player_name,
                balance: row.balance,
                type: isBedrock ? 'Bedrock' : 'Java'
            };
        });

        res.json({
            email: userRows[0].email,
            discord_id: userRows[0].discord_id,
            accounts: accounts
        });
    } catch (err) {
        res.status(500).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// 送金
app.post('/api/wallet/send', requireAuth, async (req, res) => {
    const { from_uuid, to_player, amount } = req.body;
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

        let fromUuid = from_uuid;
        if (!fromUuid) {
            fromUuid = links[0].minecraft_uuid;
        } else {
            const hasLink = links.some(l => l.minecraft_uuid === fromUuid);
            if (!hasLink) throw new Error("指定された送金元アカウントはあなたに連携されていません");
        }

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
            "INSERT INTO fje_transactions (buyer_uuid, owner_uuid, price_total, server_id, item_id, net_profit, timestamp) VALUES (?, ?, ?, 'WEB', 'WEB_PAYPAY', ?, NOW())",
            [fromUuid, toUuid, parsedAmount, parsedAmount]
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
// その他API（残高取得・ランキング・ショップ・履歴など）
// ==========================================

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
    await initDatabase();
    console.log(`FJ Economy Full-Featured API Server running on port ${PORT}`);
});