let selectedEventId = null;
let selectedPredictedUuid = null;

function formatDate(ts) {
    const d = new Date(ts);
    return d.toLocaleString('ja-JP', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit'
    });
}

function renderEventCard(event) {
    const card = document.createElement('div');
    card.className = 'bg-white rounded-2xl shadow-sm p-4 space-y-2';
    const namesText = event.participants.map(p => p.player_name).join(' vs ');
    card.innerHTML = `
        <div class="flex justify-between items-start">
            <div>
                <span class="text-sm font-bold text-gray-800">${event.name}</span>
                <div class="text-xs text-gray-400">${namesText}</div>
            </div>
            <span class="text-xs font-bold bg-red-50 text-red-600 px-2 py-0.5 rounded-full">受付中</span>
        </div>
        <div class="flex justify-between text-xs text-gray-500">
            <span>優勝賞金: ¥${window.fjew.formatYen(event.prize_amount)}</span>
            <span>賭け金プール: ¥${window.fjew.formatYen(event.bet_pool)}</span>
        </div>
        <button class="w-full bg-gray-800 text-white text-sm font-bold py-2 rounded-xl hover:bg-gray-700 transition betBtn">
            この対戦に賭ける
        </button>
    `;
    card.querySelector('.betBtn').addEventListener('click', () => openBetModal(event));
    return card;
}

function openBetModal(event) {
    selectedEventId = event.id;
    selectedPredictedUuid = null;
    document.getElementById('betEventName').textContent = event.name;

    const container = document.getElementById('betParticipants');
    container.innerHTML = '';
    for (const p of event.participants) {
        const btn = document.createElement('button');
        btn.className = 'w-full text-left border rounded-xl px-3 py-2 text-sm font-bold text-gray-700 hover:bg-gray-50 participantBtn';
        btn.textContent = p.player_name;
        btn.dataset.uuid = p.uuid;
        btn.addEventListener('click', () => {
            selectedPredictedUuid = p.uuid;
            container.querySelectorAll('.participantBtn').forEach(b => {
                b.classList.remove('bg-red-50', 'border-red-400', 'text-red-600');
            });
            btn.classList.add('bg-red-50', 'border-red-400', 'text-red-600');
        });
        container.appendChild(btn);
    }

    document.getElementById('betAmount').value = '';
    document.getElementById('betModal').classList.remove('hidden');
}

document.getElementById('closeBetModalBtn').addEventListener('click', () => {
    document.getElementById('betModal').classList.add('hidden');
});
document.getElementById('betModal').addEventListener('click', (e) => {
    if (e.target.id === 'betModal') e.target.classList.add('hidden');
});

document.getElementById('submitBetBtn').addEventListener('click', async () => {
    if (!selectedEventId) return;
    if (!selectedPredictedUuid) {
        alert('予想するプレイヤーを選択してください');
        return;
    }
    const amount = document.getElementById('betAmount').value;
    if (!amount || Number(amount) <= 0) {
        alert('賭け金を正しく入力してください');
        return;
    }

    try {
        const { res, data } = await window.fjew.fetchJson(`/api/arena/events/${selectedEventId}/bet`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ predicted_uuid: selectedPredictedUuid, amount })
        });
        if (!res.ok) throw new Error(data.error || '賭けに失敗しました');

        document.getElementById('betModal').classList.add('hidden');
        alert(data.message || '賭けました');
        loadEvents();
        loadMyBets();
    } catch (err) {
        alert(err.message);
    }
});

function betStatusMeta(status) {
    switch (status) {
        case 'WON': return { label: '的中', color: 'text-green-600', badge: 'bg-green-50 text-green-600' };
        case 'LOST': return { label: '不的中', color: 'text-gray-400', badge: 'bg-gray-100 text-gray-500' };
        case 'REFUNDED': return { label: '払い戻し', color: 'text-blue-600', badge: 'bg-blue-50 text-blue-600' };
        default: return { label: '受付中', color: 'text-red-600', badge: 'bg-red-50 text-red-600' };
    }
}

function renderBetRow(bet) {
    const meta = betStatusMeta(bet.status);
    const row = document.createElement('div');
    row.className = 'bg-white rounded-2xl shadow-sm p-4 flex justify-between items-center';
    row.innerHTML = `
        <div class="flex flex-col gap-0.5">
            <span class="text-xs font-bold ${meta.badge} inline-block px-2 py-0.5 rounded-full w-fit">${meta.label}</span>
            <span class="text-sm font-bold text-gray-800 mt-1">${bet.event_name}</span>
            <span class="text-[10px] text-gray-400">予想: ${bet.predicted_player_name || '(不明)'} ・ ${formatDate(bet.created_at)}</span>
        </div>
        <div class="text-right">
            <span class="text-base font-black ${meta.color}">
                ${bet.status === 'WON' || bet.status === 'REFUNDED' ? `¥${window.fjew.formatYen(bet.payout_amount)}` : `¥${window.fjew.formatYen(bet.amount)}`}
            </span>
        </div>
    `;
    return row;
}

async function loadEvents() {
    try {
        const { res, data } = await window.fjew.fetchJson('/api/arena/events');
        if (!res.ok) throw new Error(data.error || '対戦一覧の取得に失敗しました');

        const listEl = document.getElementById('eventList');
        listEl.innerHTML = '';
        for (const event of data) {
            listEl.appendChild(renderEventCard(event));
        }
        document.getElementById('emptyEvents').classList.toggle('hidden', data.length > 0);
    } catch (err) {
        console.error('対戦一覧取得エラー:', err);
    }
}

async function loadMyBets() {
    try {
        const { res, data } = await window.fjew.fetchJson('/api/arena/my-bets');
        if (!res.ok) throw new Error(data.error || '賭け履歴の取得に失敗しました');

        const listEl = document.getElementById('betList');
        listEl.innerHTML = '';
        for (const bet of data) {
            listEl.appendChild(renderBetRow(bet));
        }
        document.getElementById('emptyBets').classList.toggle('hidden', data.length > 0);
    } catch (err) {
        console.error('賭け履歴取得エラー:', err);
    }
}

document.getElementById('backBtn').addEventListener('click', () => { window.location.href = '/main'; });

document.getElementById('logoutBtn').addEventListener('click', async () => {
    const { data } = await window.fjew.fetchJson('/api/auth/logout', { method: 'POST' });
    if (data.success) window.location.href = '/login';
});

async function init() {
    const user = await window.fjew.requireAuth();
    if (!user) return;
    loadEvents();
    loadMyBets();
}

window.fjew.onReloadNeeded(init);
