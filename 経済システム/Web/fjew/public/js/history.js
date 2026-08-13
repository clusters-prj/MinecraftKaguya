let currentType = 'all';
let currentPage = 1;
let isLoading = false;
let hasMore = true;
let allLoadedTx = []; // 詳細モーダル表示用にキャッシュ

function formatDate(ts) {
    const d = new Date(ts);
    return d.toLocaleString('ja-JP', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit'
    });
}

function directionMeta(direction) {
    switch (direction) {
        case 'in':
            return { sign: '+', color: 'text-green-600', badge: 'bg-green-50 text-green-600', label: '受取' };
        case 'out':
            return { sign: '-', color: 'text-red-600', badge: 'bg-red-50 text-red-600', label: '送金' };
        default:
            return { sign: '', color: 'text-gray-500', badge: 'bg-gray-100 text-gray-500', label: '内部移動' };
    }
}

function typeLabelOf(tx, withItem = false) {
    if (tx.type === 'transfer') return '個人送金';
    if (tx.type === 'build_reward') return '建築ボーナス';
    return withItem ? `ショップ取引 (${window.fjew.escapeHtml(tx.item_id)})` : 'ショップ取引';
}

function renderRow(tx) {
    const meta = directionMeta(tx.direction);
    const typeLabel = typeLabelOf(tx, true);

    const row = document.createElement('div');
    row.className = 'bg-white rounded-2xl shadow-sm p-4 flex justify-between items-center cursor-pointer hover:bg-gray-50 transition';
    row.dataset.txId = tx.id;
    row.innerHTML = `
        <div class="flex flex-col gap-0.5">
            <span class="text-xs font-bold ${meta.badge} inline-block px-2 py-0.5 rounded-full w-fit">${meta.label}</span>
            <span class="text-sm font-bold text-gray-800 mt-1">${window.fjew.escapeHtml(tx.counterpart_name)}</span>
            <span class="text-[10px] text-gray-400">${typeLabel} ・ ${formatDate(tx.timestamp)}</span>
        </div>
        <div class="text-right">
            <span class="text-base font-black ${meta.color}">${meta.sign}¥${window.fjew.formatYen(tx.amount)}</span>
        </div>
    `;
    row.addEventListener('click', () => showDetail(tx));
    return row;
}

function showDetail(tx) {
    const meta = directionMeta(tx.direction);
    const content = document.getElementById('detailContent');
    content.innerHTML = `
        <div class="flex justify-between"><span class="text-gray-400">日時</span><span class="font-bold text-gray-700">${formatDate(tx.timestamp)}</span></div>
        <div class="flex justify-between"><span class="text-gray-400">種別</span><span class="font-bold text-gray-700">${typeLabelOf(tx)}</span></div>
        <div class="flex justify-between"><span class="text-gray-400">相手</span><span class="font-bold text-gray-700">${window.fjew.escapeHtml(tx.counterpart_name)}</span></div>
        <div class="flex justify-between"><span class="text-gray-400">サーバー</span><span class="font-bold text-gray-700">${window.fjew.escapeHtml(tx.server_id)}</span></div>
        ${tx.type === 'shop' ? `
        <div class="flex justify-between"><span class="text-gray-400">アイテム</span><span class="font-bold text-gray-700">${window.fjew.escapeHtml(tx.item_id)}</span></div>
        <div class="flex justify-between"><span class="text-gray-400">税額</span><span class="font-bold text-gray-700">¥${window.fjew.formatYen(tx.tax_amount)}</span></div>
        ` : ''}
        <div class="flex justify-between border-t pt-3 mt-3"><span class="text-gray-500 font-bold">金額</span><span class="font-black text-lg ${meta.color}">${meta.sign}¥${window.fjew.formatYen(tx.amount)}</span></div>
    `;
    document.getElementById('detailModal').classList.remove('hidden');
}

document.getElementById('closeModalBtn').addEventListener('click', () => {
    document.getElementById('detailModal').classList.add('hidden');
});
document.getElementById('detailModal').addEventListener('click', (e) => {
    if (e.target.id === 'detailModal') e.target.classList.add('hidden');
});

async function loadHistory(reset = false) {
    if (isLoading) return;
    if (reset) {
        currentPage = 1;
        hasMore = true;
        allLoadedTx = [];
        document.getElementById('historyList').innerHTML = '';
        document.getElementById('emptyState').classList.add('hidden');
    }
    if (!hasMore) return;

    isLoading = true;
    document.getElementById('loadingIndicator').classList.remove('hidden');
    document.getElementById('loadMoreBtn').classList.add('hidden');

    try {
        const { res, data } = await window.fjew.fetchJson(
            `/api/wallet/history?type=${currentType}&page=${currentPage}&limit=20`
        );
        if (!res.ok) throw new Error(data.error || '履歴の取得に失敗しました');

        allLoadedTx = allLoadedTx.concat(data.transactions);
        const listEl = document.getElementById('historyList');
        for (const tx of data.transactions) {
            listEl.appendChild(renderRow(tx));
        }

        hasMore = data.has_more;
        currentPage += 1;

        if (allLoadedTx.length === 0) {
            document.getElementById('emptyState').classList.remove('hidden');
        }
        document.getElementById('loadMoreBtn').classList.toggle('hidden', !hasMore);
    } catch (err) {
        console.error('履歴取得エラー:', err);
        alert('履歴の取得に失敗しました: ' + err.message);
    } finally {
        isLoading = false;
        document.getElementById('loadingIndicator').classList.add('hidden');
    }
}

document.querySelectorAll('.filterTab').forEach(btn => {
    btn.addEventListener('click', () => {
        if (btn.dataset.type === currentType) return;
        document.querySelectorAll('.filterTab').forEach(b => {
            b.classList.remove('bg-brand-600', 'text-white');
            b.classList.add('text-gray-500');
        });
        btn.classList.add('bg-brand-600', 'text-white');
        btn.classList.remove('text-gray-500');
        currentType = btn.dataset.type;
        loadHistory(true);
    });
});

document.getElementById('loadMoreBtn').addEventListener('click', () => loadHistory(false));
document.getElementById('backBtn').addEventListener('click', () => { window.location.href = '/main'; });

document.getElementById('logoutBtn').addEventListener('click', async () => {
    const { data } = await window.fjew.fetchJson('/api/auth/logout', { method: 'POST' });
    if (data.success) window.location.href = '/login';
});

async function init() {
    const user = await window.fjew.requireAuth();
    if (!user) return;
    loadHistory(true);
}

window.fjew.onReloadNeeded(init);