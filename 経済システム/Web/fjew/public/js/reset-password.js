const resetFormSection = document.getElementById('resetFormSection');
const resetDoneSection = document.getElementById('resetDoneSection');
const resetErrorSection = document.getElementById('resetErrorSection');

function showResetSection(section) {
    resetFormSection.classList.add('hidden');
    resetDoneSection.classList.add('hidden');
    resetErrorSection.classList.add('hidden');

    if (section === 'form') resetFormSection.classList.remove('hidden');
    if (section === 'done') resetDoneSection.classList.remove('hidden');
    if (section === 'error') resetErrorSection.classList.remove('hidden');
}

function initResetPage() {
    const urlParams = new URLSearchParams(window.location.search);
    const token = urlParams.get('token');

    if (!token) {
        document.getElementById('resetErrorMessage').innerText = 'リンクが不正です。パスワード再設定は「パスワードをお忘れですか？」からやり直してください。';
        showResetSection('error');
        return;
    }

    showResetSection('form');

    document.getElementById('submitReset').addEventListener('click', async () => {
        const password = document.getElementById('newPassword').value;
        const passwordConfirm = document.getElementById('newPasswordConfirm').value;

        if (!password || password.length < 8) {
            alert('パスワードは8文字以上で入力してください');
            return;
        }
        if (password !== passwordConfirm) {
            alert('パスワードが一致しません');
            return;
        }

        const { data } = await window.fjew.fetchJson('/api/auth/reset-password', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token, password })
        });

        if (data.success) {
            showResetSection('done');
        } else {
            document.getElementById('resetErrorMessage').innerText = data.error || 'パスワードの再設定に失敗しました。';
            showResetSection('error');
        }
    });
}

if (document.readyState === 'loading') {
    window.addEventListener('DOMContentLoaded', initResetPage);
} else {
    initResetPage();
}