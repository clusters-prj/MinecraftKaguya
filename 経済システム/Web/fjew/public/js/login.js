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

window.addEventListener('DOMContentLoaded', async () => {
    const user = await window.fjew.getCurrentUser();
    if (user) {
        window.location.href = '/main';
        return;
    }

    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.get('verified') === 'true') {
        alert('メール認証が完了しました！ログインしてください。');
        window.history.replaceState({}, document.title, '/login');
    }

    const verifyError = urlParams.get('verify_error');
    if (verifyError) {
        alert('メール認証に失敗しました: ' + decodeURIComponent(verifyError));
        window.history.replaceState({}, document.title, '/login');
    }

    showSection('login');
});
