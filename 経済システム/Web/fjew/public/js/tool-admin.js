// このページはYAML/Skriptのテキストを生成するだけで、DBやサーバーのファイルには一切書き込まない
// (fjewとマイクラサーバーは別マシンのため、物理的に書き込めない)。
// 実際の反映は、生成されたテキストを管理者が手動でサーバーに配置する形になる。

function yamlQuote(str) {
    return '"' + String(str).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
}

function buildYaml({ material, displayName, loreLines, customModelData, description }) {
    let yaml = `material: ${material}\n`;
    yaml += `display_name: ${yamlQuote(displayName)}\n`;
    if (loreLines.length > 0) {
        yaml += 'lore:\n';
        for (const line of loreLines) {
            yaml += `  - ${yamlQuote(line)}\n`;
        }
    }
    if (customModelData !== '') {
        yaml += `custom_model_data: ${customModelData}\n`;
    }
    if (description) {
        yaml += `description: ${yamlQuote(description)}\n`;
    }
    return yaml;
}

// SkriptのアイテムエイリアスはBukkitのMaterial名と表記が異なる(例: IRON_INGOT -> iron ingot)。
// ここでは簡易的にアンダースコアをスペースに置換するだけの目安を出す。実際の書き方は
// お使いのSkriptバージョンのアイテムエイリアス一覧で必ず確認すること。
function guessSkriptMaterialName(material) {
    return material.trim().toLowerCase().replace(/_/g, ' ');
}

function buildSkriptTemplate({ toolCode, material, displayName }) {
    const skriptMaterial = guessSkriptMaterialName(material) || '<material>';
    return `# 目安のテンプレート。material名の表記はSkriptのアイテムエイリアス一覧で要確認。
on right click holding ${skriptMaterial}:
    if name of player's tool is "${displayName}":
        cancel event
        if player has permission "fje.tool.${toolCode}":
            # ここに実際の処理を書く
        else:
            send "&cこのツールは購入していません" to player
`;
}

document.getElementById('generateBtn').addEventListener('click', () => {
    const toolCode = document.getElementById('fToolCode').value.trim();
    const material = document.getElementById('fMaterial').value.trim();
    const displayName = document.getElementById('fDisplayName').value.trim();
    const loreLines = document.getElementById('fLore').value.split('\n').map(l => l.trim()).filter(l => l.length > 0);
    const customModelData = document.getElementById('fCustomModelData').value.trim();
    const description = document.getElementById('fDescription').value.trim();

    if (!toolCode || !material || !displayName) {
        alert('tool_code・material・display_name は必須です');
        return;
    }
    if (!/^[a-zA-Z0-9_]+$/.test(toolCode)) {
        alert('tool_code は英数字とアンダースコアのみ使用してください（ファイル名になるため）');
        return;
    }

    document.getElementById('yamlOutput').textContent = buildYaml({ material, displayName, loreLines, customModelData, description });
    document.getElementById('skriptOutput').textContent = buildSkriptTemplate({ toolCode, material, displayName });
    document.getElementById('outputSection').classList.remove('hidden');
});

function makeCopyHandler(sourceId) {
    return async () => {
        const text = document.getElementById(sourceId).textContent;
        try {
            await navigator.clipboard.writeText(text);
        } catch (err) {
            alert('コピーに失敗しました。手動で選択してコピーしてください。');
        }
    };
}

document.getElementById('copyYamlBtn').addEventListener('click', makeCopyHandler('yamlOutput'));
document.getElementById('copySkriptBtn').addEventListener('click', makeCopyHandler('skriptOutput'));

function renderCatalogEntry(entry) {
    const row = document.createElement('div');
    row.className = 'bg-white rounded-xl shadow-sm px-3 py-2 space-y-1';
    row.innerHTML = `
        <div class="flex justify-between items-center text-xs">
            <span class="font-mono font-bold text-gray-700">${window.fjew.escapeHtml(entry.tool_code)}</span>
            <span class="text-gray-400">${window.fjew.escapeHtml(entry.material)}</span>
        </div>
        <div class="text-xs text-gray-600">${window.fjew.escapeHtml(entry.display_name)}</div>
        ${entry.description ? `<div class="text-[10px] text-gray-400">${window.fjew.escapeHtml(entry.description)}</div>` : ''}
    `;
    return row;
}

async function loadCatalog() {
    try {
        const { res, data } = await window.fjew.fetchJson('/api/admin/tool-catalog');
        if (!res.ok) throw new Error(data.error || 'カタログの取得に失敗しました');
        const listEl = document.getElementById('catalogList');
        listEl.innerHTML = '';
        if (data.length === 0) {
            listEl.innerHTML = '<p class="text-sm text-gray-500">まだ登録されたツールがありません。</p>';
            return;
        }
        for (const entry of data) {
            listEl.appendChild(renderCatalogEntry(entry));
        }
    } catch (err) {
        console.error('カタログ取得エラー:', err);
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

    const { res } = await window.fjew.fetchJson('/api/admin/tool-catalog');
    if (res.status === 403) {
        document.getElementById('forbidden').classList.remove('hidden');
        return;
    }
    document.getElementById('adminContent').classList.remove('hidden');
    loadCatalog();
}

window.fjew.onReloadNeeded(init);
