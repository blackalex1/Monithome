const socket = io("http://127.0.0.1:5000");

// Connection UI
const dot = document.getElementById('connection-dot');
const statusText = document.getElementById('connection-status');

socket.on('connect', () => {
    dot.classList.add('connected');
    statusText.textContent = 'Server Connected';
});

socket.on('disconnect', () => {
    dot.classList.remove('connected');
    statusText.textContent = 'Disconnected';
});

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
            
            card.innerHTML = `
                <div class="plugin-header">
                    <div>
                        <h3 class="plugin-title">${plugin.name}</h3>
                        <p class="plugin-desc">${plugin.description || 'No description available.'}</p>
                    </div>
                    <label class="switch">
                        <input type="checkbox" onchange="togglePlugin('${plugin.id}', this.checked)" ${plugin.active ? 'checked' : ''}>
                        <span class="slider"></span>
                    </label>
                </div>
                <div class="plugin-actions">
                    <span style="font-size: 13px; color: ${plugin.active ? 'var(--success)' : 'var(--text-muted)'}">
                        ${plugin.active ? '● Running' : '○ Stopped'}
                    </span>
                    <div style="display:flex; gap: 8px;">
                        <button class="plugin-btn" onclick="showPluginInfo('${plugin.id}')" title="Info">ℹ️</button>
                        <button class="plugin-btn" onclick="editPluginConfig('${plugin.id}')" title="Settings">⚙️</button>
                        ${plugin.id === 'yandex_station' && plugin.active ? `<button class="plugin-btn" onclick="requestYandexQR()">Login with QR</button>` : ''}
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
        if(res.ok) {
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
        
        modalTitle.textContent = config.name || pluginId;
        modalContent.innerHTML = `
            <div style="text-align: left; width: 100%; font-size: 14px; color: var(--text-muted);">
                <p><strong>ID:</strong> ${pluginId}</p>
                <p><strong>Version:</strong> ${config.version || 'Unknown'}</p>
                <p><strong>Author:</strong> ${config.author_name || 'Unknown'}</p>
                <p style="margin-top: 10px;">${config.description || 'No description.'}</p>
            </div>
        `;
        modal.classList.remove('hidden');
    } catch (err) { console.error(err); }
};

window.editPluginConfig = async (pluginId) => {
    if (pluginId === 'yandex_station') {
        modalTitle.textContent = "Настройки Яндекс Станции";
        modalContent.innerHTML = `<div class="loading-spinner"></div><p>Загрузка списка устройств...</p>`;
        modal.classList.remove('hidden');
        socket.emit('plugin_command', { plugin_id: 'yandex_station', action: 'get_wizard_data', data: {} });
        return;
    }

    try {
        const res = await fetch(`/api/plugins/${pluginId}/config`);
        const config = await res.json();
        
        modalTitle.textContent = `Settings: ${pluginId}`;
        modalContent.innerHTML = `
            <textarea id="config-editor" style="width:100%; height: 300px; background: #020617; color: #a5b4fc; font-family: monospace; padding: 10px; border-radius: 8px; border: 1px solid var(--border-color);"></textarea>
            <button class="plugin-btn" style="width: 100%; background: var(--accent); margin-top: 10px;" onclick="savePluginConfig('${pluginId}')">Save Changes</button>
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
        if(res.ok) {
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
        if(modal.classList.contains('hidden')) {
            modalTitle.textContent = "Yandex Station Auth";
            modal.classList.remove('hidden');
        }
    } else if (payload.event === 'wizard_data') {
        const data = payload.data;
        modalTitle.textContent = "Настройки Яндекс Станции";
        
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
                    <h4 style="color: var(--accent); margin-bottom: 10px;">Режим управления</h4>
                    <label style="display: flex; align-items: center; gap: 10px; cursor: pointer;">
                        <input type="checkbox" id="tablet-control-toggle" ${data.tablet_control ? 'checked' : ''}>
                        <span style="color: white;">Управление через планшет (Direct Glagol)</span>
                    </label>
                    <p style="font-size: 12px; color: var(--text-muted); margin-top: 5px;">
                        В этом режиме планшет подключается к Станции напрямую. Если выключено — управляет ПК.
                    </p>
                </div>

                <div class="settings-group">
                    <h4 style="color: var(--accent); margin-bottom: 10px;">Видимые колонки</h4>
                    <div id="devices-list-wizard">
                        ${devicesHtml || '<p style="color: var(--text-muted);">Колонки не найдены. Авторизуйтесь сначала.</p>'}
                    </div>
                </div>

                <button class="plugin-btn" style="width: 100%; background: var(--success); margin-top: 20px; color: white;" onclick="saveYandexWizard()">Сохранить настройки</button>
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
