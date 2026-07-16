let cachedAccounts = [];

function renderMain(user) {
    document.getElementById('webUserEmail').innerText = user.email;

    const accounts = user.accounts || [];
    cachedAccounts = accounts;
    const accountListSection = document.getElementById('accountListSection');
    const accountListEl = document.getElementById('accountList');
    const sendFromEl = document.getElementById('sendFrom');

    accountListEl.innerHTML = '';
    sendFromEl.innerHTML = '';

    if (accounts.length > 0) {
        const totalBalance = accounts.reduce((sum, acc) => sum + Number(acc.balance), 0);
        const primary = accounts[0];

        document.getElementById('userBalance').innerText = window.fjew.formatYen(totalBalance);
        document.getElementById('mcPlayerName').innerText = primary.player_name + (accounts.length > 1 ? ` 他${accounts.length - 1}件` : '');
        document.getElementById('mcStatus').innerText = '連携済み';
        document.getElementById('mcStatus').className = 'bg-green-500 text-white text-xs px-2.5 py-1 rounded-full font-bold backdrop-blur-sm';
        document.getElementById('unlinkedAlert').classList.add('hidden');

        for (const acc of accounts) {
            const option = document.createElement('option');
            option.value = acc.uuid;
            option.textContent = `${acc.player_name} (${acc.type}) - ¥${window.fjew.formatYen(acc.balance)}`;
            sendFromEl.appendChild(option);
        }

        if (accounts.length > 1) {
            accountListSection.classList.remove('hidden');
            for (const acc of accounts) {
                const row = document.createElement('div');
                row.className = 'flex justify-between items-center text-xs bg-gray-50 rounded-xl px-3 py-2';
                row.innerHTML = `
                    <span class="font-bold text-gray-700">${acc.player_name} <span class="text-gray-400 font-normal">(${acc.type})</span></span>
                    <span class="text-gray-600">¥${window.fjew.formatYen(acc.balance)}</span>
                `;
                accountListEl.appendChild(row);
            }
        } else {
            accountListSection.classList.add('hidden');
        }
    } else {
        document.getElementById('userBalance').innerText = '0';
        document.getElementById('mcPlayerName').innerText = '未連携のプレイヤー';
        document.getElementById('mcStatus').innerText = '未連携';
        document.getElementById('mcStatus').className = 'bg-amber-500 text-white text-xs px-2.5 py-1 rounded-full font-bold backdrop-blur-sm';
        document.getElementById('unlinkedAlert').classList.remove('hidden');
        accountListSection.classList.add('hidden');

        const option = document.createElement('option');
        option.textContent = 'アカウントを連携してください';
        option.disabled = true;
        option.selected = true;
        sendFromEl.appendChild(option);
    }
}

async function reloadProfile() {
    try {
        const user = await window.fjew.requireAuth();
        if (!user) return;
        renderMain(user);
    } catch (err) {
        console.error('プロフィール取得エラー:', err);
        alert('プロフィール情報の取得に失敗しました: ' + err.message);
    }
}

document.getElementById('logoutBtn').addEventListener('click', async () => {
    const { data } = await window.fjew.fetchJson('/api/auth/logout', { method: 'POST' });
    if (data.success) window.location.href = '/login';
});

document.getElementById('settingsBtn').addEventListener('click', () => {
    window.location.href = '/settings';
});

document.getElementById('submitLink').addEventListener('click', async () => {
    const code = document.getElementById('linkCode').value;
    const { data } = await window.fjew.fetchJson('/api/auth/link', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code })
    });

    if (data.success) {
        alert(data.message);
        document.getElementById('linkCode').value = '';
        await reloadProfile();
    } else {
        alert('連携失敗: ' + data.error);
    }
});

document.getElementById('submitDebugCode').addEventListener('click', async () => {
    const uuid = document.getElementById('debugUuid').value;
    if (!uuid) return alert('マイクラのUUIDを入力してください');

    const { data } = await window.fjew.fetchJson('/api/debug/generate-code', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ uuid })
    });

    if (data.success) {
        alert(`デバッグコードを発行しました: ${data.code}`);
        document.getElementById('linkCode').value = data.code;
    } else {
        alert('コード発行失敗: ' + data.error);
    }
});

document.getElementById('submitSend').addEventListener('click', async () => {
    const from_uuid = document.getElementById('sendFrom').value;
    const to_player = document.getElementById('sendTo').value;
    const amount = document.getElementById('sendAmount').value;

    if (!cachedAccounts.length || !from_uuid) {
        alert('送金元のアカウントが選択されていません。先にアカウント連携をしてください。');
        return;
    }
    if (!to_player || !amount || amount <= 0) {
        alert('送金先、または金額が正しくありません');
        return;
    }
    if (!confirm(`${to_player} さんに ${amount} 円送金します。本当によろしいですか？`)) return;

    const { data } = await window.fjew.fetchJson('/api/wallet/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ from_uuid, to_player, amount })
    });

    if (data.success) {
        alert(data.message);
        document.getElementById('sendTo').value = '';
        document.getElementById('sendAmount').value = '';
        await reloadProfile();
    } else {
        alert('送金失敗: ' + data.error);
    }
});

window.fjew.onReloadNeeded(reloadProfile);