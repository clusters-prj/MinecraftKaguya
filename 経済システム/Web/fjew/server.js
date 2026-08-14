// ★ 一番最初に追加して環境変数をロードする
require('dotenv').config();

// ==========================================
// 1. モジュール読み込み ＆ 基本設定
// ==========================================
const express = require('express');
const path = require('path');
const session = require('express-session');
const FileStore = require('session-file-store')(session);
const bcrypt = require('bcrypt');
const fs = require('fs');
const crypto = require('crypto');
const nodemailer = require('nodemailer');
const rateLimit = require('express-rate-limit');
const helmet = require("helmet");
const pool = require('./db'); // この中で process.env が正常に使える

// BigIntをJSONで出力できるようにシリアライズ方法を定義
BigInt.prototype.toJSON = function() {
    return this.toString();
};

const app = express();
const PORT = 3200;

// ==========================================
// 2. 定数 ＆ 環境変数チェック
// ==========================================
// 政府口座のUUID定数
const GOV_UUID = '00000000-0000-0000-0000-000000000001';

// シークレットがないとき通知だけ
if (!process.env.SESSION_SECRET) {
    throw new Error("SESSION_SECRET is not set.");
}

// アプリのベースURL (.envから取得)
// メール内のリンク生成にはリクエストのHostヘッダーを使わず、信頼できる.envの値のみを使用する (Host Header Poisoning対策)
if (!process.env.APP_BASE_URL) {
    console.warn("[Config Warning] APP_BASE_URL が .env に設定されていません。メール内のリンクが正しく生成できません。");
}
const APP_BASE_URL = (process.env.APP_BASE_URL || '').replace(/\/+$/, '');

// SMTPメール送信設定
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
const FROM_EMAIL = process.env.FROM_EMAIL || `"ふじゅ〜ペイ" <no-reply@clusters-prj.com>`;

const GTM_ID = process.env.GTM_ID || '';
if (!GTM_ID) {
    console.warn("[Config Warning] GTM_ID が .env に設定されていません。GTMタグは出力されません。");
}

// ==========================================
// 3. ミドルウェア設定 (セキュリティ・制限・CORS・セッション)
// ==========================================
// リバースプロキシ(Cloudflare Tunnel等)配下で動かす場合の設定
app.set('trust proxy', 1);

// 自動防御の設定
// 自動防御の設定
app.use(helmet({ 
    contentSecurityPolicy: false,
    crossOriginOpenerPolicy: false // ★ これを追加（GTMのデバッグ通信を許可）
}));
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// Rate Limitの設定
const createLimiter = (windowMs, limit) =>
    rateLimit({
        windowMs,
        limit,
        standardHeaders: true,
        legacyHeaders: false,
        message: { error: "試行回数が多すぎます。しばらく待ってから再度お試しください。" }
    });

const loginLimiter = createLimiter(15 * 60 * 1000, 5);
const registerLimiter = createLimiter(60 * 60 * 1000, 3);
const forgotPasswordLimiter = createLimiter(60 * 60 * 1000, 3);
const resetPasswordLimiter = createLimiter(30 * 60 * 1000, 5);
const linkLimiter = createLimiter(10 * 60 * 1000, 10);
const verifyLimiter = createLimiter(10 * 60 * 1000, 20);
const buildQueryLimiter = createLimiter(10 * 60 * 1000, 20);

// CORS設定
const allowedOrigin = APP_BASE_URL ? new URL(APP_BASE_URL).origin : null;
app.use((req, res, next) => {
    const origin = req.headers.origin;
    if (allowedOrigin && origin === allowedOrigin) {
        res.header("Access-Control-Allow-Origin", origin);
    }
    res.header("Access-Control-Allow-Credentials", "true");
    res.header("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
    res.header("Access-Control-Allow-Methods", "GET,POST,OPTIONS");

    if (req.method === "OPTIONS") {
        return res.sendStatus(204);
    }
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
    secret: process.env.SESSION_SECRET,
    resave: false,
    saveUninitialized: false,
    cookie: {
        secure: true,
        httpOnly: true,
        sameSite: "lax",
        maxAge: 1000 * 60 * 60 * 24
    }
}));

// 金額パース用ヘルパー
// BigInt() は "abc" や undefined、"1.5" を渡すと例外を投げるため、
// バリデーション前に直接呼ぶと 400 ではなく 500 になってしまう。
// パースできない場合は null を返し、呼び出し側で 400 を返す。
const parseAmount = (value) => {
    if (value === null || value === undefined || value === '') return null;
    const text = String(value).trim();
    if (!/^-?\d+$/.test(text)) return null;
    try {
        return BigInt(text);
    } catch {
        return null;
    }
};

// 想定外のサーバーエラーを返すヘルパー
// err.message をそのまま返すと、DBのテーブル名・カラム名・SQL構文エラーなどが
// クライアントに漏れるため、詳細はサーバーログにのみ残して汎用メッセージを返す。
// （ユーザーへ見せる目的で throw している 400 系のメッセージはこれまで通り err.message を使う）
const sendServerError = (res, err) => {
    console.error("[Server Error]", err);
    return res.status(500).json({ error: "サーバー内部でエラーが発生しました。時間をおいて再度お試しください。" });
};

// ログインチェック用ミドルウェア
const requireAuth = (req, res, next) => {
    if (!req.session.webUserId) {
        return res.status(401).json({ error: "ログインが必要です" });
    }
    next();
};

// 管理者チェック用ミドルウェア（requireAuthの後に使用する）
// .env の ADMIN_EMAILS（カンマ区切り）に自分のメールアドレスが含まれるかで判定する
const ADMIN_EMAILS = (process.env.ADMIN_EMAILS || '')
    .split(',')
    .map(e => e.trim().toLowerCase())
    .filter(e => e.length > 0);

const requireAdmin = async (req, res, next) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT email FROM web_users WHERE id = ?", [req.session.webUserId]);
        if (rows.length === 0 || !ADMIN_EMAILS.includes(rows[0].email.toLowerCase())) {
            return res.status(403).json({ error: "管理者権限が必要です" });
        }
        next();
    } catch (err) {
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
};

// ==========================================
// 4. データベース初期化
// ==========================================
async function initDatabase() {
    let conn;
    try {
        conn = await pool.getConnection();
        
        await conn.query(`
            CREATE TABLE IF NOT EXISTS web_users (
                id INT AUTO_INCREMENT PRIMARY KEY,
                email VARCHAR(255) NOT NULL UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                discord_id VARCHAR(64) DEFAULT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `);
        await conn.query(`
            CREATE TABLE IF NOT EXISTS account_links (
                web_user_id INT NOT NULL,
                minecraft_uuid VARCHAR(36) NOT NULL,
                PRIMARY KEY (web_user_id, minecraft_uuid),
                FOREIGN KEY (web_user_id) REFERENCES web_users(id) ON DELETE CASCADE
            )
        `);
        await conn.query(`
            CREATE TABLE IF NOT EXISTS link_codes (
                code VARCHAR(6) PRIMARY KEY,
                minecraft_uuid VARCHAR(36) NOT NULL,
                expires_at TIMESTAMP NOT NULL
            )
        `);
        await conn.query(`
            CREATE TABLE IF NOT EXISTS email_verifications (
                email VARCHAR(255) PRIMARY KEY,
                password_hash VARCHAR(255) NOT NULL,
                token VARCHAR(255) NOT NULL,
                expires_at TIMESTAMP NOT NULL
            )
        `);
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
// 5. ページ配信ルート (フロントエンド)
// ==========================================

// HTMLを読み込んでGTMタグを動的に挿入するヘルパー関数
const sendHtmlWithGTM = (filePath, res) => {
    fs.readFile(filePath, 'utf8', (err, data) => {
        if (err) {
            console.error("HTMLファイルの読み込みエラー:", err);
            return res.status(500).send("Internal Server Error");
        }

        let html = data;
        
        // GTM_IDが設定されている場合のみタグを挿入
            if (GTM_ID) {
            const gtmHead = `
<!-- Google Tag Manager -->
<script data-cfasync="false">(function(w,d,s,l,i){w[l]=w[l]||[];w[l].push({'gtm.start':
new Date().getTime(),event:'gtm.js'});var f=d.getElementsByTagName(s)[0],
j=d.createElement(s),dl=l!='dataLayer'?'&l='+l:'';j.async=true;j.src=
'https://www.googletagmanager.com/gtm.js?id='+i+dl;f.parentNode.insertBefore(j,f);
})(window,document,'script','dataLayer','${GTM_ID}');</script>
<!-- End Google Tag Manager -->
`;
            const gtmBody = `
<!-- Google Tag Manager (noscript) -->
<noscript><iframe src="https://www.googletagmanager.com/ns.html?id=${GTM_ID}"
height="0" width="0" style="display:none;visibility:hidden"></iframe></noscript>
<!-- End Google Tag Manager (noscript) -->
`;
            // <head>タグと<body>タグの直後にそれぞれ挿入する
            html = html.replace(/(<head[^>]*>)/i, `$1\n${gtmHead}`);
            html = html.replace(/(<body[^>]*>)/i, `$1\n${gtmBody}`);
        }

        res.send(html);
    });
};

app.get('/', (req, res) => {
    const queryString = req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : '';
    res.redirect(`/login${queryString}`);
});

app.get('/login', (req, res) => {
    sendHtmlWithGTM(path.join(__dirname, 'public', 'login.html'), res);
});
app.get('/reset-password', (req, res) => {
    sendHtmlWithGTM(path.join(__dirname, 'public', 'reset-password.html'), res);
});
app.get('/main', (req, res) => {
    sendHtmlWithGTM(path.join(__dirname, 'public', 'main.html'), res);
});
app.get('/history', (req, res) => {
    sendHtmlWithGTM(path.join(__dirname, 'public', 'history.html'), res);
});
app.get('/settings', (req, res) => {
    sendHtmlWithGTM(path.join(__dirname, 'public', 'settings.html'), res);
});
app.get('/arena', (req, res) => {
    sendHtmlWithGTM(path.join(__dirname, 'public', 'arena.html'), res);
});
app.get('/arena-admin', (req, res) => {
    sendHtmlWithGTM(path.join(__dirname, 'public', 'arena-admin.html'), res);
});
app.get('/build-admin', (req, res) => {
    sendHtmlWithGTM(path.join(__dirname, 'public', 'build-admin.html'), res);
});

// ==========================================
// 6. APIルート: 認証・アカウント連携
// ==========================================

// アカウント仮登録 ＆ 認証メール送信
app.post('/api/auth/register', registerLimiter, async (req, res) => {
    const { email, password } = req.body;
    if (!email || !password) return res.status(400).json({ error: "メアドとパスワードを入力してください" });

    let conn;
    try {
        conn = await pool.getConnection();
        const existing = await conn.query("SELECT id FROM web_users WHERE email = ?", [email]);
        if (existing.length > 0) return res.status(400).json({ error: "このメールアドレスは既に登録されています" });

        const saltRounds = 10;
        const passwordHash = await bcrypt.hash(password, saltRounds);
        const token = crypto.randomBytes(32).toString('hex');

        await conn.query(`
            INSERT INTO email_verifications (email, password_hash, token, expires_at) 
            VALUES (?, ?, ?, DATE_ADD(NOW(), INTERVAL 30 MINUTE))
            ON DUPLICATE KEY UPDATE password_hash = ?, token = ?, expires_at = DATE_ADD(NOW(), INTERVAL 30 MINUTE)
        `, [email, passwordHash, token, passwordHash, token]);

        const verifyUrl = `${APP_BASE_URL}/api/auth/verify?token=${token}`;
        const mailOptions = {
            from: FROM_EMAIL,
            to: email,
            subject: '【ふじゅ〜ペイ】新規登録アカウントの有効化',
            text: `ふじゅ〜ペイをご利用いただきありがとうございます。\n\n以下のリンクをクリックして、アカウント登録を完了させてください。\n\n▼ 登録を完了する\n${verifyUrl}\n\n※有効期限: 30分\n※このメールに心当たりがない場合は、破棄してください。`
        };

        const info = await transporter.sendMail(mailOptions);
        console.log(`[Mail Success] メール送信完了しました！\n  - To: ${email}\n  - Message-ID: ${info.messageId}\n  - Response: ${info.response}`);

        res.json({ success: true, message: "認証用メールを送信しました。メール内のリンクをクリックしてください。" });
    } catch (err) {
        console.error("[Mail Error] ユーザー登録・メール送信プロセスでエラーが発生しました:", err);
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

// メール認証リンクの検証 ＆ 本登録
app.get('/api/auth/verify', verifyLimiter, async (req, res) => {
    const { token } = req.query;
    if (!token) return res.status(400).send('<h1>無効なリクエストです</h1><p>トークンが存在しません。</p>');

    const redirectWithError = (code) => {
        res.redirect(`/login?verify_error_code=${encodeURIComponent(code)}`);
    };

    let conn;
    try {
        conn = await pool.getConnection();
        await conn.beginTransaction();

        const rows = await conn.query(
            "SELECT email, password_hash FROM email_verifications WHERE token = ? AND expires_at > NOW()",
            [token]
        );

        if (rows.length === 0) {
            const err = new Error("リンクの有効期限が切れているか、すでに使用されています。");
            err.code = "TOKEN_EXPIRED_OR_USED";
            throw err;
        }

        const { email, password_hash } = rows[0];
        await conn.query("INSERT INTO web_users (email, password_hash) VALUES (?, ?)", [email, password_hash]);
        await conn.query("DELETE FROM email_verifications WHERE email = ?", [email]);

        await conn.commit();
        res.redirect('/login?verified=true');
    } catch (err) {
        if (conn) await conn.rollback();
        console.error("[Verify Debug] 認証処理中にエラーが発生しロールバックしました:", err);
        redirectWithError(err.code || "VERIFY_FAILED");
    } finally {
        if (conn) conn.release();
    }
});

// ログイン
app.post('/api/auth/login', loginLimiter, async (req, res) => {
    const { email, password } = req.body;
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT id, password_hash FROM web_users WHERE email = ?", [email]);

        if (rows.length === 0) return res.status(401).json({ error: "メアドまたはパスワードが間違っています" });

        const match = await bcrypt.compare(password, rows[0].password_hash);
        if (!match) return res.status(401).json({ error: "メアドまたはパスワードが間違っています" });

        req.session.webUserId = rows[0].id;
        res.json({ success: true, message: "ログインしました" });
    } catch (err) {
        sendServerError(res, err);
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

// パスワードリセット申請
app.post('/api/auth/forgot-password', forgotPasswordLimiter, async (req, res) => {
    const { email } = req.body;
    if (!email) return res.status(400).json({ error: "メールアドレスを入力してください" });

    let conn;
    try {
        conn = await pool.getConnection();
        const existing = await conn.query("SELECT id FROM web_users WHERE email = ?", [email]);

        if (existing.length > 0) {
            const token = crypto.randomBytes(32).toString('hex');
            await conn.query(`
                INSERT INTO password_resets (email, token, expires_at)
                VALUES (?, ?, DATE_ADD(NOW(), INTERVAL 30 MINUTE))
                ON DUPLICATE KEY UPDATE token = ?, expires_at = DATE_ADD(NOW(), INTERVAL 30 MINUTE)
            `, [email, token, token]);

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

        res.json({ success: true, message: "パスワード再設定用のメールを送信しました。メールをご確認ください。" });
    } catch (err) {
        console.error("[Reset Debug] パスワードリセット申請処理でエラー:", err);
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

// パスワードリセット実行
app.post('/api/auth/reset-password', resetPasswordLimiter, async (req, res) => {
    const { token, password } = req.body;
    if (!token || !password) return res.status(400).json({ error: "トークンと新しいパスワードを入力してください" });
    if (password.length < 8) return res.status(400).json({ error: "パスワードは8文字以上で入力してください" });

    let conn;
    try {
        conn = await pool.getConnection();
        await conn.beginTransaction();

        const rows = await conn.query("SELECT email FROM password_resets WHERE token = ? AND expires_at > NOW()", [token]);
        if (rows.length === 0) throw new Error("リンクの有効期限が切れているか、無効なリンクです。もう一度お試しください。");

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

// マイクラアカウント連携
app.post('/api/auth/link', requireAuth, linkLimiter, async (req, res) => {
    const { code } = req.body;
    if (!code || code.length !== 6) return res.status(400).json({ error: "6桁のコードを入力してください" });

    let conn;
    try {
        conn = await pool.getConnection();
        await conn.beginTransaction();

        const rows = await conn.query("SELECT minecraft_uuid FROM link_codes WHERE code = ? AND expires_at > NOW()", [code]);
        if (rows.length === 0) throw new Error("コードが無効か、有効期限が切れています");
        
        const minecraftUuid = rows[0].minecraft_uuid;
        const pRows = await conn.query("SELECT player_name FROM fje_balances WHERE uuid = ?", [minecraftUuid]);
        if (pRows.length === 0) throw new Error("マイクラのプレイヤーデータが存在しません");
        
        const playerName = pRows[0].player_name;
        const isBedrock = minecraftUuid.startsWith('00000000-0000-0000-') || (playerName && playerName.startsWith('.'));
        const accountType = isBedrock ? 'BEDROCK' : 'JAVA';

        const existingLink = await conn.query("SELECT web_user_id FROM account_links WHERE minecraft_uuid = ?", [minecraftUuid]);
        if (existingLink.length > 0) throw new Error("このマイクラアカウントは既に別のWebアカウントに連携されています");

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

        await conn.query("INSERT INTO account_links (web_user_id, minecraft_uuid) VALUES (?, ?)", [req.session.webUserId, minecraftUuid]);
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

// ==========================================
// 7. APIルート: ユーザー情報 ＆ ウォレット (送金)
// ==========================================

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
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

// 送金
app.post('/api/wallet/send', requireAuth, async (req, res) => {
    const { from_uuid, to_player, amount } = req.body;
    const parsedAmount = parseAmount(amount);

    if (!to_player || parsedAmount === null || parsedAmount <= 0n) {
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
// 8. APIルート: 経済データ・ショップ・履歴等
// ==========================================
app.get('/api/economy/balance/:uuid', async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT uuid, player_name, balance FROM fje_balances WHERE uuid = ?", [req.params.uuid]);
        if (rows.length === 0) return res.status(404).json({ error: "Player not found" });
        res.json(rows[0]);
    } catch (err) {
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

app.get('/api/economy/ranking', async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT player_name, balance FROM fje_balances WHERE uuid != ? ORDER BY balance DESC LIMIT 100", [GOV_UUID]);
        res.json(rows);
    } catch (err) {
        sendServerError(res, err);
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
        sendServerError(res, err);
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
        sendServerError(res, err);
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
        sendServerError(res, err);
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
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

// 自分（連携中の全アカウント）が関わった取引履歴を取得
// ?type=all|sent|received  … 絞り込み（省略時 all）
// ?page=1&limit=20         … ページネーション
app.get('/api/wallet/history', requireAuth, async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();

        const links = await conn.query(
            "SELECT minecraft_uuid FROM account_links WHERE web_user_id = ?",
            [req.session.webUserId]
        );
        if (links.length === 0) {
            return res.json({ transactions: [], total: 0, page: 1, limit: 20, has_more: false });
        }
        const myUuids = links.map(l => l.minecraft_uuid);

        const type = req.query.type || 'all'; // all | sent | received
        const page = Math.max(parseInt(req.query.page, 10) || 1, 1);
        const limit = Math.min(Math.max(parseInt(req.query.limit, 10) || 20, 1), 100);
        const offset = (page - 1) * limit;

        const placeholders = myUuids.map(() => '?').join(',');

        let whereClause;
        let whereParams;
        if (type === 'sent') {
            whereClause = `t.buyer_uuid IN (${placeholders})`;
            whereParams = [...myUuids];
        } else if (type === 'received') {
            whereClause = `t.owner_uuid IN (${placeholders})`;
            whereParams = [...myUuids];
        } else {
            whereClause = `(t.buyer_uuid IN (${placeholders}) OR t.owner_uuid IN (${placeholders}))`;
            whereParams = [...myUuids, ...myUuids];
        }

        const countRows = await conn.query(
            `SELECT COUNT(*) AS cnt FROM fje_transactions t WHERE ${whereClause}`,
            whereParams
        );
        const total = Number(countRows[0].cnt);

        const rows = await conn.query(`
            SELECT
                t.id,
                t.timestamp,
                t.server_id,
                t.item_id,
                t.price_total,
                t.tax_amount,
                t.net_profit,
                t.buyer_uuid,
                t.owner_uuid,
                bb.player_name AS buyer_name,
                ob.player_name AS owner_name
            FROM fje_transactions t
            LEFT JOIN fje_balances bb ON t.buyer_uuid = bb.uuid
            LEFT JOIN fje_balances ob ON t.owner_uuid = ob.uuid
            WHERE ${whereClause}
            ORDER BY t.timestamp DESC
            LIMIT ? OFFSET ?
        `, [...whereParams, limit, offset]);

        // 自分視点での「入金/出金」「相手」「種別」を付与
        const transactions = rows.map(r => {
            const iAmBuyer = myUuids.includes(r.buyer_uuid);
            const iAmOwner = myUuids.includes(r.owner_uuid);
            const isInternalBoth = iAmBuyer && iAmOwner; // 自分の複数アカウント間送金

            let direction; // 'out' = 自分の残高が減った, 'in' = 増えた
            let counterpartName;
            if (isInternalBoth) {
                direction = 'internal';
                counterpartName = '自分の別アカウント';
            } else if (iAmBuyer) {
                direction = 'out';
                counterpartName = r.owner_name || '(不明なプレイヤー)';
            } else {
                direction = 'in';
                counterpartName = r.buyer_name || '(不明なプレイヤー)';
            }

            let txType = 'shop';
            if (r.item_id === 'WEB_PAYPAY') {
                txType = 'transfer';
            } else if (r.item_id === 'BUILD_REWARD') {
                txType = 'build_reward';
            }

            return {
                id: r.id,
                timestamp: r.timestamp,
                server_id: r.server_id,
                item_id: r.item_id,
                type: txType,
                direction,
                counterpart_name: counterpartName,
                amount: r.price_total,
                tax_amount: r.tax_amount,
                net_profit: r.net_profit
            };
        });

        res.json({
            transactions,
            total,
            page,
            limit,
            has_more: offset + rows.length < total
        });
    } catch (err) {
        sendServerError(res, err);
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
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

// ==========================================
// 9. APIルート: 管理者用データ取得
// ==========================================
app.get('/api/admin/economy/summary', requireAuth, requireAdmin, async (req, res) => {
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
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

app.get('/api/admin/government/ledger', requireAuth, requireAdmin, async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query("SELECT * FROM fje_government_ledger ORDER BY timestamp DESC LIMIT 100");
        res.json(rows);
    } catch (err) {
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

// ==========================================
// 10. APIルート: アリーナ監視イベント・優勝者予想ベット
// ==========================================

// [管理者専用] イベント作成
app.post('/api/admin/arena/events', requireAuth, requireAdmin, async (req, res) => {
    const { name, world, x, y, z, radius, prize_amount, participants } = req.body;

    if (!name || !world || !Array.isArray(participants) || participants.length < 2) {
        return res.status(400).json({ error: "name, world, participants(2件以上) が必要です" });
    }
    const centerX = Number(x), centerY = Number(y), centerZ = Number(z), r = Number(radius);
    if ([centerX, centerY, centerZ, r].some(n => Number.isNaN(n)) || r <= 0) {
        return res.status(400).json({ error: "座標・半径は数値で指定してください" });
    }
    const prizeAmount = parseAmount(prize_amount ?? 0);
    if (prizeAmount === null || prizeAmount < 0n) return res.status(400).json({ error: "賞金額が正しくありません" });

    let conn;
    try {
        conn = await pool.getConnection();
        await conn.beginTransaction();

        const participantRows = await conn.query(
            `SELECT uuid, player_name FROM fje_balances WHERE uuid IN (${participants.map(() => '?').join(',')}) OR player_name IN (${participants.map(() => '?').join(',')})`,
            [...participants, ...participants]
        );
        if (participantRows.length !== participants.length) {
            throw new Error("対戦プレイヤーの一部が見つかりませんでした");
        }

        const eventResult = await conn.query(
            "INSERT INTO fje_arena_events (name, world, center_x, center_y, center_z, radius, prize_amount) VALUES (?, ?, ?, ?, ?, ?, ?)",
            [name, world, centerX, centerY, centerZ, r, prizeAmount]
        );
        const eventId = eventResult.insertId;

        for (const p of participantRows) {
            await conn.query(
                "INSERT INTO fje_arena_participants (event_id, minecraft_uuid, player_name) VALUES (?, ?, ?)",
                [eventId, p.uuid, p.player_name]
            );
        }

        await conn.commit();
        res.json({ success: true, event_id: Number(eventId) });
    } catch (err) {
        if (conn) await conn.rollback();
        res.status(400).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// [管理者専用] イベント一覧（全ステータス）
app.get('/api/admin/arena/events', requireAuth, requireAdmin, async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const events = await conn.query("SELECT * FROM fje_arena_events ORDER BY created_at DESC LIMIT 100");
        const participants = await conn.query(
            "SELECT event_id, minecraft_uuid, player_name FROM fje_arena_participants WHERE event_id IN (?)",
            [events.length ? events.map(e => e.id) : [0]]
        );
        const byEvent = {};
        for (const p of participants) {
            (byEvent[p.event_id] ??= []).push({ uuid: p.minecraft_uuid, player_name: p.player_name });
        }
        res.json(events.map(e => ({ ...e, participants: byEvent[e.id] || [] })));
    } catch (err) {
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

// [管理者専用] イベントのキャンセル（ベットは全額払い戻し）
app.post('/api/admin/arena/events/:id/cancel', requireAuth, requireAdmin, async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        await conn.beginTransaction();

        const eventRows = await conn.query("SELECT * FROM fje_arena_events WHERE id = ? AND status = 'ACTIVE'", [req.params.id]);
        if (eventRows.length === 0) throw new Error("キャンセル可能なアクティブイベントが見つかりません");

        const bets = await conn.query("SELECT * FROM fje_arena_bets WHERE event_id = ? AND status = 'PLACED'", [req.params.id]);
        for (const bet of bets) {
            await conn.query("UPDATE fje_balances SET balance = balance + ? WHERE uuid = ?", [bet.amount, bet.bettor_uuid]);
            await conn.query("UPDATE fje_arena_bets SET status = 'REFUNDED', payout_amount = ? WHERE id = ?", [bet.amount, bet.id]);
        }
        await conn.query("UPDATE fje_arena_events SET status = 'CANCELLED', resolved_at = NOW() WHERE id = ?", [req.params.id]);

        await conn.commit();
        res.json({ success: true, message: "イベントをキャンセルし、ベットを全額払い戻しました" });
    } catch (err) {
        if (conn) await conn.rollback();
        res.status(400).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// アクティブなアリーナイベント一覧（ベット用）
app.get('/api/arena/events', requireAuth, async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const events = await conn.query("SELECT id, name, world, prize_amount, status, created_at FROM fje_arena_events WHERE status = 'ACTIVE' ORDER BY created_at DESC");
        const participants = await conn.query(
            "SELECT event_id, minecraft_uuid, player_name FROM fje_arena_participants WHERE event_id IN (?)",
            [events.length ? events.map(e => e.id) : [0]]
        );
        const pools = await conn.query(
            "SELECT event_id, SUM(amount) AS pool FROM fje_arena_bets WHERE event_id IN (?) AND status = 'PLACED' GROUP BY event_id",
            [events.length ? events.map(e => e.id) : [0]]
        );
        const byEvent = {};
        for (const p of participants) {
            (byEvent[p.event_id] ??= []).push({ uuid: p.minecraft_uuid, player_name: p.player_name });
        }
        const poolByEvent = {};
        for (const row of pools) poolByEvent[row.event_id] = row.pool;

        res.json(events.map(e => ({
            ...e,
            participants: byEvent[e.id] || [],
            bet_pool: poolByEvent[e.id] || 0
        })));
    } catch (err) {
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

// 優勝者予想ベットを行う
app.post('/api/arena/events/:id/bet', requireAuth, async (req, res) => {
    const { predicted_uuid, amount } = req.body;
    const parsedAmount = parseAmount(amount);

    if (!predicted_uuid || parsedAmount === null || parsedAmount <= 0n) {
        return res.status(400).json({ error: "予想するプレイヤーと賭け金を正しく指定してください" });
    }

    let conn;
    try {
        conn = await pool.getConnection();
        await conn.beginTransaction();

        const links = await conn.query("SELECT minecraft_uuid FROM account_links WHERE web_user_id = ?", [req.session.webUserId]);
        if (links.length === 0) throw new Error("マイクラアカウントが連携されていません");
        const bettorUuid = links[0].minecraft_uuid;

        const eventRows = await conn.query("SELECT * FROM fje_arena_events WHERE id = ? AND status = 'ACTIVE'", [req.params.id]);
        if (eventRows.length === 0) throw new Error("受付中のイベントではありません");

        const participantRows = await conn.query(
            "SELECT 1 FROM fje_arena_participants WHERE event_id = ? AND minecraft_uuid = ?",
            [req.params.id, predicted_uuid]
        );
        if (participantRows.length === 0) throw new Error("予想したプレイヤーはこの対戦の参加者ではありません");

        const balanceRows = await conn.query("SELECT balance FROM fje_balances WHERE uuid = ?", [bettorUuid]);
        if (balanceRows.length === 0) throw new Error("あなたのマイクラデータが見つかりません");
        if (BigInt(balanceRows[0].balance) < parsedAmount) throw new Error("残高が不足しています");

        await conn.query("UPDATE fje_balances SET balance = balance - ? WHERE uuid = ?", [parsedAmount, bettorUuid]);
        await conn.query(
            "INSERT INTO fje_arena_bets (event_id, bettor_uuid, predicted_uuid, amount) VALUES (?, ?, ?, ?)",
            [req.params.id, bettorUuid, predicted_uuid, parsedAmount]
        );

        await conn.commit();
        res.json({ success: true, message: `${amount} 円を賭けました` });
    } catch (err) {
        if (conn) await conn.rollback();
        res.status(400).json({ error: err.message });
    } finally {
        if (conn) conn.release();
    }
});

// 自分のベット履歴
app.get('/api/arena/my-bets', requireAuth, async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const links = await conn.query("SELECT minecraft_uuid FROM account_links WHERE web_user_id = ?", [req.session.webUserId]);
        if (links.length === 0) return res.json([]);
        const myUuids = links.map(l => l.minecraft_uuid);
        const placeholders = myUuids.map(() => '?').join(',');

        const rows = await conn.query(`
            SELECT b.*, e.name AS event_name, e.status AS event_status, pb.player_name AS predicted_player_name
            FROM fje_arena_bets b
            JOIN fje_arena_events e ON b.event_id = e.id
            LEFT JOIN fje_balances pb ON b.predicted_uuid = pb.uuid
            WHERE b.bettor_uuid IN (${placeholders})
            ORDER BY b.created_at DESC LIMIT 50
        `, myUuids);
        res.json(rows);
    } catch (err) {
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

// ==========================================
// 11. APIルート: 建築量ポイント（CoreProtect集計）
// ==========================================

// datetime-local（2026-08-12T15:04）や通常の日時文字列を MySQL の DATETIME 形式に正規化する
const normalizeDateTime = (value) => {
    if (typeof value !== 'string') return null;
    const matched = value.trim().match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?$/);
    if (!matched) return null;
    const [, year, month, day, hour, minute, second] = matched;
    const parsed = new Date(`${year}-${month}-${day}T${hour}:${minute}:${second || '00'}`);
    if (Number.isNaN(parsed.getTime())) return null;
    return `${year}-${month}-${day} ${hour}:${minute}:${second || '00'}`;
};

// [管理者専用] 集計先として選べるサーバーID一覧
app.get('/api/admin/build/servers', requireAuth, requireAdmin, async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        // fje_transactions の server_id には、実際のマイクラサーバー（mc1/mc2/mc3）以外に
        // 'WEB'（Web送金）・'ARENA'（アリーナ精算）・'API_BOT'（外部API送金）という
        // 擬似的な値も入る。これらを候補に出すと、対応するプラグインが存在しないため
        // 集計リクエストが誰にも拾われず「待機中」のまま残ってしまうので除外する。
        const rows = await conn.query(`
            SELECT DISTINCT server_id FROM fje_build_rewards
            UNION
            SELECT DISTINCT server_id FROM fje_transactions
             WHERE server_id NOT IN ('WEB', 'ARENA', 'API_BOT')
            ORDER BY server_id
        `);
        res.json(rows.map(r => r.server_id));
    } catch (err) {
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

// [管理者専用] 任意期間の集計リクエストを登録する（実際の集計はマイクラ側プラグインが行う／ポイントは不加算）
app.post('/api/admin/build/queries', requireAuth, requireAdmin, buildQueryLimiter, async (req, res) => {
    const { server_id, range_start, range_end } = req.body;

    if (!server_id || !/^[A-Za-z0-9_-]{1,20}$/.test(server_id)) {
        return res.status(400).json({ error: "サーバーIDが正しくありません" });
    }
    const rangeStart = normalizeDateTime(range_start);
    const rangeEnd = normalizeDateTime(range_end);
    if (!rangeStart || !rangeEnd) {
        return res.status(400).json({ error: "集計期間の日時を正しく指定してください" });
    }
    if (rangeStart >= rangeEnd) {
        return res.status(400).json({ error: "終了日時は開始日時より後にしてください" });
    }

    let conn;
    try {
        conn = await pool.getConnection();
        const result = await conn.query(
            "INSERT INTO fje_build_queries (server_id, requested_by, range_start, range_end) VALUES (?, ?, ?, ?)",
            [server_id, req.session.webUserId, rangeStart, rangeEnd]
        );
        res.json({ success: true, query_id: Number(result.insertId) });
    } catch (err) {
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

// [管理者専用] 直近の集計リクエスト一覧
app.get('/api/admin/build/queries', requireAuth, requireAdmin, async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const rows = await conn.query(`
            SELECT q.id, q.server_id, q.range_start, q.range_end, q.status, q.error_message,
                   q.created_at, q.completed_at, u.email AS requested_by_email
            FROM fje_build_queries q
            LEFT JOIN web_users u ON q.requested_by = u.id
            ORDER BY q.id DESC LIMIT 20
        `);
        res.json(rows);
    } catch (err) {
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

// [管理者専用] 集計リクエストの状態とランキング結果
app.get('/api/admin/build/queries/:id', requireAuth, requireAdmin, async (req, res) => {
    let conn;
    try {
        conn = await pool.getConnection();
        const queries = await conn.query("SELECT * FROM fje_build_queries WHERE id = ?", [req.params.id]);
        if (queries.length === 0) return res.status(404).json({ error: "集計リクエストが見つかりません" });

        const ranking = await conn.query(`
            SELECT minecraft_uuid, player_name, blocks_placed, blocks_broken, score
            FROM fje_build_query_results
            WHERE query_id = ?
            ORDER BY score DESC, blocks_placed DESC
            LIMIT 100
        `, [req.params.id]);

        res.json({ ...queries[0], ranking });
    } catch (err) {
        sendServerError(res, err);
    } finally {
        if (conn) conn.release();
    }
});

// ==========================================
// 12. サーバー起動処理
// ==========================================
app.listen(PORT, async () => {
    await initDatabase();
    console.log(`FJ Economy Full-Featured API Server running on port ${PORT}`);
});