const ITEM_TYPE_LABELS = {
    world_data: 'ワールド',
    skin: 'スキン',
    media: 'メディア'
};

function renderCard(nft) {
    const card = document.createElement('div');
    card.className = 'bg-white rounded-2xl shadow-sm overflow-hidden';

    card.innerHTML = `
        <div class="aspect-square bg-gray-100 flex items-center justify-center overflow-hidden">
            ${nft.preview_image_path
                ? `<img src="${nft.preview_image_path}" alt="" class="w-full h-full object-cover">`
                : `<span class="text-3xl text-gray-300">${ITEM_TYPE_LABELS[nft.item_type]?.[0] || '?'}</span>`}
        </div>
        <div class="p-3 space-y-1">
            <span class="text-[10px] font-bold px-1.5 py-0.5 rounded-full bg-brand-50 text-brand-700">${ITEM_TYPE_LABELS[nft.item_type] || nft.item_type}</span>
            <p class="text-sm font-bold text-gray-800 truncate">${window.fjew.escapeHtml(nft.title)}</p>
            <p class="text-[10px] text-gray-400">#${nft.serial_number}${nft.edition_type === 'limited' ? ` / ${nft.max_editions}` : ''}</p>
            <a href="/api/marketplace/nfts/${nft.nft_id}/download" target="_blank" rel="noopener" class="block text-center fj-btn-primary py-2 text-xs mt-2">開く / ダウンロード</a>
        </div>
    `;
    return card;
}

async function loadCollection() {
    const user = await window.fjew.requireAuth();
    if (!user) return;

    document.getElementById('loadingIndicator').classList.remove('hidden');
    const gridEl = document.getElementById('collectionGrid');
    gridEl.innerHTML = '';

    try {
        const { res, data } = await window.fjew.fetchJson('/api/marketplace/my-collection');
        if (!res.ok) throw new Error(data.error || 'コレクションの取得に失敗しました');

        if (data.length === 0) {
            document.getElementById('emptyState').classList.remove('hidden');
            return;
        }
        document.getElementById('emptyState').classList.add('hidden');
        for (const nft of data) {
            gridEl.appendChild(renderCard(nft));
        }
    } catch (err) {
        console.error('コレクション取得エラー:', err);
        alert('コレクションの取得に失敗しました: ' + err.message);
    } finally {
        document.getElementById('loadingIndicator').classList.add('hidden');
    }
}

document.getElementById('backBtn').addEventListener('click', () => { window.location.href = '/marketplace'; });

window.fjew.onReloadNeeded(loadCollection);
