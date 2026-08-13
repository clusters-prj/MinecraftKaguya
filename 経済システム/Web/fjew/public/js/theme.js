/* ふじゅ〜ペイ ブランドテーマ（紫×藍の夜空）
   Tailwind CDN の直後に読み込むこと。ページ描画前に config を差し込む。 */
if (typeof tailwind !== 'undefined') {
    tailwind.config = {
        theme: {
            extend: {
                colors: {
                    // ブランド: 紫 → 藍
                    brand: {
                        50: '#f5f3ff',
                        100: '#ede9fe',
                        200: '#ddd6fe',
                        300: '#c4b5fd',
                        400: '#a78bfa',
                        500: '#8b5cf6',
                        600: '#6d28d9',
                        700: '#5b21b6',
                        800: '#4c1d95',
                        900: '#3730a3'
                    },
                    // アクセント: 月明かりの金
                    moon: {
                        50: '#fffbeb',
                        100: '#fef3c7',
                        200: '#fde68a',
                        400: '#fbbf24',
                        600: '#d97706',
                        700: '#b45309'
                    }
                }
            }
        }
    };
}
