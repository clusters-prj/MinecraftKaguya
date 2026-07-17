const loginSection = document.getElementById('loginSection');
const registerSection = document.getElementById('registerSection');
const verifyPendingSection = document.getElementById('verifyPendingSection');

function showSection(section) {
    loginSection.classList.add('hidden');
    registerSection.classList.add('hidden');
    verifyPendingSection.classList.add('hidden');

    if (section === 'login') loginSection.classList.remove('hidden');
    if (section === 'register') registerSection.classList.remove('hidden');
    if (section === 'verifyPending') verifyPendingSection.classList.remove('hidden');
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

document.getElementById('submitRegister').addEventListener('click', async () => {
    const email = document.getElementById('registerEmail').value;
    const password = document.getElementById('registerPassword').value;

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