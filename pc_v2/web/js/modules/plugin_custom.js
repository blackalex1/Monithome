import { socket } from './socket.js';
import { secureFetch } from './api.js';
import { t } from './i18n.js';
import { showModal, closeModal, showToast } from './ui.js';

const modalContent = document.getElementById('modal-content');

export const customHandlers = {
    'system_stats': {
        render: async (config) => {
            let html = `<div class="settings-list">`;
            for (const [key, enabled] of Object.entries(config.enabled_sensors)) {
                const label = t(`sensor_${key}`, key);
                html += `
                    <div class="settings-item glass-panel" style="display:flex; justify-content: space-between; padding: 12px; margin-bottom: 8px;">
                        <span>${label}</span>
                        <label class="switch">
                            <input type="checkbox" id="sensor-${key}" ${enabled ? 'checked' : ''}>
                            <span class="slider"></span>
                        </label>
                    </div>
                `;
            }
            html += `</div>
            <button class="plugin-btn" style="width: 100%; background: var(--accent); margin-top: 15px;" id="save-sensors-btn">${t('btn_save_changes', 'Save Changes')}</button>`;
            
            modalContent.innerHTML = html;
            modalContent.querySelector('#save-sensors-btn').onclick = () => {
                const sensors = {};
                modalContent.querySelectorAll('input[type="checkbox"]').forEach(sw => {
                    sensors[sw.id.replace('sensor-', '')] = sw.checked;
                });
                socket.emit('plugin_command', { plugin_id: 'system_stats', action: 'update_sensor_settings', data: sensors });
                closeModal();
            };
        }
    },
    'pc_disks': {
        render: (config) => {
            modalContent.innerHTML = `<div class="loading-spinner"></div>`;
            socket.emit('plugin_command', { plugin_id: 'pc_disks', action: 'get_disks_for_settings', data: {} });
        },
        onEvent: (payload) => {
            if (payload.event === 'disks_for_settings') {
                const { all_disks, monitored_disks, show_new_disks } = payload.data;
                let html = `<div class="settings-list">
                    <div class="settings-item glass-panel" style="display:flex; justify-content: space-between; padding: 12px; margin-bottom: 15px; border-bottom: 2px solid var(--accent);">
                        <span style="font-weight: bold;">${t('setting_show_new_disks', 'Show new disks')}</span>
                        <label class="switch">
                            <input type="checkbox" id="disk-show-new" ${show_new_disks ? 'checked' : ''}>
                            <span class="slider"></span>
                        </label>
                    </div>
                    <p style="font-size: 12px; color: var(--text-muted); margin-bottom: 10px;">${t('setting_monitored_disks', 'Monitored Disks')}:</p>
                `;
                all_disks.forEach(disk => {
                    const isMonitored = monitored_disks.includes(disk.device);
                    html += `
                        <div class="settings-item glass-panel" style="display:flex; justify-content: space-between; padding: 10px; margin-bottom: 8px;">
                            <span>${disk.device} (${disk.label || 'Disk'})</span>
                            <label class="switch">
                                <input type="checkbox" class="disk-checkbox" data-device="${disk.device}" ${isMonitored ? 'checked' : ''}>
                                <span class="slider"></span>
                            </label>
                        </div>
                    `;
                });
                html += `</div><button class="plugin-btn" style="width: 100%; background: var(--accent); margin-top: 15px;" id="save-disks-btn">${t('btn_save_changes', 'Save Changes')}</button>`;
                modalContent.innerHTML = html;
                modalContent.querySelector('#save-disks-btn').onclick = () => {
                    const monitored = Array.from(modalContent.querySelectorAll('.disk-checkbox:checked')).map(cb => cb.getAttribute('data-device'));
                    socket.emit('plugin_command', {
                        plugin_id: 'pc_disks',
                        action: 'update_settings',
                        data: { monitored_disks: monitored, show_new_disks: modalContent.querySelector('#disk-show-new').checked }
                    });
                    closeModal();
                };
            }
        }
    },
    'app_launcher': {
        render: (config) => {
            renderAppLauncherUI(config);
        },
        onEvent: (payload) => {
            if (payload.event === 'config_updated') {
                secureFetch('/api/plugins/app_launcher/config').then(res => res.json()).then(renderAppLauncherUI);
            } else if (payload.event === 'file_selected') {
                const pathInput = document.getElementById('new-app-path');
                const nameInput = document.getElementById('new-app-name');
                const previewDiv = document.getElementById('new-app-icon-preview');
                if (pathInput) pathInput.value = payload.data.path;
                if (nameInput) nameInput.value = payload.data.label;
                if (previewDiv && payload.data.icon) {
                    previewDiv.innerHTML = `<img src="${payload.data.icon}" style="width: 100%; height: 100%; object-fit: contain; transform: scale(1.15);">`;
                    previewDiv.style.borderStyle = 'solid';
                    previewDiv.style.borderColor = 'var(--accent)';
                }
            }
        }
    },
    'yandex_station': {
        render: (config) => {
            modalContent.innerHTML = `<div class="loading-spinner"></div>`;
            socket.emit('plugin_command', { plugin_id: 'yandex_station', action: 'get_wizard_data', data: {} });
        },
        onEvent: (payload) => {
            if (payload.event === 'wizard_data') {
                const { devices, tablet_control, selected_device_ids } = payload.data;
                let html = `
                    <div class="yandex-settings">
                        <div class="settings-group glass-panel mb-4" style="padding: 15px;">
                            <div style="display: flex; align-items: center; justify-content: space-between;">
                                <div>
                                    <h4 style="color: var(--accent); margin-bottom: 4px;">${t('label_tablet_control', 'Управление с планшета')}</h4>
                                    <p style="font-size: 12px; color: var(--text-muted);">${t('desc_tablet_control', 'Разрешить планшету управлять колонками напрямую')}</p>
                                </div>
                                <label class="switch">
                                    <input type="checkbox" id="yandex-tablet-control" ${tablet_control ? 'checked' : ''}>
                                    <span class="slider"></span>
                                </label>
                            </div>
                        </div>

                        <div class="settings-group mb-4">
                            <h4 style="color: var(--accent); margin-bottom: 10px;">${t('label_select_speakers', 'Выберите колонки')}</h4>
                            <div class="devices-selection-list" style="display: flex; flex-direction: column; gap: 8px;">
                                ${devices.map(d => `
                                    <div class="settings-item glass-panel" style="display:flex; justify-content: space-between; padding: 12px;">
                                        <span>${d.name}</span>
                                        <label class="switch">
                                            <input type="checkbox" class="yandex-device-checkbox" data-id="${d.id}" ${selected_device_ids.includes(d.id) ? 'checked' : ''}>
                                            <span class="slider"></span>
                                        </label>
                                    </div>
                                `).join('') || `<p style="color: var(--text-muted);">${t('no_devices_found', 'Колонки не найдены')}</p>`}
                            </div>
                        </div>

                        <div class="settings-group mb-4">
                            <button class="plugin-btn" style="width: 100%; border-color: #f59e0b; color: #f59e0b; background: rgba(245, 158, 11, 0.1);" id="yandex-qr-login-btn">
                                🔑 ${t('btn_qr_login', 'Войти через QR-код')}
                            </button>
                        </div>

                        <button class="plugin-btn" style="width: 100%; background: var(--accent); color: white;" id="yandex-save-btn">${t('btn_save_changes', 'Сохранить изменения')}</button>
                    </div>
                `;
                modalContent.innerHTML = html;

                modalContent.querySelector('#yandex-qr-login-btn').onclick = () => {
                    socket.emit('plugin_command', { plugin_id: 'yandex_station', action: 'start_qr_login', data: {} });
                    modalContent.innerHTML = `<div class="loading-spinner"></div><p style="text-align:center; margin-top:10px;">${t('getting_qr', 'Получение QR-кода...')}</p>`;
                };

                modalContent.querySelector('#yandex-save-btn').onclick = () => {
                    const selected = Array.from(modalContent.querySelectorAll('.yandex-device-checkbox:checked')).map(cb => cb.getAttribute('data-id'));
                    const tablet = modalContent.querySelector('#yandex-tablet-control').checked;
                    socket.emit('plugin_command', {
                        plugin_id: 'yandex_station',
                        action: 'handle_wizard',
                        data: { selected_device_ids: selected, tablet_control: tablet }
                    });
                    closeModal();
                };
            } else if (payload.event === 'show_qr') {
                const { qr_url, status, instructions } = payload.data;
                modalContent.innerHTML = `
                    <div class="qr-container" style="text-align: center; padding: 20px;">
                        <h3 style="margin-bottom: 15px; color: var(--accent);">${status}</h3>
                        <div style="background: white; padding: 15px; border-radius: 15px; display: inline-block; margin-bottom: 15px;">
                            <img src="${qr_url}" style="width: 256px; height: 256px;">
                        </div>
                        <p style="color: var(--text-muted);">${instructions}</p>
                        <button class="plugin-btn mt-4" style="width: 100%;" id="yandex-back-to-settings">${t('btn_back', 'Назад')}</button>
                    </div>
                `;
                modalContent.querySelector('#yandex-back-to-settings').onclick = () => {
                    customHandlers['yandex_station'].render();
                };
            }
        }
    }
};

// Internal App Launcher UI Logic
function renderAppLauncherUI(config) {
    const widgets = config.widgets || [];
    const launcherWidget = widgets.find(w => w.id === "app_buttons") || widgets[0];
    const apps = launcherWidget ? (launcherWidget.buttons || []) : [];

    let html = `
        <div class="launcher-settings">
            <div class="settings-group" style="margin-bottom: 20px;">
                <h4 style="color: var(--accent); margin-bottom: 10px;">${t('label_current_apps', 'Текущие приложения')}</h4>
                <div class="apps-list" id="launcher-apps-list" style="display: flex; flex-direction: column; gap: 8px; max-height: 300px; overflow-y: auto; padding-right: 5px;">
                    ${apps.map((app, index) => `
                        <div class="glass-panel" draggable="true" data-index="${index}" style="display: flex; align-items: center; justify-content: space-between; padding: 10px; cursor: grab;">
                            <div style="display: flex; align-items: center; gap: 12px; pointer-events: none;">
                                <div style="color: var(--text-muted); font-size: 14px; margin-right: 5px;">☰</div>
                                <div style="width: 52px; height: 52px; display: flex; align-items: center; justify-content: center; overflow: hidden;">
                                    ${getLauncherIconHtml(app.icon)}
                                </div>
                                <div style="text-align: left;">
                                    <div style="font-weight: 500; color: white;">${app.label}</div>
                                    <div style="font-size: 10px; color: var(--text-muted); word-break: break-all; max-width: 200px;">${app.data}</div>
                                </div>
                            </div>
                            <button class="plugin-btn remove-app-btn" style="background: rgba(239, 68, 68, 0.1); color: #ef4444; border-color: rgba(239, 68, 68, 0.2); padding: 5px 10px;" data-label="${app.label}">✕</button>
                        </div>
                    `).join('') || `<p style="color: var(--text-muted);">${t('no_apps_added', 'Приложения не добавлены')}</p>`}
                </div>
            </div>
            <div class="settings-group glass-panel" style="padding: 15px; border: 1px solid var(--accent-glow);">
                <h4 style="color: var(--accent); margin-bottom: 12px;">${t('label_add_new', 'Добавить новое')}</h4>
                <div style="display: flex; gap: 15px; align-items: flex-start;">
                    <div id="new-app-icon-preview" style="width: 64px; height: 64px; background: rgba(255,255,255,0.05); border-radius: 12px; display: flex; align-items: center; justify-content: center; overflow: hidden; border: 1px dashed var(--border-color); flex-shrink: 0;">
                        <span style="font-size: 24px; opacity: 0.3;">🚀</span>
                    </div>
                    <div style="display: flex; flex-direction: column; gap: 10px; flex-grow: 1;">
                        <input type="text" id="new-app-name" placeholder="${t('placeholder_app_name', 'Название')}" style="width: 100%; background: #020617; border: 1px solid var(--border-color); color: white; padding: 8px; border-radius: 4px;">
                        <div style="display: flex; gap: 5px;">
                            <input type="text" id="new-app-path" placeholder="${t('placeholder_app_path', 'Путь')}" style="flex-grow: 1; background: #020617; border: 1px solid var(--border-color); color: white; padding: 8px; border-radius: 4px;">
                            <button class="plugin-btn" id="browse-app-btn" style="padding: 5px 12px; background: rgba(56, 189, 248, 0.2); color: #38bdf8; border-color: rgba(56, 189, 248, 0.3);">📁</button>
                        </div>
                    </div>
                </div>
                <button class="plugin-btn" style="width: 100%; background: var(--accent); color: white; margin-top: 15px;" id="add-app-btn">${t('btn_add_to_list', '+ Добавить')}</button>
            </div>
        </div>
    `;
    modalContent.innerHTML = html;

    // Handlers
    modalContent.querySelector('#browse-app-btn').onclick = () => socket.emit('plugin_command', { plugin_id: 'app_launcher', action: 'browse_file', data: {} });
    modalContent.querySelector('#add-app-btn').onclick = () => {
        const name = document.getElementById('new-app-name').value;
        const path = document.getElementById('new-app-path').value;
        const iconImg = document.getElementById('new-app-icon-preview').querySelector('img');
        if (!name || !path) return;
        socket.emit('plugin_command', { plugin_id: 'app_launcher', action: 'add_app', data: { label: name, path, icon: iconImg ? iconImg.src : null } });
        modalContent.innerHTML = '<div class="loading-spinner"></div>';
    };
    modalContent.querySelectorAll('.remove-app-btn').forEach(btn => {
        btn.onclick = () => {
            const label = btn.getAttribute('data-label');
            if (confirm(`${t('confirm_delete', 'Удалить')} "${label}"?`)) {
                socket.emit('plugin_command', { plugin_id: 'app_launcher', action: 'remove_app', data: label });
            }
        };
    });
}

function getLauncherIconHtml(iconData) {
    if (iconData && iconData.startsWith('data:image')) {
        return `<img src="${iconData}" style="width: 100%; height: 100%; object-fit: contain; transform: scale(1.15);">`;
    }
    return `<span style="font-size: 20px;">🚀</span>`;
}
