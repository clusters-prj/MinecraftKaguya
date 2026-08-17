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
        ? `<p class="text-sm text-gray-700 font-bold">${window.fjew.escapeHtml(java.player_name)}</p><p class="text-xs text-gray-500 font-mono mt-1">UUID: ${window.fjew.escapeHtml(java.uuid)}</p><p class="text-xs text-gray-500 mt-1">残高: ¥${window.fjew.formatYen(java.balance)}</p>`
        : '<p class="text-sm text-gray-500">Java版は未連携です。</p>';

    document.getElementById('bedrockDetail').innerHTML = bedrock
        ? `<p class="text-sm text-gray-700 font-bold">${window.fjew.escapeHtml(bedrock.player_name)}</p><p class="text-xs text-gray-500 font-mono mt-1">UUID: ${window.fjew.escapeHtml(bedrock.uuid)}</p><p class="text-xs text-gray-500 mt-1">残高: ¥${window.fjew.formatYen(bedrock.balance)}</p>`
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
                <p class="font-bold text-gray-700">${window.fjew.escapeHtml(acc.player_name)} <span class="text-gray-400 font-normal">(${acc.type})</span></p>
                <p class="text-gray-400 font-mono">${window.fjew.escapeHtml(acc.uuid)}</p>
            </div>
            <span class="text-gray-600">¥${window.fjew.formatYen(acc.balance)}</span>
        `;
        listEl.appendChild(row);
    }
}

async function loadApiKeys(uuid, container) {
    container.innerHTML = '<p class="text-xs text-gray-400">読み込み中...</p>';
    try {
        const { res, data } = await window.fjew.fetchJson(`/api/corporate-accounts/${uuid}/api-keys`);
        if (!res.ok) throw new Error(data.error || 'APIキー一覧の取得に失敗しました');

        if (data.length === 0) {
            container.innerHTML = '<p class="text-xs text-gray-400">発行済みのAPIキーはありません。</p>';
            return;
        }

        container.innerHTML = '';
        for (const key of data) {
            const row = document.createElement('div');
            row.className = 'flex justify-between items-center text-xs bg-white rounded-lg px-2 py-1.5 border border-gray-100';
            row.innerHTML = `
                <span class="font-mono text-gray-500">${window.fjew.escapeHtml(key.key_name)} ****${window.fjew.escapeHtml(key.key_hint)}</span>
                <button class="text-red-500 hover:text-red-700 font-bold revokeKeyBtn">失効</button>
            `;
            row.querySelector('.revokeKeyBtn').addEventListener('click', async () => {
                if (!confirm('このAPIキーを失効しますか？')) return;
                const { res: delRes, data: delData } = await window.fjew.fetchJson(`/api/corporate-accounts/${uuid}/api-keys/${key.id}`, { method: 'DELETE' });
                if (!delRes.ok) {
                    alert(delData.error || '失効に失敗しました');
                    return;
                }
                loadApiKeys(uuid, container);
            });
            container.appendChild(row);
        }
    } catch (err) {
        container.innerHTML = `<p class="text-xs text-red-500">${window.fjew.escapeHtml(err.message)}</p>`;
    }
}

function renderCorpAccounts(user) {
    const corpAccounts = user.corporate_accounts || [];
    const listEl = document.getElementById('corpAccountList');
    listEl.innerHTML = '';

    if (corpAccounts.length === 0) {
        listEl.innerHTML = '<p class="text-sm text-gray-500">法人口座はまだありません。</p>';
        return;
    }

    for (const acc of corpAccounts) {
        const row = document.createElement('div');
        row.className = 'bg-gray-50 rounded-xl px-3 py-2 space-y-2';
        row.innerHTML = `
            <div class="flex justify-between items-center text-xs">
                <div>
                    <p class="font-bold text-gray-700">${window.fjew.escapeHtml(acc.player_name)}</p>
                    <p class="text-gray-400 font-mono">${window.fjew.escapeHtml(acc.uuid)}</p>
                </div>
                <span class="text-gray-600">¥${window.fjew.formatYen(acc.balance)}</span>
            </div>
            <div class="border-t border-gray-200 pt-2">
                <div class="flex justify-between items-center mb-1">
                    <span class="text-[10px] font-bold text-gray-400">APIキー</span>
                    <button class="text-[10px] fj-navbtn issueKeyBtn">新規発行</button>
                </div>
                <div class="apiKeyList space-y-1"></div>
            </div>
        `;

        const apiKeyListEl = row.querySelector('.apiKeyList');
        row.querySelector('.issueKeyBtn').addEventListener('click', async () => {
            const { res, data } = await window.fjew.fetchJson(`/api/corporate-accounts/${acc.uuid}/api-keys`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ key_name: 'Web発行' })
            });
            if (!res.ok) {
                alert(data.error || 'APIキーの発行に失敗しました');
                return;
            }
            prompt('新しいAPIキーです。この画面を閉じると二度と表示されません。コピーして安全に保管してください。', data.api_key);
            loadApiKeys(acc.uuid, apiKeyListEl);
        });

        listEl.appendChild(row);
        loadApiKeys(acc.uuid, apiKeyListEl);
    }
}

async function loadSettings() {
    try {
        const user = await window.fjew.requireAuth();
        if (!user) return;
        renderLinkStatus(user);
        renderCorpAccounts(user);
    } catch (err) {
        console.error('設定情報取得エラー:', err);
        alert('連携状態の取得に失敗しました: ' + err.message);
    }
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

document.getElementById('createCorpAccountBtn').addEventListener('click', async () => {
    const nameEl = document.getElementById('corpAccountName');
    const name = nameEl.value.trim();
    if (!name) {
        alert('口座名を入力してください');
        return;
    }

    const { data } = await window.fjew.fetchJson('/api/corporate-accounts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name })
    });

    if (data.success) {
        alert(`法人口座「${name}」を作成しました`);
        nameEl.value = '';
        await loadSettings();
    } else {
        alert('作成失敗: ' + data.error);
    }
});

window.fjew.onReloadNeeded(loadSettings);