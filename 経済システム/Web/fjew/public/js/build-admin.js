// 集計はマイクラ側プラグインが非同期で行うため、リクエスト登録後は完了までポーリングする
const POLL_INTERVAL_MS = 2000;
const POLL_TIMEOUT_MS = 5 * 60 * 1000;

let pollTimer = null;

function formatDate(ts) {
    const d = new Date(ts);
    return d.toLocaleString('ja-JP', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit'
    });
}

// datetime-local の value（ローカル時刻の 2026-08-12T15:04）を生成する
function toDateTimeLocal(date) {
    const pad = (n) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
        + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function statusMeta(status) {
    switch (status) {
        case 'PENDING': return { label: '待機中', badge: 'bg-gray-100 text-gray-500' };
        case 'RUNNING': return { label: '集計中', badge: 'bg-blue-50 text-blue-600' };
        case 'DONE': return { label: '完了', badge: 'bg-green-50 text-green-600' };
        default: return { label: 'エラー', badge: 'bg-red-50 text-red-600' };
    }
}

function renderRanking(query) {
    const meta = statusMeta(query.status);
    document.getElementById('resultSection').classList.remove('hidden');
    document.getElementById('resultStatus').textContent = meta.label;
    document.getElementById('resultStatus').className = `text-xs font-bold px-2 py-0.5 rounded-full ${meta.badge}`;
    document.getElementById('resultRange').textContent =
        `${query.server_id} ・ ${formatDate(query.range_start)} 〜 ${formatDate(query.range_end)}`;

    const noteEl = document.getElementById('resultNote');
    if (query.error_message) {
        noteEl.textContent = query.error_message;
        noteEl.classList.remove('hidden');
    } else {
        noteEl.classList.add('hidden');
    }

    const listEl = document.getElementById('ranking');
    listEl.innerHTML = '';
    const ranking = query.ranking || [];

    document.getElementById('rankingEmpty').classList.toggle(
        'hidden', ranking.length > 0 || query.status !== 'DONE');

    ranking.forEach((row, index) => {
        const card = document.createElement('div');
        card.className = 'bg-white rounded-2xl shadow-sm p-3 flex justify-between items-center';
        card.innerHTML = `
            <div class="flex items-center gap-3">
                <span class="text-sm font-black text-gray-400 w-6 text-right">${index + 1}</span>
                <div class="flex flex-col">
                    <span class="text-sm font-bold text-gray-800">${row.player_name}</span>
                    <span class="text-[10px] text-gray-400">設置 ${window.fjew.formatYen(row.blocks_placed)} ・ 破壊 ${window.fjew.formatYen(row.blocks_broken)}</span>
                </div>
            </div>
            <span class="text-base font-black text-red-600">${window.fjew.formatYen(row.score)}</span>
        `;
        listEl.appendChild(card);
    });
}

async function pollQuery(queryId, startedAt) {
    const { res, data } = await window.fjew.fetchJson(`/api/admin/build/queries/${queryId}`);
    if (!res.ok) {
        alert(data.error || '集計結果の取得に失敗しました');
        return;
    }

    renderRanking(data);

    if (data.status === 'DONE' || data.status === 'ERROR') {
        loadQueries();
        return;
    }
    if (Date.now() - startedAt > POLL_TIMEOUT_MS) {
        document.getElementById('resultNote').textContent =
            'マイクラサーバーからの応答がありません。プラグインが起動しているか確認してください。';
        document.getElementById('resultNote').classList.remove('hidden');
        return;
    }
    pollTimer = setTimeout(() => pollQuery(queryId, startedAt), POLL_INTERVAL_MS);
}

async function loadServers() {
    const { res, data } = await window.fjew.fetchJson('/api/admin/build/servers');
    if (!res.ok) return;

    const selectEl = document.getElementById('fServer');
    selectEl.innerHTML = '';
    const servers = data.length > 0 ? data : ['mc1', 'mc2', 'mc3'];
    for (const serverId of servers) {
        const option = document.createElement('option');
        option.value = serverId;
        option.textContent = serverId;
        selectEl.appendChild(option);
    }
}

async function loadQueries() {
    const { res, data } = await window.fjew.fetchJson('/api/admin/build/queries');
    if (!res.ok) return;

    const listEl = document.getElementById('queryList');
    listEl.innerHTML = '';
    for (const query of data) {
        const meta = statusMeta(query.status);
        const card = document.createElement('div');
        card.className = 'bg-white rounded-2xl shadow-sm p-3 flex justify-between items-center cursor-pointer hover:bg-gray-50 transition';
        card.innerHTML = `
            <div class="flex flex-col gap-0.5">
                <span class="text-xs font-bold text-gray-800">${query.server_id}</span>
                <span class="text-[10px] text-gray-400">${formatDate(query.range_start)} 〜 ${formatDate(query.range_end)}</span>
            </div>
            <span class="text-xs font-bold ${meta.badge} px-2 py-0.5 rounded-full">${meta.label}</span>
        `;
        card.addEventListener('click', () => {
            clearTimeout(pollTimer);
            pollQuery(query.id, Date.now());
        });
        listEl.appendChild(card);
    }
}

document.getElementById('runBtn').addEventListener('click', async () => {
    const server_id = document.getElementById('fServer').value;
    const range_start = document.getElementById('fStart').value;
    const range_end = document.getElementById('fEnd').value;

    if (!server_id || !range_start || !range_end) {
        alert('対象サーバーと集計期間を入力してください');
        return;
    }

    try {
        const { res, data } = await window.fjew.fetchJson('/api/admin/build/queries', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ server_id, range_start, range_end })
        });
        if (!res.ok) throw new Error(data.error || '集計リクエストの登録に失敗しました');

        clearTimeout(pollTimer);
        loadQueries();
        pollQuery(data.query_id, Date.now());
    } catch (err) {
        alert(err.message);
    }
});

document.getElementById('backBtn').addEventListener('click', () => { window.location.href = '/main'; });

document.getElementById('logoutBtn').addEventListener('click', async () => {
    const { data } = await window.fjew.fetchJson('/api/auth/logout', { method: 'POST' });
    if (data.success) window.location.href = '/login';
});

async function init() {
    const user = await window.fjew.requireAuth();
    if (!user) return;

    const { res } = await window.fjew.fetchJson('/api/admin/build/queries');
    if (res.status === 403) {
        document.getElementById('forbidden').classList.remove('hidden');
        return;
    }
    document.getElementById('adminContent').classList.remove('hidden');

    const now = new Date();
    document.getElementById('fEnd').value = toDateTimeLocal(now);
    document.getElementById('fStart').value = toDateTimeLocal(new Date(now.getTime() - 24 * 60 * 60 * 1000));

    loadServers();
    loadQueries();
}

window.fjew.onReloadNeeded(init);
