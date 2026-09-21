function formatDate(ts) {
    const d = new Date(ts);
    return d.toLocaleString('ja-JP', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit'
    });
}

function statusMeta(status) {
    switch (status) {
        case 'ACTIVE': return { label: '受付中', badge: 'bg-brand-50 text-brand-700' };
        case 'RESOLVED': return { label: '終了', badge: 'bg-green-50 text-green-600' };
        default: return { label: 'キャンセル', badge: 'bg-gray-100 text-gray-500' };
    }
}

function renderEventCard(event) {
    const meta = statusMeta(event.status);
    const namesText = event.participants.map(p => window.fjew.escapeHtml(p.player_name)).join(' vs ');
    const card = document.createElement('div');
    card.className = 'bg-white rounded-2xl shadow-sm p-4 space-y-2';
    card.innerHTML = `
        <div class="flex justify-between items-start">
            <div>
                <span class="text-sm font-bold text-gray-800">${window.fjew.escapeHtml(event.name)}</span>
                <div class="text-xs text-gray-400">${namesText}</div>
                <div class="text-[10px] text-gray-400">${window.fjew.escapeHtml(event.world)} (${event.center_x}, ${event.center_y}, ${event.center_z}) 半径${event.radius}</div>
            </div>
            <span class="text-xs font-bold ${meta.badge} px-2 py-0.5 rounded-full">${meta.label}</span>
        </div>
        <div class="flex justify-between text-xs text-gray-500">
            <span>賞金: ¥${window.fjew.formatYen(event.prize_amount)}</span>
            <span>${formatDate(event.created_at)}</span>
        </div>
        ${event.loadout ? `<div class="text-[10px] text-gray-400">初期装備: ${window.fjew.escapeHtml(event.loadout.split('\n').join(', '))}</div>` : ''}
        ${event.status === 'ACTIVE' ? '<button class="w-full bg-gray-800 text-white text-sm font-bold py-2 rounded-xl hover:bg-gray-700 transition cancelBtn">キャンセル（全額返金）</button>' : ''}
        <button class="w-full border border-gray-300 text-gray-600 text-sm font-bold py-2 rounded-xl hover:bg-gray-50 transition reuseBtn">この試合設定を使いまわす</button>
    `;
    card.querySelector('.reuseBtn').addEventListener('click', () => reuseEvent(event));
    const cancelBtn = card.querySelector('.cancelBtn');
    if (cancelBtn) {
        cancelBtn.addEventListener('click', () => cancelEvent(event.id));
    }
    return card;
}

// 過去の試合の設定（会場・賞金・対戦者・初期装備）を作成フォームへ複写する。内容は編集してから作成できる。
function reuseEvent(event) {
    document.getElementById('fName').value = event.name;
    document.getElementById('fWorld').value = event.world;
    document.getElementById('fX').value = event.center_x;
    document.getElementById('fY').value = event.center_y;
    document.getElementById('fZ').value = event.center_z;
    document.getElementById('fRadius').value = event.radius;
    document.getElementById('fPrize').value = event.prize_amount;
    document.getElementById('fParticipants').value = event.participants.map(p => p.player_name).join(',');
    document.getElementById('fLoadout').value = event.loadout || '';
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

async function cancelEvent(eventId) {
    if (!confirm('このイベントをキャンセルし、ベットを全額返金しますか？')) return;
    try {
        const { res, data } = await window.fjew.fetchJson(`/api/admin/arena/events/${eventId}/cancel`, { method: 'POST' });
        if (!res.ok) throw new Error(data.error || 'キャンセルに失敗しました');
        alert(data.message || 'キャンセルしました');
        loadEvents();
    } catch (err) {
        alert(err.message);
    }
}

async function loadEvents() {
    try {
        const { res, data } = await window.fjew.fetchJson('/api/admin/arena/events');
        if (!res.ok) throw new Error(data.error || 'イベント一覧の取得に失敗しました');

        const listEl = document.getElementById('eventList');
        listEl.innerHTML = '';
        for (const event of data) {
            listEl.appendChild(renderEventCard(event));
        }
    } catch (err) {
        console.error('イベント一覧取得エラー:', err);
    }
}

document.getElementById('createEventBtn').addEventListener('click', async () => {
    const name = document.getElementById('fName').value.trim();
    const world = document.getElementById('fWorld').value.trim();
    const x = document.getElementById('fX').value;
    const y = document.getElementById('fY').value;
    const z = document.getElementById('fZ').value;
    const radius = document.getElementById('fRadius').value;
    const prize_amount = document.getElementById('fPrize').value || '0';
    const participants = document.getElementById('fParticipants').value
        .split(',')
        .map(s => s.trim())
        .filter(s => s.length > 0);
    const loadout = document.getElementById('fLoadout').value;

    if (!name || !world || !radius || participants.length < 2) {
        alert('イベント名・ワールド名・半径・2人以上の対戦プレイヤーを入力してください');
        return;
    }

    try {
        const { res, data } = await window.fjew.fetchJson('/api/admin/arena/events', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, world, x, y, z, radius, prize_amount, participants, loadout })
        });
        if (!res.ok) throw new Error(data.error || 'イベント作成に失敗しました');

        alert('イベントを作成しました');
        document.getElementById('fName').value = '';
        document.getElementById('fWorld').value = '';
        document.getElementById('fX').value = '';
        document.getElementById('fY').value = '';
        document.getElementById('fZ').value = '';
        document.getElementById('fRadius').value = '';
        document.getElementById('fPrize').value = '';
        document.getElementById('fParticipants').value = '';
        document.getElementById('fLoadout').value = '';
        loadEvents();
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

    const { res } = await window.fjew.fetchJson('/api/admin/arena/events');
    if (res.status === 403) {
        document.getElementById('forbidden').classList.remove('hidden');
        return;
    }
    document.getElementById('adminContent').classList.remove('hidden');
    loadEvents();
}

window.fjew.onReloadNeeded(init);
