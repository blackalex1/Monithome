import { socket } from './socket.js';
import { secureFetch } from './api.js';
import { t } from './i18n.js';
import { showModal, closeModal } from './ui.js';
import { customHandlers } from './plugin_custom.js';

const pluginsList = document.getElementById('plugins-list');

export async function loadPlugins() {
    if (!pluginsList) return;
    try {
        const response = await secureFetch('/api/plugins');
        const data = await response.json();

        pluginsList.innerHTML = '';
        if (data && data.plugins) {
            data.plugins.forEach(plugin => {
                const card = renderPluginCard(plugin);
                pluginsList.appendChild(card);
            });
        } else {
            console.warn("No plugins data received or unauthorized");
            pluginsList.innerHTML = `<p style="text-align:center; color: var(--text-muted); padding: 20px;">${t('error_unauthorized', 'Unauthorized or no data')}</p>`;
        }
    } catch (err) {
        console.error("Failed to load plugins", err);
    }
}

function renderPluginCard(plugin) {
    const card = document.createElement('div');
    card.className = 'plugin-card glass-panel';

    const name = t(`plugin_name_${plugin.id}`, plugin.name);
    const desc = t(`plugin_desc_${plugin.id}`, plugin.description || 'No description available.');
    const runningStr = t('plugin_running', 'Running');
    const stoppedStr = t('plugin_stopped', 'Stopped');
    const loginStr = t('btn_yandex_login', 'Login with QR');

    card.innerHTML = `
        <div class="plugin-header">
            <div class="plugin-info-left">
                <h3 class="plugin-title">${name}</h3>
                <p class="plugin-desc">${desc}</p>
            </div>
            <label class="switch">
                <input type="checkbox" class="plugin-toggle" ${plugin.active ? 'checked' : ''}>
                <span class="slider"></span>
            </label>
        </div>
        <div class="plugin-actions">
            <span style="font-size: 13px; color: ${plugin.active ? 'var(--success)' : 'var(--text-muted)'}">
                ${plugin.active ? '● ' + runningStr : '○ ' + stoppedStr}
            </span>
            <div style="display:flex; gap: 8px; align-items: center;">
                ${plugin.elevation_active ? `
                    <button class="plugin-btn elevation-active-btn" 
                            title="${t('status_elevated', 'Admin Rights Active')}"
                            style="background: var(--success); border-color: rgba(16, 185, 129, 0.4); cursor: default;">
                        🛡️
                    </button>
                ` : (plugin.needs_elevation ? `
                    <button class="plugin-btn elevation-request-btn" 
                            title="${t('btn_elevate', 'Request Admin Rights')}"
                            style="background: var(--danger); border-color: rgba(239, 68, 68, 0.4); animation: pulse-red 2s infinite;">
                        🛡️
                    </button>
                ` : '')}
                <button class="plugin-btn info-btn" title="${t('btn_info', 'Info')}">ℹ️</button>
                ${plugin.has_settings ? `<button class="plugin-btn settings-btn" title="${t('btn_settings', 'Settings')}">⚙️</button>` : ''}
                ${plugin.id === 'yandex_station' && plugin.active ? `<button class="plugin-btn yandex-qr-btn">${loginStr}</button>` : ''}
            </div>
        </div>
    `;

    // Event listeners
    card.querySelector('.plugin-toggle').onchange = (e) => togglePlugin(plugin.id, e.target.checked);
    const elevBtn = card.querySelector('.elevation-request-btn');
    if (elevBtn) elevBtn.onclick = () => requestPluginElevation(plugin.id);
    card.querySelector('.info-btn').onclick = () => showPluginInfo(plugin.id);
    const settingsBtn = card.querySelector('.settings-btn');
    if (settingsBtn) settingsBtn.onclick = () => editPluginConfig(plugin.id);
    const yandexBtn = card.querySelector('.yandex-qr-btn');
    if (yandexBtn) yandexBtn.onclick = () => requestYandexQR();

    return card;
}

export async function togglePlugin(pluginId, isActive) {
    try {
        const res = await secureFetch('/api/plugins/toggle', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ plugin_id: pluginId, active: isActive })
        });
        if (res.ok) {
            setTimeout(loadPlugins, 500);
        }
    } catch (err) {
        console.error("Failed to toggle plugin", err);
    }
}

export function requestPluginElevation(pluginId) {
    socket.emit('plugin_command', {
        plugin_id: pluginId,
        action: 'request_elevation',
        data: {}
    });
}

export async function showPluginInfo(pluginId) {
    try {
        const res = await secureFetch(`/api/plugins/${pluginId}/config`);
        const config = await res.json();

        const name = t(`plugin_name_${pluginId}`, config.name || pluginId);
        const desc = t(`plugin_desc_${pluginId}`, config.description || 'No description available.');
        
        showModal({
            title: name,
            content: `
                <div style="text-align: left; width: 100%; font-size: 14px; color: var(--text-muted);">
                    <p><strong>ID:</strong> ${pluginId}</p>
                    <p><strong>${t('label_version', 'Version')}:</strong> ${config.version || 'Unknown'}</p>
                    <p><strong>${t('label_author', 'Author')}:</strong> ${config.author_name || 'Unknown'}</p>
                    <p style="margin-top: 10px;">${desc}</p>
                </div>
            `
        });
    } catch (err) { console.error(err); }
}

export async function editPluginConfig(pluginId) {
    if (pluginId === 'yandex_station') {
        showModal({ title: t('yandex_settings_title', "Яндекс Станция"), content: `<div class="loading-spinner"></div>` });
        socket.emit('plugin_command', { plugin_id: 'yandex_station', action: 'get_wizard_data', data: {} });
        return;
    }

    try {
        const res = await secureFetch(`/api/plugins/${pluginId}/config`);
        const config = await res.json();
        const pluginName = t(`plugin_name_${pluginId}`, config.name || pluginId);

        showModal({
            title: `${t('label_settings', 'Settings')}: ${pluginName}`,
            content: `<div class="settings-content"></div>`
        });

        if (customHandlers[pluginId]) {
            customHandlers[pluginId].render(config);
        } else {
            // Fallback to JSON editor
            const content = document.querySelector('.settings-content');
            content.innerHTML = `
                <div id="json-error" style="color: #ef4444; font-size: 12px; margin-bottom: 8px; height: 18px; visibility: hidden;">Invalid JSON</div>
                <textarea id="config-editor" style="width:100%; height: 300px; background: #020617; color: #a5b4fc; font-family: monospace; padding: 10px; border-radius: 8px; border: 1px solid var(--border-color);"></textarea>
                <button class="plugin-btn" style="width: 100%; background: var(--accent); margin-top: 10px;" id="save-plugin-config-btn">${t('btn_save_changes', 'Save Changes')}</button>
            `;
            const editor = content.querySelector('#config-editor');
            const saveBtn = content.querySelector('#save-plugin-config-btn');
            const errorDiv = content.querySelector('#json-error');

            editor.value = JSON.stringify(config, null, 2);

            const validate = () => {
                try {
                    JSON.parse(editor.value);
                    errorDiv.style.visibility = 'hidden';
                    saveBtn.disabled = false;
                    saveBtn.style.opacity = '1';
                } catch (e) {
                    errorDiv.style.visibility = 'visible';
                    errorDiv.textContent = `Error: ${e.message}`;
                    saveBtn.disabled = true;
                    saveBtn.style.opacity = '0.5';
                }
            };

            editor.oninput = validate;
            saveBtn.onclick = () => savePluginConfig(pluginId, editor.value);
        }
    } catch (err) { console.error(err); }
}

async function savePluginConfig(pluginId, jsonString) {
    try {
        const newConfig = JSON.parse(jsonString);
        const res = await secureFetch(`/api/plugins/${pluginId}/config`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ config_data: newConfig })
        });
        if (res.ok) {
            closeModal();
            loadPlugins();
        }
    } catch (err) {
        alert("Invalid JSON format!");
    }
}

export function requestYandexQR() {
    showModal({
        title: t('yandex_auth_title', "Yandex Station Auth"),
        content: `<p>${t('requesting_qr', 'Requesting QR Code...')}</p>`
    });
    socket.emit('plugin_command', { plugin_id: 'yandex_station', action: 'start_qr_login', data: {} });
}

export function initPluginHandlers() {
    socket.on('plugin_state_changed', () => loadPlugins());
    
    // Global plugin event routing
    socket.onAny((event, payload) => {
        if (event.startsWith('plugin_event:')) {
            const pId = event.split(':')[1];
            if (customHandlers[pId] && customHandlers[pId].onEvent) {
                customHandlers[pId].onEvent(payload);
            }
        }
    });

    socket.on('plugin_event:yandex_station', (payload) => {
        if (payload.event === 'show_qr') {
            const data = payload.data;
            let html = `<p style="color: var(--accent); font-weight: 500;">${data.status || 'Waiting...'}</p>`;
            if (data.qr_url) {
                html += `<img src="${data.qr_url}" alt="QR Code" style="max-width: 250px; border-radius: 12px; margin: 15px 0;">`;
                html += `<p style="font-size: 13px; color: var(--text-muted);">${data.instructions || 'Scan with Yandex App'}</p>`;
            }
            showModal({ title: t('yandex_auth_title', "Yandex Station Auth"), content: html });
        }
    });

    window.addEventListener('monit:plugins-reload', () => loadPlugins());
}
