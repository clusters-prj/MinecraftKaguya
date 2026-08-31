let accounts = [];

function renderAccountOptions() {
    const select = document.getElementById('buyerSelect');
    select.innerHTML = accounts
        .map(a => `<option value="${a.uuid}">${window.fjew.escapeHtml(a.player_name)} (¥${window.fjew.formatYen(a.balance)})</option>`)
        .join('');
}

function renderCard(pet) {
    const card = document.createElement('div');
    card.className = 'bg-white rounded-2xl shadow-sm overflow-hidden p-3 space-y-2';
    card.innerHTML = `
        <p class="text-sm font-bold text-gray-800 truncate">${window.fjew.escapeHtml(pet.display_name)}</p>
        <p class="text-sm font-black text-brand-700">¥${window.fjew.formatYen(pet.tame_cost)}</p>
        <button class="buyBtn w-full text-xs font-bold py-2 rounded-xl bg-brand-600 text-white hover:bg-brand-700 transition">購入する</button>
    `;
    card.querySelector('.buyBtn').addEventListener('click', () => purchase(pet));
    return card;
}

async function purchase(pet) {
    const buyerUuid = document.getElementById('buyerSelect').value;
    if (!buyerUuid) {
        alert('購入元のアカウントを選択してください');
        return;
    }
    if (!confirm(`「${pet.display_name}」を¥${window.fjew.formatYen(pet.tame_cost)}で購入しますか?`)) return;

    try {
        const { res, data } = await window.fjew.fetchJson('/api/pets/purchase', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ mob_type: pet.mob_type, buyer_uuid: buyerUuid })
        });
        if (!res.ok) throw new Error(data.error || '購入に失敗しました');
        alert(data.message || '購入しました');
        await loadCatalog();
    } catch (err) {
        alert('購入に失敗しました: ' + err.message);
    }
}

async function loadCatalog() {
    document.getElementById('loadingIndicator').classList.remove('hidden');
    document.getElementById('emptyState').classList.add('hidden');
    const gridEl = document.getElementById('catalogGrid');
    gridEl.innerHTML = '';

    try {
        const { res, data } = await window.fjew.fetchJson('/api/pets/catalog');
        if (!res.ok) throw new Error(data.error || 'カタログの取得に失敗しました');

        if (data.length === 0) {
            document.getElementById('emptyState').classList.remove('hidden');
            return;
        }
        for (const pet of data) {
            gridEl.appendChild(renderCard(pet));
        }
    } catch (err) {
        console.error('カタログ取得エラー:', err);
        alert('カタログの取得に失敗しました: ' + err.message);
    } finally {
        document.getElementById('loadingIndicator').classList.add('hidden');
    }
}

async function init() {
    const user = await window.fjew.requireAuth();
    if (!user) return;
    accounts = user.accounts || [];
    if (accounts.length === 0) {
        alert('マイクラアカウントが連携されていません。設定画面から連携してください');
        window.location.href = '/settings';
        return;
    }
    renderAccountOptions();
    await loadCatalog();
}

document.getElementById('backBtn').addEventListener('click', () => { window.location.href = '/main'; });

window.fjew.onReloadNeeded(init);
