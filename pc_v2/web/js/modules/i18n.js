export let translations = {};

export async function switchLanguage(lang) {
    try {
        const res = await fetch(`/static/languages/${lang}.json`);
        translations = await res.json();
        applyTranslations();
        console.log(`Language switched to: ${lang}`);
    } catch (err) {
        console.error("Failed to load language file", err);
    }
}

export function applyTranslations() {
    document.querySelectorAll('[data-i18n]').forEach(el => {
        const key = el.getAttribute('data-i18n');
        if (translations[key]) {
            if (el.tagName === 'INPUT' && el.type === 'button') {
                el.value = translations[key];
            } else {
                el.textContent = translations[key];
            }
        }
    });
}

export function t(key, fallback) {
    return translations[key] || fallback || key;
}
