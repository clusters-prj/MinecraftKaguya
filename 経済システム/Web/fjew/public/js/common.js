window.fjew = {
    async fetchJson(url, options = {}) {
        console.log('fetchJson呼び出し:', url);
        const res = await fetch(url, options);
        console.log('fetchJsonレスポンス:', url, res.status);
        let data = {};
        try {
            data = await res.json();
        } catch (_) {
            data = {};
        }
        return { res, data };
    },

    async getCurrentUser() {
        const { res, data } = await this.fetchJson('/api/user/me');
        if (res.status === 401) return null;
        if (!res.ok) throw new Error(data.error || 'ユーザー情報の取得に失敗しました');
        return data;
    },

    async requireAuth() {
        const user = await this.getCurrentUser();
        if (!user) {
            window.location.href = '/login';
            return null;
        }
        return user;
    },

    formatYen(value) {
        return Number(value || 0).toLocaleString();
    },

    // innerHTML のテンプレートへ埋め込む値は必ずこれを通すこと。
    // アリーナのイベント名など、管理画面から自由入力された文字列がそのまま
    // 各ユーザーの画面で innerHTML として展開されると、
    // <img src=x onerror=...> のようなタグが実行されてしまう（蓄積型XSS）。
    escapeHtml(value) {
        if (value === null || value === undefined) return '';
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    },

    // bfcache(戻る/進むでページがJSごと復元される挙動)から復帰した際に
    // 再取得処理(reloadFn)を呼び直すための共通ヘルパー。
    // 通常のDOMContentLoadedだけだとbfcache復元時に発火しないため、
    // pageshowイベントでevent.persistedを見て再取得する。
    // また、Rocket Loader等でスクリプト実行が遅延し、登録時点で
    // すでにDOMContentLoadedが発火済みになっているケースにも対応する。
    onReloadNeeded(reloadFn) {
        if (document.readyState === 'loading') {
            // window ではなく document に変更
            document.addEventListener('DOMContentLoaded', reloadFn);
        } else {
            reloadFn();
        }
        window.addEventListener('pageshow', (event) => {
            if (event.persisted) {
                reloadFn();
            }
        });
    },

    // ログイン画面等と同じ書式(stroke="currentColor" viewBox="0 0 24 24")のアウトラインアイコン。
    // 外部アイコンフォント/ライブラリを追加せず、インラインSVGだけで完結させる。
    _bottomNavIconPaths: {
        home: 'M2.25 12l8.954-8.955c.44-.439 1.152-.439 1.591 0L21.75 12M4.5 9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75M8.25 21h8.25',
        clock: 'M12 6v6h4.5m4.5 0a9 9 0 11-18 0 9 9 0 0118 0z',
        bag: 'M15.75 10.5V6a3.75 3.75 0 10-7.5 0v4.5m11.356-1.993l1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 01-1.12-1.243l1.264-12A1.125 1.125 0 015.513 7.5h12.974c.576 0 1.059.435 1.119 1.007z',
        paw: 'M7.2 9a1.6 1.6 0 100-3.2A1.6 1.6 0 007.2 9zm4.8-2.2a1.6 1.6 0 100-3.2 1.6 1.6 0 000 3.2zm4.8 2.2a1.6 1.6 0 100-3.2 1.6 1.6 0 000 3.2zM12 19.7c-2.9 0-5.3-1.9-5.3-4.2S9.1 11.3 12 11.3s5.3 1.9 5.3 4.2-2.4 4.2-5.3 4.2z',
        sword: 'M6 18 18 6M14.5 3.5l4 4-2 2-4-4 2-2zM4.5 19.5l2-2',
        cog: 'M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.324.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 011.37.49l1.296 2.247a1.125 1.125 0 01-.26 1.431l-1.003.827c-.293.24-.438.613-.431.992a6.759 6.759 0 010 .255c-.007.378.138.75.43.99l1.005.828c.424.35.534.954.26 1.43l-1.298 2.247a1.125 1.125 0 01-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.57 6.57 0 01-.22.128c-.331.183-.581.495-.644.869l-.213 1.28c-.09.543-.56.941-1.11.941h-2.594c-.55 0-1.02-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 01-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 01-1.369-.49l-1.297-2.247a1.125 1.125 0 01.26-1.431l1.004-.827c.292-.24.437-.613.43-.992a6.932 6.932 0 010-.255c.007-.378-.138-.75-.43-.99l-1.004-.828a1.125 1.125 0 01-.26-1.43l1.297-2.247a1.125 1.125 0 011.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.087.22-.128.332-.183.582-.495.644-.869l.214-1.28zM15 12a3 3 0 11-6 0 3 3 0 016 0z'
    },

    _bottomNavIcon(name) {
        const d = this._bottomNavIconPaths[name];
        return `<svg class="fj-bottom-nav__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="${d}"></path></svg>`;
    },

    // スマホ用の下部固定タブバー。PC(md以上)ではCSS側で非表示にする(.fj-bottom-nav)。
    // ヘッダーに6個ボタンを横並びさせると詰まってしまうため、主要セクションへの導線はここに集約する。
    // ログイン前のページ(login/reset-password)では表示しない。
    renderBottomNav() {
        const hiddenOn = ['/login', '/reset-password'];
        if (hiddenOn.includes(window.location.pathname)) return;

        const items = [
            { href: '/main', icon: 'home', label: 'ホーム' },
            { href: '/history', icon: 'clock', label: '履歴' },
            { href: '/marketplace', icon: 'bag', label: 'マーケット' },
            { href: '/pet-shop', icon: 'paw', label: 'ペット' },
            { href: '/arena', icon: 'sword', label: 'アリーナ' },
            { href: '/settings', icon: 'cog', label: '設定' }
        ];

        const nav = document.createElement('nav');
        nav.className = 'fj-bottom-nav';
        nav.setAttribute('aria-label', '主要ナビゲーション');
        nav.innerHTML = items.map(item => {
            const isActive = window.location.pathname === item.href;
            return `<a href="${item.href}" class="fj-bottom-nav__item${isActive ? ' is-active' : ''}">` +
                `${this._bottomNavIcon(item.icon)}<span>${item.label}</span></a>`;
        }).join('');

        document.body.appendChild(nav);
        document.body.classList.add('fj-has-bottom-nav');
    }
};

window.fjew.renderBottomNav();
