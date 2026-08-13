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
    }
};
