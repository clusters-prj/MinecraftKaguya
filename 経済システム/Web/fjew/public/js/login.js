const loginSection = document.getElementById('loginSection');
const registerSection = document.getElementById('registerSection');
const verifyPendingSection = document.getElementById('verifyPendingSection');
const forgotPasswordSection = document.getElementById('forgotPasswordSection');
const forgotPendingSection = document.getElementById('forgotPendingSection');

function showSection(section) {
    loginSection.classList.add('hidden');
    registerSection.classList.add('hidden');
    verifyPendingSection.classList.add('hidden');
    forgotPasswordSection.classList.add('hidden');
    forgotPendingSection.classList.add('hidden');

    if (section === 'login') loginSection.classList.remove('hidden');
    if (section === 'register') registerSection.classList.remove('hidden');
    if (section === 'verifyPending') verifyPendingSection.classList.remove('hidden');
    if (section === 'forgotPassword') forgotPasswordSection.classList.remove('hidden');
    if (section === 'forgotPending') forgotPendingSection.classList.remove('hidden');
}

document.getElementById('toRegister').addEventListener('click', (e) => {
    e.preventDefault();
    showSection('register');
});

document.getElementById('toLogin').addEventListener('click', (e) => {
    e.preventDefault();
    showSection('login');
});

document.getElementById('backToLoginFromPending').addEventListener('click', (e) => {
    e.preventDefault();
    showSection('login');
});

document.getElementById('toForgotPassword').addEventListener('click', (e) => {
    e.preventDefault();
    showSection('forgotPassword');
});

document.getElementById('toLoginFromForgot').addEventListener('click', (e) => {
    e.preventDefault();
    showSection('login');
});

document.getElementById('backToLoginFromForgotPending').addEventListener('click', (e) => {
    e.preventDefault();
    showSection('login');
});

// パスワード再設定メール送信処理
document.getElementById('submitForgotPassword').addEventListener('click', async (e) => {
    const email = document.getElementById('forgotEmail').value;
    if (!email) {
        alert('メールアドレスを入力してください');
        return;
    }

    // ボタンを無効化して連打を防止し、見た目を暗くする
    const btn = e.target;
    btn.disabled = true;
    btn.classList.add('opacity-50', 'cursor-not-allowed');

    const { data } = await window.fjew.fetchJson('/api/auth/forgot-password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email })
    });

    if (data.success) {
        showSection('forgotPending');
    } else {
        alert('エラー: ' + data.error);
        // エラー時は再度押せるように元に戻す
        btn.disabled = false;
        btn.classList.remove('opacity-50', 'cursor-not-allowed');
    }
});

// アカウント新規作成処理
document.getElementById('submitRegister').addEventListener('click', async (e) => {
    const email = document.getElementById('registerEmail').value;
    const password = document.getElementById('registerPassword').value;

    // ボタンを無効化して連打を防止し、見た目を暗くする
    const btn = e.target;
    btn.disabled = true;
    btn.classList.add('opacity-50', 'cursor-not-allowed');

    const { data } = await window.fjew.fetchJson('/api/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password })
    });

    if (data.success) {
        document.getElementById('sentEmailAddress').innerText = email;
        showSection('verifyPending');
    } else {
        alert('エラー: ' + data.error);
        // エラー時は再度押せるように元に戻す
        btn.disabled = false;
        btn.classList.remove('opacity-50', 'cursor-not-allowed');
    }
});

document.getElementById('submitLogin').addEventListener('click', async () => {
    const email = document.getElementById('loginEmail').value;
    const password = document.getElementById('loginPassword').value;

    const { data } = await window.fjew.fetchJson('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password })
    });

    if (data.success) {
        window.location.href = '/main';
    } else {
        alert('エラー: ' + data.error);
    }
});

async function initLoginPage() {
    let user = null;
    try {
        user = await window.fjew.getCurrentUser();
    } catch (e) {
        console.error('[Login] getCurrentUser 取得中にエラー:', e);
    }
    if (user) {
        window.location.href = '/main';
        return;
    }

    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.get('verified') === 'true') {
        alert('メール認証が完了しました！ログインしてください。');
        window.history.replaceState({}, document.title, '/login');
    }

    const VERIFY_ERROR_MESSAGES = {
        TOKEN_EXPIRED_OR_USED: 'リンクの有効期限が切れているか、すでに使用されています。',
        VERIFY_FAILED: 'メール認証処理中にエラーが発生しました。時間をおいて再度お試しください。'
    };

    const verifyErrorCode = urlParams.get('verify_error_code');
    if (verifyErrorCode) {
        const message = VERIFY_ERROR_MESSAGES[verifyErrorCode] || VERIFY_ERROR_MESSAGES.VERIFY_FAILED;
        document.getElementById('verifyErrorMessage').innerText = 'メール認証に失敗しました: ' + message;
        document.getElementById('verifyErrorCodeText').innerText = `エラーコード: ${verifyErrorCode}`;
        document.getElementById('verifyErrorBox').classList.remove('hidden');
        window.history.replaceState({}, document.title, '/login');
    }

    showSection('login');
}

// Rocket Loader等でスクリプト実行が遅延し、DOMContentLoadedが
// すでに発火済みになっているケースへの保険
if (document.readyState === 'loading') {
    window.addEventListener('DOMContentLoaded', initLoginPage);
} else {
    initLoginPage();
}