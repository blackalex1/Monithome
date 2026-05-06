import { getAuthToken } from './api.js';
import { applyThemeColor, showToast } from './ui.js';
import { t } from './i18n.js';

export const socket = io("http://127.0.0.1:5000", {
    auth: {
        token: getAuthToken()
    }
});

const statusText = document.getElementById('connection-status');
const dot = document.getElementById('connection-dot');

export function initSocketHandlers() {
    socket.on('connect', () => {
        if (dot) dot.classList.add('connected');
        updateConnectionStatus('status_connected', 'Server Connected');
    });

    socket.on('disconnect', () => {
        if (dot) dot.classList.remove('connected');
        updateConnectionStatus('status_disconnected', 'Disconnected');
    });

    socket.on('ui_config', (data) => {
        if (data.theme_color) applyThemeColor(data.theme_color);
        // Dispatch custom event for plugins to reload
        window.dispatchEvent(new CustomEvent('monit:plugins-reload'));
    });

    socket.on('theme_update', (data) => {
        if (data.theme_color) applyThemeColor(data.theme_color);
    });

    socket.on('stats_json', (payload) => {
        const stats = payload.stats;
        if (stats.system_stats) {
            const s = stats.system_stats;
            if (s.cpu !== undefined) updateValue('cpu-val', `${Math.round(s.cpu)}%`);
            if (s.ram_percent !== undefined) updateValue('ram-val', `${Math.round(s.ram_percent)}%`);
            if (s.cpu_temp !== undefined) updateValue('temp-val', `${Math.round(s.cpu_temp)}°C`);
        }
    });

    socket.on('connected_devices', (devices) => {
        window.dispatchEvent(new CustomEvent('monit:devices-update', { detail: devices }));
    });
}

function updateConnectionStatus(key, fallback) {
    if (statusText) {
        statusText.textContent = t(key, fallback);
        statusText.setAttribute('data-i18n', key);
    }
}

function updateValue(id, value) {
    const el = document.getElementById(id);
    if (el) el.textContent = value;
}
