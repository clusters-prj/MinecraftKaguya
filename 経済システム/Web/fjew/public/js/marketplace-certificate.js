const ITEM_TYPE_LABELS = {
    world_data: 'ワールドデータ',
    skin: 'スキン',
    media: 'メディア',
    blueprint: '設計図'
};

function getNftId() {
    const params = new URLSearchParams(window.location.search);
    return params.get('nft_id');
}

async function loadCertificate() {
    const user = await window.fjew.requireAuth();
    if (!user) return;

    const nftId = getNftId();
    if (!nftId) {
        alert('アイテムが指定されていません');
        window.location.href = '/my-collection';
        return;
    }

    try {
        const { res, data } = await window.fjew.fetchJson(`/api/marketplace/nfts/${nftId}/certificate`);
        if (!res.ok) throw new Error(data.error || '証明書の取得に失敗しました');

        document.getElementById('certItemType').innerText = ITEM_TYPE_LABELS[data.item_type] || data.item_type;
        document.getElementById('certTitle').innerText = data.title;
        const editionText = data.edition_type === 'unique'
            ? '1点物'
            : data.edition_type === 'limited'
                ? `#${data.serial_number} / ${data.max_editions}`
                : `#${data.serial_number}（無制限発行）`;
        document.getElementById('certSerial').innerText = editionText;
        document.getElementById('certOwner').innerText = `所有者: ${data.owner_name || '不明'}`;
        document.getElementById('certDate').innerText = data.date;
        document.getElementById('certCode').innerText = data.code;
        document.getElementById('certNftId').innerText = data.nft_id;

        document.getElementById('loadingIndicator').classList.add('hidden');
        document.getElementById('certContent').classList.remove('hidden');
    } catch (err) {
        console.error('証明書取得エラー:', err);
        alert('証明書の取得に失敗しました: ' + err.message);
    }
}

document.getElementById('backBtn').addEventListener('click', () => { window.location.href = '/my-collection'; });

window.fjew.onReloadNeeded(loadCertificate);
