let currentType = '';

const ITEM_TYPE_LABELS = {
    world_data: 'ワールド',
    skin: 'スキン',
    media: 'メディア'
};

function editionLabel(listing) {
    if (listing.edition_type === 'unique') return '1点物';
    if (listing.edition_type === 'unlimited') return '無制限';
    return `残り${listing.remaining} / ${listing.max_editions}`;
}

function renderCard(listing) {
    const card = document.createElement('a');
    card.href = `/marketplace-item?id=${listing.id}`;
    card.className = 'block bg-white rounded-2xl shadow-sm overflow-hidden hover:shadow-md transition';

    const soldOut = listing.edition_type !== 'unlimited' && listing.remaining <= 0;

    card.innerHTML = `
        <div class="aspect-square bg-gray-100 flex items-center justify-center overflow-hidden">
            ${listing.preview_image_path
                ? `<img src="${listing.preview_image_path}" alt="" class="w-full h-full object-cover">`
                : `<span class="text-3xl text-gray-300">${ITEM_TYPE_LABELS[listing.item_type]?.[0] || '?'}</span>`}
        </div>
        <div class="p-3 space-y-1">
            <span class="text-[10px] font-bold px-1.5 py-0.5 rounded-full bg-brand-50 text-brand-700">${ITEM_TYPE_LABELS[listing.item_type] || listing.item_type}</span>
            <p class="text-sm font-bold text-gray-800 truncate">${window.fjew.escapeHtml(listing.title)}</p>
            <p class="text-[10px] text-gray-400 truncate">出品者: ${window.fjew.escapeHtml(listing.seller_name || '不明')}</p>
            <div class="flex justify-between items-center pt-1">
                <span class="text-sm font-black text-brand-700">¥${window.fjew.formatYen(listing.price)}</span>
                <span class="text-[10px] ${soldOut ? 'text-red-500 font-bold' : 'text-gray-400'}">${soldOut ? '完売' : editionLabel(listing)}</span>
            </div>
        </div>
    `;
    return card;
}

async function loadListings() {
    document.getElementById('loadingIndicator').classList.remove('hidden');
    document.getElementById('emptyState').classList.add('hidden');
    const gridEl = document.getElementById('listingGrid');
    gridEl.innerHTML = '';

    try {
        const query = currentType ? `?item_type=${encodeURIComponent(currentType)}` : '';
        const { res, data } = await window.fjew.fetchJson(`/api/marketplace/listings${query}`);
        if (!res.ok) throw new Error(data.error || '出品一覧の取得に失敗しました');

        if (data.length === 0) {
            document.getElementById('emptyState').classList.remove('hidden');
            return;
        }
        for (const listing of data) {
            gridEl.appendChild(renderCard(listing));
        }
    } catch (err) {
        console.error('出品一覧取得エラー:', err);
        alert('出品一覧の取得に失敗しました: ' + err.message);
    } finally {
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
        loadListings();
    });
});

document.getElementById('backBtn').addEventListener('click', () => { window.location.href = '/main'; });
document.getElementById('myCollectionBtn').addEventListener('click', () => { window.location.href = '/my-collection'; });
document.getElementById('sellBtn').addEventListener('click', () => { window.location.href = '/marketplace-sell'; });

window.fjew.onReloadNeeded(loadListings);
