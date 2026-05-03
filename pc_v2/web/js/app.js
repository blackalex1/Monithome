const urlParams = new URLSearchParams(window.location.search);
const guiToken = urlParams.get('gui_token');

const socket = io("http://127.0.0.1:5000", {
    auth: {
        token: guiToken || localStorage.getItem('auth_token')
    }
});

// Connection UI
const dot = document.getElementById('connection-dot');
const statusText = document.getElementById('connection-status');

socket.on('connect', () => {
    dot.classList.add('connected');
    updateConnectionStatus('status_connected', 'Server Connected');
});

socket.on('disconnect', () => {
    dot.classList.remove('connected');
    updateConnectionStatus('status_disconnected', 'Disconnected');
});

socket.on('auth_required', () => {
    // Если мы в GUI, возможно токен протух или не сработал
    const message = guiToken ?
        "Local GUI token invalid. Enter pairing code from PC screen:" :
        "New device detected. Enter pairing code from PC screen:";

    const code = prompt(message);
    if (code) {
        socket.emit('auth_attempt', { code: code });
    }
});

socket.on('auth_success', (data) => {
    console.log("Authorized successfully!");
    if (data.token) {
        localStorage.setItem('auth_token', data.token);
    }
    if (data.encryption_key) {
        localStorage.setItem('encryption_key', data.encryption_key);
    }
    // Если мы получили конфиг при авторизации, применим его
    if (data.theme_color) {
        applyThemeColor(data.theme_color);
    }
});

socket.on('ui_config', (data) => {
    if (data.theme_color) applyThemeColor(data.theme_color);
    loadPlugins(); // Обновляем список плагинов (вкл/выкл)
});

socket.on('theme_update', (data) => {
    if (data.theme_color) applyThemeColor(data.theme_color);
});

socket.on('auth_error', (data) => {
    alert("Authorization failed: " + data.message);
    localStorage.removeItem('auth_token');
    location.reload();
});

socket.on('stats_json', (payload) => {
    const stats = payload.stats;
    if (stats.system_stats) {
        const s = stats.system_stats;
        if (s.cpu !== undefined) document.getElementById('cpu-val').textContent = `${Math.round(s.cpu)}%`;
        if (s.ram_percent !== undefined) document.getElementById('ram-val').textContent = `${Math.round(s.ram_percent)}%`;
        if (s.cpu_temp !== undefined) document.getElementById('temp-val').textContent = `${Math.round(s.cpu_temp)}°C`;
    }
});

function applyThemeColor(hex) {
    if (!hex) return;

    let cssColor = hex;
    if (hex.startsWith('0xFF')) {
        cssColor = '#' + hex.substring(4);
    } else if (hex.startsWith('0x')) {
        cssColor = '#' + hex.substring(2);
    }

    document.documentElement.style.setProperty('--accent', cssColor);

    // Вычисляем RGB для создания цвета свечения с прозрачностью
    if (cssColor.startsWith('#')) {
        const r = parseInt(cssColor.substring(1, 3), 16);
        const g = parseInt(cssColor.substring(3, 5), 16);
        const b = parseInt(cssColor.substring(5, 7), 16);
        document.documentElement.style.setProperty('--accent-glow', `rgba(${r}, ${g}, ${b}, 0.5)`);
    }
}

function updateConnectionStatus(key, fallback) {
    if (window.translations && window.translations[key]) {
        statusText.textContent = window.translations[key];
        statusText.setAttribute('data-i18n', key);
    } else {
        statusText.textContent = fallback;
    }
}

// i18n System
window.translations = {};
async function switchLanguage(lang) {
    try {
        const res = await fetch(`/static/languages/${lang}.json`);
        window.translations = await res.json();

        // Apply translations to all elements with data-i18n
        document.querySelectorAll('[data-i18n]').forEach(el => {
            const key = el.getAttribute('data-i18n');
            if (window.translations[key]) {
                if (el.tagName === 'INPUT' && el.type === 'button') {
                    el.value = window.translations[key];
                } else {
                    el.textContent = window.translations[key];
                }
            }
        });

        console.log(`Language switched to: ${lang}`);
    } catch (err) {
        console.error("Failed to load language file", err);
    }
}

// Navigation
document.querySelectorAll('.nav-links a').forEach(link => {
    link.addEventListener('click', (e) => {
        e.preventDefault();
        document.querySelectorAll('.nav-links li').forEach(li => li.classList.remove('active'));
        e.target.parentElement.classList.add('active');

        const targetId = e.target.getAttribute('data-target');
        document.querySelectorAll('.content-section').forEach(sec => sec.classList.remove('active'));
        document.getElementById(targetId).classList.add('active');
    });
});

// Load Plugins
const pluginsList = document.getElementById('plugins-list');

async function loadPlugins() {
    try {
        const response = await fetch('/api/plugins');
        const data = await response.json();

        pluginsList.innerHTML = '';
        data.plugins.forEach(plugin => {
            const card = document.createElement('div');
            card.className = 'plugin-card glass-panel';

            const name = window.translations[`plugin_name_${plugin.id}`] || plugin.name;
            const desc = window.translations[`plugin_desc_${plugin.id}`] || plugin.description || 'No description available.';
            const runningStr = window.translations['plugin_running'] || 'Running';
            const stoppedStr = window.translations['plugin_stopped'] || 'Stopped';
            const loginStr = window.translations['btn_yandex_login'] || 'Login with QR';

            card.innerHTML = `
                <div class="plugin-header">
                    <div>
                        <h3 class="plugin-title">${name}</h3>
                        <p class="plugin-desc">${desc}</p>
                    </div>
                    <label class="switch">
                        <input type="checkbox" onchange="togglePlugin('${plugin.id}', this.checked)" ${plugin.active ? 'checked' : ''}>
                        <span class="slider"></span>
                    </label>
                </div>
                <div class="plugin-actions">
                    <span style="font-size: 13px; color: ${plugin.active ? 'var(--success)' : 'var(--text-muted)'}">
                        ${plugin.active ? '● ' + runningStr : '○ ' + stoppedStr}
                    </span>
                    <div style="display:flex; gap: 8px;">
                        <button class="plugin-btn" onclick="showPluginInfo('${plugin.id}')" title="${window.translations['btn_info'] || 'Info'}">ℹ️</button>
                        <button class="plugin-btn" onclick="editPluginConfig('${plugin.id}')" title="${window.translations['btn_settings'] || 'Settings'}">⚙️</button>
                        ${plugin.id === 'yandex_station' && plugin.active ? `<button class="plugin-btn" onclick="requestYandexQR()">${loginStr}</button>` : ''}
                    </div>
                </div>
            `;
            pluginsList.appendChild(card);
        });
    } catch (err) {
        console.error("Failed to load plugins", err);
    }
}

// Toggle Plugin
window.togglePlugin = async (pluginId, isActive) => {
    try {
        const res = await fetch('/api/plugins/toggle', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ plugin_id: pluginId, active: isActive })
        });
        if (res.ok) {
            setTimeout(loadPlugins, 500); // Reload list to update status UI
        }
    } catch (err) {
        console.error("Failed to toggle plugin", err);
    }
};

// Yandex QR Auth / Modal Handling
const modal = document.getElementById('action-modal');
const modalTitle = document.getElementById('modal-title');
const modalContent = document.getElementById('modal-content');
document.getElementById('modal-close').onclick = () => modal.classList.add('hidden');

window.showPluginInfo = async (pluginId) => {
    try {
        const res = await fetch(`/api/plugins/${pluginId}/config`);
        const config = await res.json();

        const name = window.translations[`plugin_name_${pluginId}`] || config.name || pluginId;
        const desc = window.translations[`plugin_desc_${pluginId}`] || config.description || 'No description available.';
        const verLabel = window.translations['label_version'] || 'Version';
        const authLabel = window.translations['label_author'] || 'Author';

        modalTitle.textContent = name;
        modalContent.innerHTML = `
            <div style="text-align: left; width: 100%; font-size: 14px; color: var(--text-muted);">
                <p><strong>ID:</strong> ${pluginId}</p>
                <p><strong>${verLabel}:</strong> ${config.version || 'Unknown'}</p>
                <p><strong>${authLabel}:</strong> ${config.author_name || 'Unknown'}</p>
                <p style="margin-top: 10px;">${desc}</p>
            </div>
        `;
        modal.classList.remove('hidden');
    } catch (err) { console.error(err); }
};

window.editPluginConfig = async (pluginId) => {
    if (pluginId === 'yandex_station') {
        modalTitle.textContent = window.translations['yandex_settings_title'] || "Настройки Яндекс Станции";
        modalContent.innerHTML = `<div class="loading-spinner"></div>`;
        modal.classList.remove('hidden');
        socket.emit('plugin_command', { plugin_id: 'yandex_station', action: 'get_wizard_data', data: {} });
        return;
    }

    try {
        const res = await fetch(`/api/plugins/${pluginId}/config`);
        const config = await res.json();

        const settingsLabel = window.translations['label_settings'] || 'Settings';
        const saveChangesLabel = window.translations['btn_save_changes'] || 'Save Changes';

        modalTitle.textContent = `${settingsLabel}: ${pluginId}`;
        modalContent.innerHTML = `
            <textarea id="config-editor" style="width:100%; height: 300px; background: #020617; color: #a5b4fc; font-family: monospace; padding: 10px; border-radius: 8px; border: 1px solid var(--border-color);"></textarea>
            <button class="plugin-btn" style="width: 100%; background: var(--accent); margin-top: 10px;" onclick="savePluginConfig('${pluginId}')">${saveChangesLabel}</button>
        `;
        document.getElementById('config-editor').value = JSON.stringify(config, null, 2);
        modal.classList.remove('hidden');
    } catch (err) { console.error(err); }
};

window.savePluginConfig = async (pluginId) => {
    try {
        const newConfig = JSON.parse(document.getElementById('config-editor').value);
        const res = await fetch(`/api/plugins/${pluginId}/config`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ config_data: newConfig })
        });
        if (res.ok) {
            modal.classList.add('hidden');
            loadPlugins(); // Reload to reflect any name/desc changes
        }
    } catch (err) {
        alert("Invalid JSON format!");
    }
};

window.requestYandexQR = () => {
    modalTitle.textContent = "Yandex Station Auth";
    modalContent.innerHTML = `<p>Requesting QR Code...</p>`;
    modal.classList.remove('hidden');

    // Trigger command to plugin
    socket.emit('plugin_command', { plugin_id: 'yandex_station', action: 'start_qr_login', data: {} });
};

// Listen for custom events from plugins (e.g. show_qr)
socket.on('plugin_event:yandex_station', (payload) => {
    if (payload.event === 'show_qr') {
        const data = payload.data;

        let html = `<p style="color: var(--accent); font-weight: 500;">${data.status || 'Waiting...'}</p>`;
        if (data.qr_url) {
            html += `<img src="${data.qr_url}" alt="QR Code" style="max-width: 250px; border-radius: 12px; margin: 15px 0;">`;
            html += `<p style="font-size: 13px; color: var(--text-muted);">${data.instructions || 'Scan with Yandex App'}</p>`;
        }

        modalContent.innerHTML = html;
        if (modal.classList.contains('hidden')) {
            modalTitle.textContent = "Yandex Station Auth";
            modal.classList.remove('hidden');
        }
    } else if (payload.event === 'wizard_data') {
        const data = payload.data;
        modalTitle.textContent = window.translations['yandex_settings_title'] || "Настройки Яндекс Станции";

        let devicesHtml = '';
        data.devices.forEach(dev => {
            const isChecked = data.selected_device_ids.length === 0 || data.selected_device_ids.includes(dev.id);
            devicesHtml += `
                <div class="device-item" style="display: flex; align-items: center; justify-content: space-between; padding: 10px; background: rgba(255,255,255,0.05); border-radius: 8px; margin-bottom: 8px;">
                    <span style="color: white; font-weight: 500;">${dev.name}</span>
                    <input type="checkbox" class="device-checkbox" data-id="${dev.id}" ${isChecked ? 'checked' : ''}>
                </div>
            `;
        });

        modalContent.innerHTML = `
            <div class="yandex-wizard" style="text-align: left;">
                <div class="settings-group" style="margin-bottom: 20px;">
                    <h4 style="color: var(--accent); margin-bottom: 10px;">${window.translations['yandex_control_mode'] || 'Режим управления'}</h4>
                    <label style="display: flex; align-items: center; gap: 10px; cursor: pointer;">
                        <input type="checkbox" id="tablet-control-toggle" ${data.tablet_control ? 'checked' : ''}>
                        <span style="color: white;">${window.translations['yandex_tablet_control_label'] || 'Управление через планшет (Direct Glagol)'}</span>
                    </label>
                </div>

                <div class="settings-group">
                    <h4 style="color: var(--accent); margin-bottom: 10px;">${window.translations['yandex_visible_speakers'] || 'Видимые колонки'}</h4>
                    <div id="devices-list-wizard">
                        ${devicesHtml || '<p style="color: var(--text-muted);">...</p>'}
                    </div>
                </div>

                <button class="plugin-btn" style="width: 100%; background: var(--success); margin-top: 20px; color: white;" onclick="saveYandexWizard()">${window.translations['btn_save'] || 'Сохранить настройки'}</button>
            </div>
        `;
    }
});

window.saveYandexWizard = () => {
    const isTablet = document.getElementById('tablet-control-toggle').checked;
    const selectedIds = Array.from(document.querySelectorAll('.device-checkbox:checked')).map(cb => cb.getAttribute('data-id'));

    socket.emit('plugin_command', {
        plugin_id: 'yandex_station',
        action: 'handle_wizard',
        data: {
            tablet_control: isTablet,
            selected_device_ids: selectedIds
        }
    });

    modal.classList.add('hidden');
};

// Logs Logic
const logOutput = document.getElementById('log-output');
const MAX_LOG_LINES = 200;

socket.on('server_log', (payload) => {
    const msg = payload.message || '';
    const span = document.createElement('div');
    span.className = 'log-line';

    if (msg.includes('[WARNING]')) span.classList.add('log-warning');
    else if (msg.includes('[ERROR]')) span.classList.add('log-error');
    else span.classList.add('log-info');

    span.textContent = msg;
    logOutput.appendChild(span);

    // Auto-scroll
    const container = logOutput.parentElement;
    container.scrollTop = container.scrollHeight;

    // Trim old logs
    while (logOutput.children.length > MAX_LOG_LINES) {
        logOutput.removeChild(logOutput.firstChild);
    }
});

// Initial Load
loadPlugins();
loadGlobalConfig();

// Global Settings Logic
async function loadGlobalConfig() {
    try {
        const res = await fetch('/api/config');
        const config = await res.json();

        document.getElementById('server-hostname').value = config.hostname || '';
        document.getElementById('server-language').value = config.language || 'ru';

        // Load translations
        await switchLanguage(config.language || 'ru');

        const colorHex = config.theme_color || '0xFF22C55E';
        document.getElementById('server-theme-hex').value = colorHex;
        applyThemeColor(colorHex);

        // Sync color picker
        let pickerColor = colorHex.startsWith('0xFF') ? '#' + colorHex.substring(4) : colorHex.replace('0x', '#');
        document.getElementById('server-theme-picker').value = pickerColor.substring(0, 7);
    } catch (err) {
        console.error("Failed to load global config", err);
    }
}

// Language Switch Preview
document.getElementById('server-language').addEventListener('change', (e) => {
    switchLanguage(e.target.value);
});

// Sync HEX input and Color Picker
const themePicker = document.getElementById('server-theme-picker');
const themeHex = document.getElementById('server-theme-hex');

themePicker.addEventListener('input', (e) => {
    const hex = e.target.value.toUpperCase();
    themeHex.value = '0xFF' + hex.substring(1);
});

themeHex.addEventListener('input', (e) => {
    let hex = e.target.value;
    if (hex.startsWith('0x')) {
        const cssColor = '#' + hex.substring(4);
        if (/^#[0-9A-F]{6}$/i.test(cssColor)) {
            themePicker.value = cssColor;
        }
    }
});

document.getElementById('global-settings-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = document.getElementById('save-config-btn');
    const originalText = btn.textContent;

    btn.textContent = 'Saving...';
    btn.disabled = true;

    const config = {
        hostname: document.getElementById('server-hostname').value,
        language: document.getElementById('server-language').value,
        theme_color: document.getElementById('server-theme-hex').value
    };

    try {
        const res = await fetch('/api/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(config)
        });

        if (res.ok) {
            btn.textContent = 'Saved!';
            btn.style.background = 'var(--success)';
            setTimeout(() => {
                btn.textContent = originalText;
                btn.style.background = 'var(--accent)';
                btn.disabled = false;
            }, 2000);
        }
    } catch (err) {
        console.error("Failed to save config", err);
        btn.textContent = 'Error!';
        btn.style.background = 'var(--danger)';
        setTimeout(() => {
            btn.textContent = originalText;
            btn.style.background = 'var(--accent)';
            btn.disabled = false;
        }, 2000);
    }
});
