window.fjew = {
    async fetchJson(url, options = {}) {
        const res = await fetch(url, options);
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
    }
};
