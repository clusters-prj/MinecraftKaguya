function renderLinkStatus(user) {
    const accounts = user.accounts || [];
    const java = accounts.find(acc => (acc.type || '').toLowerCase() === 'java');
    const bedrock = accounts.find(acc => (acc.type || '').toLowerCase() === 'bedrock');

    document.getElementById('javaStatus').innerText = java ? '連携済み' : '未連携';
    document.getElementById('javaStatus').className = java
        ? 'inline-block text-xs font-bold px-2 py-1 rounded-full bg-green-100 text-green-700'
        : 'inline-block text-xs font-bold px-2 py-1 rounded-full bg-amber-100 text-amber-700';

    document.getElementById('bedrockStatus').innerText = bedrock ? '連携済み' : '未連携';
    document.getElementById('bedrockStatus').className = bedrock
        ? 'inline-block text-xs font-bold px-2 py-1 rounded-full bg-green-100 text-green-700'
        : 'inline-block text-xs font-bold px-2 py-1 rounded-full bg-amber-100 text-amber-700';

    document.getElementById('javaDetail').innerHTML = java
        ? `<p class="text-sm text-gray-700 font-bold">${java.player_name}</p><p class="text-xs text-gray-500 font-mono mt-1">UUID: ${java.uuid}</p><p class="text-xs text-gray-500 mt-1">残高: ¥${window.fjew.formatYen(java.balance)}</p>`
        : '<p class="text-sm text-gray-500">Java版は未連携です。</p>';

    document.getElementById('bedrockDetail').innerHTML = bedrock
        ? `<p class="text-sm text-gray-700 font-bold">${bedrock.player_name}</p><p class="text-xs text-gray-500 font-mono mt-1">UUID: ${bedrock.uuid}</p><p class="text-xs text-gray-500 mt-1">残高: ¥${window.fjew.formatYen(bedrock.balance)}</p>`
        : '<p class="text-sm text-gray-500">統合版は未連携です。</p>';

    const listEl = document.getElementById('linkedAccountList');
    listEl.innerHTML = '';
    if (accounts.length === 0) {
        listEl.innerHTML = '<p class="text-sm text-gray-500">連携済みアカウントはありません。メインページで連携コードを入力してください。</p>';
        return;
    }

    for (const acc of accounts) {
        const row = document.createElement('div');
        row.className = 'flex justify-between items-center text-xs bg-gray-50 rounded-xl px-3 py-2';
        row.innerHTML = `
            <div>
                <p class="font-bold text-gray-700">${acc.player_name} <span class="text-gray-400 font-normal">(${acc.type})</span></p>
                <p class="text-gray-400 font-mono">${acc.uuid}</p>
            </div>
            <span class="text-gray-600">¥${window.fjew.formatYen(acc.balance)}</span>
        `;
        listEl.appendChild(row);
    }
}

async function loadSettings() {
    const user = await window.fjew.requireAuth();
    if (!user) return;
    renderLinkStatus(user);
}

document.getElementById('toMainBtn').addEventListener('click', () => {
    window.location.href = '/main';
});

document.getElementById('logoutBtn').addEventListener('click', async () => {
    const { data } = await window.fjew.fetchJson('/api/auth/logout', { method: 'POST' });
    if (data.success) window.location.href = '/login';
});

document.getElementById('reloadStatusBtn').addEventListener('click', loadSettings);

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
        await loadSettings();
    } else {
        alert('連携失敗: ' + data.error);
    }
});

window.fjew.onReloadNeeded(loadSettings);