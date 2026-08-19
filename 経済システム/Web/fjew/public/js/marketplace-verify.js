const ITEM_TYPE_LABELS = {
    world_data: 'ワールドデータ',
    skin: 'スキン',
    media: 'メディア'
};

document.getElementById('verifyForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const nftId = document.getElementById('nftIdInput').value.trim();
    const code = document.getElementById('codeInput').value.trim();
    const resultArea = document.getElementById('resultArea');
    resultArea.innerHTML = '<div class="text-center text-xs text-gray-400 py-4">確認中...</div>';

    try {
        const { res, data } = await window.fjew.fetchJson(
            `/api/marketplace/certificate/verify?nft_id=${encodeURIComponent(nftId)}&code=${encodeURIComponent(code)}`
        );
        if (!res.ok) throw new Error(data.error || '確認に失敗しました');

        if (data.valid) {
            resultArea.innerHTML = `
                <div class="bg-green-50 border border-green-200 rounded-2xl p-6 text-center space-y-2">
                    <p class="text-green-700 font-black text-lg">✔ 本物です（${window.fjew.escapeHtml(data.checked_date)}時点）</p>
                    <p class="text-sm text-gray-700 font-bold">${window.fjew.escapeHtml(data.title)}</p>
                    <p class="text-xs text-gray-500">${ITEM_TYPE_LABELS[data.item_type] || window.fjew.escapeHtml(data.item_type)} ・ #${data.serial_number}${data.edition_type === 'limited' ? ` / ${data.max_editions}` : ''}</p>
                    <p class="text-xs text-gray-500">所有者: ${window.fjew.escapeHtml(data.owner_name || '不明')}</p>
                </div>
            `;
        } else {
            resultArea.innerHTML = `
                <div class="bg-red-50 border border-red-200 rounded-2xl p-6 text-center space-y-1">
                    <p class="text-red-600 font-black text-lg">✘ コードが一致しません</p>
                    <p class="text-xs text-gray-500">${data.reason ? window.fjew.escapeHtml(data.reason) : '古いスクリーンショットか、偽造の可能性があります。'}</p>
                </div>
            `;
        }
    } catch (err) {
        resultArea.innerHTML = `<div class="bg-red-50 border border-red-200 rounded-2xl p-6 text-center text-xs text-red-600">${window.fjew.escapeHtml(err.message)}</div>`;
    }
});

document.getElementById('backBtn').addEventListener('click', () => { window.location.href = '/marketplace'; });
