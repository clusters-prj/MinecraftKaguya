const ITEM_TYPE_LABELS = {
    world_data: 'ワールドデータ',
    skin: 'スキン',
    media: 'メディア',
    blueprint: '設計図'
};

function getListingId() {
    const params = new URLSearchParams(window.location.search);
    return params.get('id');
}

function editionLabel(listing) {
    if (listing.edition_type === 'unique') return '1点物';
    if (listing.edition_type === 'unlimited') return '無制限発行';
    return `残り ${listing.remaining} / ${listing.max_editions}`;
}

function renderPreview(listing) {
    const area = document.getElementById('previewArea');
    if (listing.preview_image_path) {
        area.innerHTML = `<img src="${listing.preview_image_path}" alt="" class="w-full h-full object-cover">`;
    } else {
        area.innerHTML = `<span class="text-4xl text-gray-300">${ITEM_TYPE_LABELS[listing.item_type]?.[0] || '?'}</span>`;
    }
}

function renderEditions(editions) {
    const listEl = document.getElementById('editionList');
    listEl.innerHTML = '';
    if (editions.length === 0) {
        listEl.innerHTML = '<p class="text-xs text-gray-400">まだ誰も購入していません。</p>';
        return;
    }
    for (const edition of editions) {
        const row = document.createElement('div');
        row.className = 'flex justify-between items-center text-xs bg-gray-50 rounded-xl px-3 py-2';
        row.innerHTML = `
            <span class="font-bold text-gray-700">#${edition.serial_number}</span>
            <span class="text-gray-500">${window.fjew.escapeHtml(edition.owner_name || '不明')}</span>
        `;
        listEl.appendChild(row);
    }
}

async function loadItem() {
    const listingId = getListingId();
    if (!listingId) {
        alert('アイテムが指定されていません');
        window.location.href = '/marketplace';
        return;
    }

    try {
        const { res, data } = await window.fjew.fetchJson(`/api/marketplace/listings/${listingId}`);
        if (!res.ok) throw new Error(data.error || 'アイテム情報の取得に失敗しました');

        document.getElementById('itemTypeBadge').innerText = ITEM_TYPE_LABELS[data.item_type] || data.item_type;
        document.getElementById('itemTitle').innerText = data.title;
        document.getElementById('itemPrice').innerText = `¥${window.fjew.formatYen(data.price)}`;
        document.getElementById('itemDescription').innerText = data.description || '';
        document.getElementById('itemSeller').innerText = data.seller_name || '不明';

        const soldOut = data.edition_type !== 'unlimited' && data.remaining <= 0;
        const stockEl = document.getElementById('itemStock');
        stockEl.innerText = soldOut ? '完売' : editionLabel(data);
        stockEl.className = soldOut ? 'font-bold text-red-500' : 'font-bold text-gray-600';

        renderPreview(data);
        renderEditions(data.editions);

        const purchaseBtn = document.getElementById('purchaseBtn');
        if (soldOut) {
            purchaseBtn.disabled = true;
            purchaseBtn.innerText = '完売しました';
        }

        // ログイン中なら購入元アカウントの選択肢を出す（未ログインでも閲覧自体は可能）
        const user = await window.fjew.getCurrentUser();
        const buyerSelect = document.getElementById('buyerAccount');
        if (!user) {
            buyerSelect.innerHTML = '<option>ログインが必要です</option>';
            purchaseBtn.disabled = true;
            if (!soldOut) purchaseBtn.innerText = 'ログインして購入する';
            purchaseBtn.onclick = () => { window.location.href = '/login'; };
        } else {
            const accounts = [...(user.accounts || []), ...(user.corporate_accounts || [])];
            if (accounts.length === 0) {
                buyerSelect.innerHTML = '<option>連携済みアカウントがありません</option>';
                purchaseBtn.disabled = true;
            } else {
                buyerSelect.innerHTML = '';
                for (const acc of accounts) {
                    const option = document.createElement('option');
                    option.value = acc.uuid;
                    option.textContent = `${acc.player_name} (${acc.type}) - ¥${window.fjew.formatYen(acc.balance)}`;
                    buyerSelect.appendChild(option);
                }
                if (!soldOut) {
                    purchaseBtn.onclick = () => purchaseItem(listingId, data.title);
                }
            }
        }

        document.getElementById('loadingIndicator').classList.add('hidden');
        document.getElementById('itemContent').classList.remove('hidden');
    } catch (err) {
        console.error('アイテム取得エラー:', err);
        alert('アイテム情報の取得に失敗しました: ' + err.message);
    }
}

async function purchaseItem(listingId, title) {
    const buyerUuid = document.getElementById('buyerAccount').value;
    if (!confirm(`「${title}」を購入します。よろしいですか？`)) return;

    const { data } = await window.fjew.fetchJson(`/api/marketplace/listings/${listingId}/purchase`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ buyer_uuid: buyerUuid })
    });

    if (data.success) {
        alert(data.message);
        window.location.href = '/my-collection';
    } else {
        alert('購入失敗: ' + data.error);
    }
}

document.getElementById('backBtn').addEventListener('click', () => { window.location.href = '/marketplace'; });

window.fjew.onReloadNeeded(loadItem);
