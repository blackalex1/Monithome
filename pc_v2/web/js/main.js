import { initSocketHandlers, socket } from './modules/socket.js';
import { registerAuthHandlers } from './modules/auth.js';
import { initNavigation, applyThemeColor, showToast } from './modules/ui.js';
import { switchLanguage, translations, applyTranslations, t } from './modules/i18n.js';
import { loadPlugins, initPluginHandlers } from './modules/plugins.js';
import { secureFetch } from './modules/api.js';

async function init() {
    console.log("Initializing MonitHome...");

    // 1. Initial UI Setup
    initNavigation();

    // 2. Load Config & i18n
    try {
        const res = await secureFetch('/api/config');
        const config = await res.json();

        // Load language
        await switchLanguage(config.language || 'ru');

        // Apply theme
        if (config.theme_color) applyThemeColor(config.theme_color);

        // Sync settings form
        syncSettingsForm(config);
    } catch (err) {
        console.error("Failed to load initial config", err);
    }

    // 3. Initialize Socket & Handlers
    initSocketHandlers();
    registerAuthHandlers(socket);
    initPluginHandlers();

    // 4. Initial Content Load
    loadPlugins();
    initDevicesLogic();
    initLogsLogic();

    console.log("Initialization complete.");
}

function syncSettingsForm(config) {
    const hostname = document.getElementById('server-hostname');
    const lang = document.getElementById('server-language');
    const autostart = document.getElementById('server-autostart');
    const hex = document.getElementById('server-theme-hex');
    const picker = document.getElementById('server-theme-picker');

    if (hostname) hostname.value = config.hostname || '';
    if (lang) lang.value = config.language || 'ru';
    if (autostart) autostart.checked = config.autostart || false;
    if (hex) hex.value = config.theme_color || '0xFF22C55E';
    
    if (picker && config.theme_color) {
        const c = config.theme_color;
        picker.value = c.startsWith('0xFF') ? '#' + c.substring(4) : c.replace('0x', '#');
    }

    // Global settings form submit
    const form = document.getElementById('global-settings-form');
    if (form) {
        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            const btn = document.getElementById('save-config-btn');
            const originalText = btn.textContent;

            btn.textContent = t('btn_saving', 'Saving...');
            btn.disabled = true;

            const newConfig = {
                hostname: hostname.value,
                language: lang.value,
                theme_color: hex.value,
                autostart: autostart.checked
            };

            try {
                const res = await secureFetch('/api/config', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(newConfig)
                });

                if (res.ok) {
                    btn.textContent = t('btn_saved', 'Saved!');
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
    }
}

function initDevicesLogic() {
    const list = document.getElementById('devices-list');
    window.addEventListener('monit:devices-update', (e) => {
        const devices = e.detail;
        if (!list) return;

        if (!devices || devices.length === 0) {
            list.innerHTML = `<p class="no-devices" data-i18n="no_devices_connected">${t('no_devices_connected', 'No external devices connected')}</p>`;
            return;
        }

        list.innerHTML = '';
        devices.forEach(dev => {
            const card = document.createElement('div');
            card.className = 'device-card glass-panel';
            
            let icon = '🌐';
            if (dev.type === 'PC GUI') icon = '💻';
            else if (dev.type === 'Tablet') icon = '📱';

            card.innerHTML = `
                <div class="device-icon">${icon}</div>
                <div class="device-info">
                    <div class="device-name">${dev.type}</div>
                    <div class="device-detail">IP: ${dev.ip}</div>
                    <div class="device-ua" title="${dev.ua}">${dev.ua}</div>
                </div>
                <div class="device-status"><span class="dot connected"></span>Online</div>
                ${dev.type !== 'PC GUI' ? `<button class="plugin-btn danger kick-btn">Disconnect</button>` : ''}
            `;
            const kickBtn = card.querySelector('.kick-btn');
            if (kickBtn) {
                kickBtn.onclick = () => {
                    if (confirm(t('confirm_kick', "Disconnect this device?"))) {
                        socket.emit('kick_device', { sid: dev.sid });
                    }
                };
            }
            list.appendChild(card);
        });
    });
}

function initLogsLogic() {
    const logOutput = document.getElementById('log-output');
    const MAX_LOG_LINES = 200;
    let currentLogLevel = 'all';
    let allLogs = [];

    socket.on('server_log', (payload) => {
        const msg = payload.message || '';
        let level = 'info';
        if (msg.includes('[WARNING]')) level = 'warning';
        else if (msg.includes('[ERROR]')) level = 'error';

        allLogs.push({ message: msg, level: level });
        if (allLogs.length > MAX_LOG_LINES) allLogs.shift();

        if (currentLogLevel === 'all' || currentLogLevel === level) {
            appendLog(msg, level);
        }
    });

    function appendLog(msg, level) {
        if (!logOutput) return;
        const div = document.createElement('div');
        div.className = `log-line log-${level}`;
        div.textContent = msg;
        logOutput.appendChild(div);
        logOutput.parentElement.scrollTop = logOutput.parentElement.scrollHeight;
        while (logOutput.children.length > MAX_LOG_LINES) logOutput.removeChild(logOutput.firstChild);
    }

    document.querySelectorAll('.filter-btn').forEach(btn => {
        btn.onclick = (e) => {
            document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
            e.target.classList.add('active');
            currentLogLevel = e.target.getAttribute('data-level');
            logOutput.innerHTML = '';
            allLogs.forEach(l => {
                if (currentLogLevel === 'all' || currentLogLevel === l.level) appendLog(l.message, l.level);
            });
        };
    });
}

// Global scope exposure for legacy/inline JS if needed (optional)
window.monit = { socket, t, secureFetch };

document.addEventListener('DOMContentLoaded', init);
