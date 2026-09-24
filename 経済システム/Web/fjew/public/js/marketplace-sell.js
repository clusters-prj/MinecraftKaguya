const STATUS_LABELS = {
    active: { text: '公開中', cls: 'bg-green-100 text-green-700' },
    paused: { text: '一時停止', cls: 'bg-amber-100 text-amber-700' },
    removed: { text: '削除済み', cls: 'bg-gray-100 text-gray-500' }
};

function toggleMaxEditionsField() {
    const editionType = document.getElementById('editionType').value;
    document.getElementById('maxEditionsField').classList.toggle('hidden', editionType !== 'limited');
}

document.getElementById('editionType').addEventListener('change', toggleMaxEditionsField);
toggleMaxEditionsField();

// ツール(item_type='tool')はファイルアップロードの代わりに、管理者が登録済みのtool_codeを選ぶ。
let toolCatalogLoaded = false;
async function loadToolCatalog() {
    if (toolCatalogLoaded) return;
    toolCatalogLoaded = true;
    try {
        const { res, data } = await window.fjew.fetchJson('/api/marketplace/tool-catalog');
        if (!res.ok) throw new Error(data.error || 'ツール一覧の取得に失敗しました');
        const select = document.getElementById('toolCode');
        select.innerHTML = '';
        if (data.length === 0) {
            select.innerHTML = '<option value="">現在出品できるツールがありません</option>';
            return;
        }
        for (const tool of data) {
            const option = document.createElement('option');
            option.value = tool.tool_code;
            option.textContent = tool.description ? `${tool.display_name}（${tool.description}）` : tool.display_name;
            select.appendChild(option);
        }
    } catch (err) {
        console.error('ツール一覧取得エラー:', err);
    }
}

function toggleItemTypeFields() {
    const itemType = document.getElementById('itemType').value;
    const isTool = itemType === 'tool';
    document.getElementById('toolCodeField').classList.toggle('hidden', !isTool);
    document.getElementById('fileInputField').classList.toggle('hidden', isTool);
    document.getElementById('fileInput').required = !isTool;
    document.getElementById('toolCode').required = isTool;
    document.getElementById('blueprintPreviewBtn').classList.toggle('hidden', itemType !== 'blueprint');
    if (isTool) loadToolCatalog();
}

document.getElementById('itemType').addEventListener('change', toggleItemTypeFields);
toggleItemTypeFields();

// 出品前に、選択した設計図JSONの中身をその場でプレビューできるようにする(サーバーには送らない)
document.getElementById('blueprintPreviewBtn').addEventListener('click', async () => {
    const fileInput = document.getElementById('fileInput');
    const file = fileInput.files[0];
    if (!file) {
        alert('先に設計図ファイル（.json）を選択してください');
        return;
    }
    try {
        const text = await file.text();
        const parsed = JSON.parse(text);
        if (!parsed || !Array.isArray(parsed.blocks)) {
            throw new Error('{ name, blocks: [...] } の形式が必要です');
        }
        // window.openに'noopener'を付けると新しいタブがopenerから完全に切り離され、
        // sessionStorageは共有されない(別の記憶域になる)ため、タブ間で共有されるlocalStorageを使う
        localStorage.setItem('fj_blueprint_preview', JSON.stringify(parsed));
        window.open('/marketplace-preview?source=session', '_blank', 'noopener');
    } catch (err) {
        alert('プレビューできませんでした: ' + err.message);
    }
});

async function loadSellerAccounts() {
    const user = await window.fjew.requireAuth();
    if (!user) return;

    const accounts = [...(user.accounts || []), ...(user.corporate_accounts || [])];
    const select = document.getElementById('sellerAccount');
    select.innerHTML = '';
    if (accounts.length === 0) {
        select.innerHTML = '<option value="">連携済みアカウントがありません</option>';
        document.getElementById('submitBtn').disabled = true;
        return;
    }
    for (const acc of accounts) {
        const option = document.createElement('option');
        option.value = acc.uuid;
        option.textContent = `${acc.player_name} (${acc.type})`;
        select.appendChild(option);
    }
}

function renderMyListings(listings) {
    const listEl = document.getElementById('myListings');
    listEl.innerHTML = '';
    if (listings.length === 0) {
        listEl.innerHTML = '<p class="text-sm text-gray-500">まだ出品していません。</p>';
        return;
    }
    for (const listing of listings) {
        const status = STATUS_LABELS[listing.status] || STATUS_LABELS.active;
        const row = document.createElement('div');
        row.className = 'bg-gray-50 rounded-xl px-3 py-2 space-y-1';
        row.innerHTML = `
            <div class="flex justify-between items-center text-xs">
                <span class="font-bold text-gray-700">${window.fjew.escapeHtml(listing.title)}</span>
                <span class="px-2 py-0.5 rounded-full font-bold ${status.cls}">${status.text}</span>
            </div>
            <div class="flex justify-between items-center text-[10px] text-gray-400">
                <span>¥${window.fjew.formatYen(listing.price)} ・ 発行数 ${listing.minted_count}${listing.edition_type === 'limited' ? ` / ${listing.max_editions}` : ''}</span>
                <div class="flex gap-2"></div>
            </div>
        `;
        const actionsEl = row.querySelector('.flex.gap-2');
        if (listing.status === 'active') {
            actionsEl.appendChild(makeActionButton('一時停止', () => updateStatus(listing.id, 'paused')));
        } else if (listing.status === 'paused') {
            actionsEl.appendChild(makeActionButton('再開', () => updateStatus(listing.id, 'active')));
        }
        if (listing.status !== 'removed') {
            actionsEl.appendChild(makeActionButton('削除', () => {
                if (confirm('この出品を削除しますか？（購入済みの人はダウンロードを続けられます）')) {
                    updateStatus(listing.id, 'removed');
                }
            }));
        }
        listEl.appendChild(row);
    }
}

function makeActionButton(label, onClick) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'fj-navbtn text-[10px]';
    btn.innerText = label;
    btn.addEventListener('click', onClick);
    return btn;
}

async function updateStatus(listingId, status) {
    const { data } = await window.fjew.fetchJson(`/api/marketplace/listings/${listingId}/status`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status })
    });
    if (!data.success) {
        alert(data.error || '更新に失敗しました');
        return;
    }
    loadMyListings();
}

async function loadMyListings() {
    try {
        const { res, data } = await window.fjew.fetchJson('/api/marketplace/my-listings');
        if (!res.ok) throw new Error(data.error || '出品一覧の取得に失敗しました');
        renderMyListings(data);
    } catch (err) {
        console.error('自分の出品取得エラー:', err);
    }
}

document.getElementById('sellForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const submitBtn = document.getElementById('submitBtn');
    submitBtn.disabled = true;
    submitBtn.innerText = '出品中...';

    try {
        // multerはmultipartストリームを順に処理するため、item_type等のフィールドは
        // file/preview_imageより前に来るようappendの順序を守ること
        const form = document.getElementById('sellForm');
        const formData = new FormData();
        formData.append('seller_uuid', document.getElementById('sellerAccount').value);
        formData.append('item_type', document.getElementById('itemType').value);
        formData.append('title', document.getElementById('title').value);
        formData.append('description', document.getElementById('description').value);
        formData.append('price', document.getElementById('price').value);
        formData.append('edition_type', document.getElementById('editionType').value);
        if (document.getElementById('editionType').value === 'limited') {
            formData.append('max_editions', document.getElementById('maxEditions').value);
        }
        const isTool = document.getElementById('itemType').value === 'tool';
        if (isTool) {
            formData.append('tool_code', document.getElementById('toolCode').value);
        } else {
            const fileInput = document.getElementById('fileInput');
            if (fileInput.files[0]) formData.append('file', fileInput.files[0]);
        }
        const previewInput = document.getElementById('previewInput');
        if (previewInput.files[0]) formData.append('preview_image', previewInput.files[0]);

        const res = await fetch('/api/marketplace/listings', { method: 'POST', body: formData });
        const data = await res.json().catch(() => ({}));

        if (data.success) {
            alert('出品しました');
            form.reset();
            toggleMaxEditionsField();
            toggleItemTypeFields();
            loadMyListings();
        } else {
            alert('出品失敗: ' + (data.error || '不明なエラー'));
        }
    } catch (err) {
        alert('出品に失敗しました: ' + err.message);
    } finally {
        submitBtn.disabled = false;
        submitBtn.innerText = '出品する';
    }
});

document.getElementById('backBtn').addEventListener('click', () => { window.location.href = '/marketplace'; });

window.fjew.onReloadNeeded(async () => {
    await loadSellerAccounts();
    await loadMyListings();
});
