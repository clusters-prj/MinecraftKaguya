import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

// 巨大な設計図でブラウザが固まらないよう、表示ブロック数に上限を設ける
const MAX_RENDER_BLOCKS = 20000;

function getParam(name) {
    return new URLSearchParams(window.location.search).get(name);
}

async function loadBlueprintData() {
    const listingId = getParam('listingId');
    if (listingId) {
        const { res, data } = await window.fjew.fetchJson(`/api/marketplace/listings/${listingId}/blueprint`);
        if (!res.ok) throw new Error(data.error || '設計図の取得に失敗しました');
        return data;
    }
    if (getParam('source') === 'session') {
        const raw = sessionStorage.getItem('fj_blueprint_preview');
        if (!raw) throw new Error('プレビューするデータが見つかりません。出品画面からやり直してください。');
        try {
            return JSON.parse(raw);
        } catch (err) {
            throw new Error('プレビューデータの読み込みに失敗しました');
        }
    }
    throw new Error('プレビュー対象が指定されていません');
}

// マテリアル名の先頭が染料色（WHITE_WOOL, RED_CONCRETE 等）の場合の色
const COLOR_PREFIX_MAP = {
    WHITE: '#e9ecf1', ORANGE: '#e07a2b', MAGENTA: '#b350c7', LIGHT_BLUE: '#5dabd6',
    YELLOW: '#e8c93a', LIME: '#7bc142', PINK: '#e59bc2', LIGHT_GRAY: '#8f9498',
    GRAY: '#3f4448', CYAN: '#2a9c9c', PURPLE: '#7b3fb5', BLUE: '#3854a5',
    BROWN: '#5b3a29', GREEN: '#546c1f', RED: '#a33430', BLACK: '#1a1a1e'
};
// 染料色以外の代表的なマテリアルの色（雰囲気が伝わればよい程度の近似）
const MATERIAL_COLOR_RULES = [
    [/GRASS_BLOCK/, '#5a8c3c'],
    [/GRASS/, '#5a8c3c'],
    [/DIRT/, '#79553a'],
    [/COBBLESTONE|STONE_BRICK/, '#8a8a8a'],
    [/^STONE|SMOOTH_STONE/, '#9a9a9a'],
    [/PLANKS/, '#b3854a'],
    [/LOG|WOOD/, '#5c4326'],
    [/LEAVES/, '#3f7a34'],
    [/SAND/, '#dccb85'],
    [/GRAVEL/, '#8b8579'],
    [/GLASS/, '#bfe3ec'],
    [/WATER/, '#3b6fd1'],
    [/LAVA/, '#e0791a'],
    [/NETHERRACK/, '#6b2c2c'],
    [/OBSIDIAN/, '#180d24'],
    [/BRICK/, '#8f4a3b'],
    [/GOLD/, '#e0c22c'],
    [/IRON/, '#d3d3cd'],
    [/DIAMOND/, '#5fd6d0'],
    [/EMERALD/, '#3fbf6f'],
    [/QUARTZ/, '#ece6db'],
    [/SNOW|ICE/, '#dff1f7']
];

function hashColor(name) {
    let hash = 0;
    for (let i = 0; i < name.length; i++) {
        hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
    }
    const hue = hash % 360;
    return `hsl(${hue}, 40%, 55%)`;
}

// 木の種類ごとのSTAIRS/SLAB/FENCE/DOOR等は素材名にPLANKS/LOGを含まないため、
// 個別に木材種を判定してPLANKS/LOG相当の色に寄せる(そうしないと同じ木材の
// 階段とハーフブロックが無関係なハッシュ色になり見分けがつかなくなる)
const WOOD_SPECIES = ['OAK', 'SPRUCE', 'BIRCH', 'JUNGLE', 'ACACIA', 'DARK_OAK', 'MANGROVE', 'CHERRY', 'BAMBOO', 'CRIMSON', 'WARPED', 'PALE_OAK'];

function colorForMaterial(material) {
    const name = (material || 'UNKNOWN').toUpperCase();
    for (const prefix of Object.keys(COLOR_PREFIX_MAP)) {
        if (name === prefix || name.startsWith(prefix + '_')) {
            return new THREE.Color(COLOR_PREFIX_MAP[prefix]);
        }
    }
    if (WOOD_SPECIES.some((species) => name === species || name.startsWith(species + '_'))) {
        if (/LOG|WOOD|STEM|HYPHAE/.test(name)) return new THREE.Color('#5c4326');
        if (/LEAVES/.test(name)) return new THREE.Color('#3f7a34');
        return new THREE.Color('#b3854a');
    }
    for (const [pattern, color] of MATERIAL_COLOR_RULES) {
        if (pattern.test(name)) return new THREE.Color(color);
    }
    return new THREE.Color(hashColor(name));
}

// "oak_stairs[facing=north,half=bottom,shape=straight]" のようなBukkitのBlockData文字列を分解する
function parseBlockData(blockData) {
    if (!blockData || typeof blockData !== 'string') return null;
    const m = /^([a-zA-Z0-9_]+)(?:\[(.*)])?$/.exec(blockData.trim());
    if (!m) return null;
    const props = {};
    if (m[2]) {
        for (const pair of m[2].split(',')) {
            const eq = pair.indexOf('=');
            if (eq === -1) continue;
            props[pair.slice(0, eq).trim()] = pair.slice(eq + 1).trim();
        }
    }
    return { name: m[1], props };
}

function addStairMesh(group, mat, getBoxGeom, x, y, z, props) {
    const half = props.half === 'top' ? 'top' : 'bottom';
    const facing = props.facing || 'north';
    const axis = (facing === 'north' || facing === 'south') ? 'z' : 'x';
    const sign = (facing === 'north' || facing === 'west') ? -1 : 1;

    // 奥側：全高の半分幅ブロック
    const backW = axis === 'x' ? 0.5 : 1;
    const backD = axis === 'z' ? 0.5 : 1;
    const backMesh = new THREE.Mesh(getBoxGeom(backW, 1, backD), mat);
    const backOffset = -sign * 0.25;
    backMesh.position.set(
        x + 0.5 + (axis === 'x' ? backOffset : 0),
        y + 0.5,
        z + 0.5 + (axis === 'z' ? backOffset : 0)
    );
    group.add(backMesh);

    // 手前側：段差になる半分高さのブロック
    const frontW = axis === 'x' ? 0.5 : 1;
    const frontD = axis === 'z' ? 0.5 : 1;
    const frontMesh = new THREE.Mesh(getBoxGeom(frontW, 0.5, frontD), mat);
    const frontOffset = sign * 0.25;
    const frontY = half === 'top' ? 0.75 : 0.25;
    frontMesh.position.set(
        x + 0.5 + (axis === 'x' ? frontOffset : 0),
        y + frontY,
        z + 0.5 + (axis === 'z' ? frontOffset : 0)
    );
    group.add(frontMesh);
}

function buildScene(container, blueprint) {
    const width = container.clientWidth;
    const height = container.clientHeight;

    const scene = new THREE.Scene();
    scene.background = new THREE.Color('#f5f4fb');

    const camera = new THREE.PerspectiveCamera(50, width / height, 0.1, 2000);
    const renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.setSize(width, height);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    container.innerHTML = '';
    container.appendChild(renderer.domElement);

    scene.add(new THREE.AmbientLight(0xffffff, 0.65));
    const dirLight = new THREE.DirectionalLight(0xffffff, 0.8);
    dirLight.position.set(6, 10, 8);
    scene.add(dirLight);
    const dirLight2 = new THREE.DirectionalLight(0xffffff, 0.35);
    dirLight2.position.set(-6, 4, -8);
    scene.add(dirLight2);

    const blocks = Array.isArray(blueprint.blocks) ? blueprint.blocks : [];
    const materialCounts = {};
    const group = new THREE.Group();

    const matCache = new Map();
    const boxGeomCache = new Map();
    function getMaterial(hex) {
        if (!matCache.has(hex)) {
            matCache.set(hex, new THREE.MeshLambertMaterial({ color: new THREE.Color(hex) }));
        }
        return matCache.get(hex);
    }
    function getBoxGeom(w, h, d) {
        const key = `${w}_${h}_${d}`;
        if (!boxGeomCache.has(key)) {
            boxGeomCache.set(key, new THREE.BoxGeometry(w, h, d));
        }
        return boxGeomCache.get(key);
    }

    let minX = Infinity, minY = Infinity, minZ = Infinity, maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;
    const cubeBuckets = new Map();
    const renderedBlocks = blocks.slice(0, MAX_RENDER_BLOCKS);

    for (const block of renderedBlocks) {
        const x = Number(block.x) || 0;
        const y = Number(block.y) || 0;
        const z = Number(block.z) || 0;
        const material = block.material || 'UNKNOWN';
        materialCounts[material] = (materialCounts[material] || 0) + 1;

        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);

        const hex = '#' + colorForMaterial(material).getHexString();
        const upperMaterial = material.toUpperCase();
        const blockData = parseBlockData(block['block-data']);

        if (upperMaterial.endsWith('_SLAB')) {
            const type = blockData?.props?.type || 'bottom';
            let h = 0.5, yOff = -0.25;
            if (type === 'top') yOff = 0.25;
            else if (type === 'double') { h = 1; yOff = 0; }
            const mesh = new THREE.Mesh(getBoxGeom(1, h, 1), getMaterial(hex));
            mesh.position.set(x + 0.5, y + 0.5 + yOff, z + 0.5);
            group.add(mesh);
        } else if (upperMaterial.endsWith('_STAIRS')) {
            addStairMesh(group, getMaterial(hex), getBoxGeom, x, y, z, blockData?.props || {});
        } else {
            if (!cubeBuckets.has(hex)) cubeBuckets.set(hex, []);
            cubeBuckets.get(hex).push([x, y, z]);
        }
    }

    for (const [hex, positions] of cubeBuckets.entries()) {
        const mesh = new THREE.InstancedMesh(getBoxGeom(1, 1, 1), getMaterial(hex), positions.length);
        const dummy = new THREE.Object3D();
        positions.forEach(([x, y, z], i) => {
            dummy.position.set(x + 0.5, y + 0.5, z + 0.5);
            dummy.updateMatrix();
            mesh.setMatrixAt(i, dummy.matrix);
        });
        group.add(mesh);
    }

    scene.add(group);

    if (renderedBlocks.length === 0) {
        minX = minY = minZ = maxX = maxY = maxZ = 0;
    }

    const centerX = (minX + maxX) / 2 + 0.5;
    const centerY = (minY + maxY) / 2 + 0.5;
    const centerZ = (minZ + maxZ) / 2 + 0.5;
    const sizeVec = new THREE.Vector3(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
    const radius = Math.max(sizeVec.length(), 3);

    camera.position.set(centerX + radius * 0.8, centerY + radius * 0.7, centerZ + radius * 0.8);
    camera.lookAt(centerX, centerY, centerZ);

    const controls = new OrbitControls(camera, renderer.domElement);
    controls.target.set(centerX, centerY, centerZ);
    controls.enableDamping = true;
    controls.dampingFactor = 0.08;
    controls.update();

    const gridSize = Math.max(Math.ceil(radius * 2.5), 10);
    const grid = new THREE.GridHelper(gridSize, gridSize, 0xcccccc, 0xe5e5e5);
    grid.position.set(centerX, minY, centerZ);
    scene.add(grid);

    function animate() {
        requestAnimationFrame(animate);
        controls.update();
        renderer.render(scene, camera);
    }
    animate();

    window.addEventListener('resize', () => {
        const w = container.clientWidth;
        const h = container.clientHeight;
        if (w === 0 || h === 0) return;
        camera.aspect = w / h;
        camera.updateProjectionMatrix();
        renderer.setSize(w, h);
    });

    return { materialCounts, totalBlocks: blocks.length, renderedCount: renderedBlocks.length };
}

function renderLegend(materialCounts) {
    const legend = document.getElementById('materialLegend');
    legend.innerHTML = '';
    const entries = Object.entries(materialCounts).sort((a, b) => b[1] - a[1]);
    for (const [material, count] of entries) {
        const hex = '#' + colorForMaterial(material).getHexString();
        const chip = document.createElement('span');
        chip.className = 'flex items-center gap-1 text-[10px] bg-gray-50 rounded-full px-2 py-1 text-gray-600';
        chip.innerHTML = `<span class="inline-block w-2.5 h-2.5 rounded-full" style="background:${hex}"></span>${window.fjew.escapeHtml(material)} ×${count}`;
        legend.appendChild(chip);
    }
}

async function init() {
    try {
        const blueprint = await loadBlueprintData();
        const blocks = Array.isArray(blueprint.blocks) ? blueprint.blocks : [];
        if (blocks.length === 0) throw new Error('設計図にブロックデータがありません');

        document.getElementById('blueprintName').innerText = blueprint.name || '(無題の設計図)';

        // previewContentがhidden(display:none)の間はcontainerの幅・高さが0になり、
        // three.jsのレンダラーが0x0で初期化されてしまうため、先に表示してからbuildSceneを呼ぶこと
        document.getElementById('loadingIndicator').classList.add('hidden');
        document.getElementById('previewContent').classList.remove('hidden');

        const container = document.getElementById('previewCanvasWrap');
        const { materialCounts, totalBlocks, renderedCount } = buildScene(container, blueprint);

        const statsEl = document.getElementById('blueprintStats');
        statsEl.innerText = totalBlocks > renderedCount
            ? `ブロック数: ${totalBlocks.toLocaleString()}（表示は先頭${renderedCount.toLocaleString()}個まで）`
            : `ブロック数: ${totalBlocks.toLocaleString()}`;

        renderLegend(materialCounts);
    } catch (err) {
        console.error('設計図プレビューエラー:', err);
        document.getElementById('loadingIndicator').classList.add('hidden');
        const errorEl = document.getElementById('errorState');
        errorEl.innerText = err.message || 'プレビューの読み込みに失敗しました';
        errorEl.classList.remove('hidden');
    }
}

document.getElementById('backBtn').addEventListener('click', () => {
    if (window.history.length > 1) window.history.back();
    else window.location.href = '/marketplace';
});

init();
