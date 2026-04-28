import { useEffect, useState, useRef } from 'react';
import { io, Socket } from 'socket.io-client';
import { 
  Puzzle, 
  Layers, 
  Upload, 
  Monitor
} from 'lucide-react';
import { motion, AnimatePresence, Reorder } from 'framer-motion';

// --- Imports ---
import type { PluginInfo, MasterConfig } from './types';
import { LayoutItem } from './components/LayoutItem';
import { PluginCard } from './components/PluginCard';
import { WizardModal } from './components/WizardModal';
import { InfoModal } from './components/InfoModal';
import { translations } from './i18n';
import type { Language } from './i18n';

function App() {
  const [socket, setSocket] = useState<Socket | null>(null);
  const [connected, setConnected] = useState(false);
  const [activeTab, setActiveTab] = useState('plugins');
  const activeTabRef = useRef(activeTab);
  
  useEffect(() => {
    activeTabRef.current = activeTab;
  }, [activeTab]);
  
  const [allPlugins, setAllPlugins] = useState<PluginInfo[]>([]);
  const [masterConfig, setMasterConfig] = useState<MasterConfig | null>(null);
  
  const [lang, setLang] = useState<Language>('ru');

  const t = translations[lang];

  useEffect(() => {
    if (masterConfig?.language) {
      setLang(masterConfig.language as Language);
    }
  }, [masterConfig?.language]);
  
  // Modal State
  const [editingPlugin, setEditingPlugin] = useState<PluginInfo | null>(null);
  const [infoPlugin, setInfoPlugin] = useState<PluginInfo | null>(null);
  const [configText, setConfigText] = useState('');
  const [wizardData, setWizardData] = useState<any>(null);
  const [selectedWizardItems, setSelectedWizardItems] = useState<string[]>([]);
  const [isWizardLoading, setIsWizardLoading] = useState(false);
  const [pairingRequest, setPairingRequest] = useState<{sid: string, code: string} | null>(null);

  useEffect(() => {
    const s = io();
    setSocket(s);
    
    s.on('connect', () => {
      setConnected(true);
      s.emit('get_manager_data');
    });
    
    s.on('disconnect', () => setConnected(false));
    
    s.on('manager_data', (data) => {
      setAllPlugins(data.all_plugins);
      setMasterConfig(data.master_config);
    });

    s.on('pairing_request', (data) => {
      setPairingRequest(data);
    });

    s.on('pairing_complete', () => {
      setPairingRequest(null);
    });

    s.on('pairing_cancel', () => {
      setPairingRequest(null);
    });

    s.on('wizard_data', (data) => {
      setWizardData(data.wizard);
      
      const currentIds: string[] = [];
      const plugin = data.plugin_info;
      
      if (plugin) {
        if (plugin.id === 'system_stats') {
          // Рекурсивный поиск data_key во всех виджетах (включая вложенные в строки)
          const findKeys = (widgets: any[]) => {
            widgets?.forEach(w => {
              if (w.data_key) {
                if (w.data_key === 'ram_combined') {
                  currentIds.push('ram_percent', 'ram_used');
                } else {
                  currentIds.push(w.data_key);
                }
              }
              if (w.children) findKeys(w.children);
            });
          };
          findKeys(plugin.config?.widgets || []);
        } else if (plugin.id === 'yandex_station') {
          if (plugin.config?.selected_device_ids) {
            currentIds.push(...plugin.config.selected_device_ids);
          } else {
            plugin.config?.widgets?.forEach((w: any) => { if (w?.device_id) currentIds.push(w.device_id); });
          }
        } else if (plugin.id === 'pc_system') {
          plugin.config?.actions?.[0]?.buttons?.forEach((b: any) => { if (b?.action) currentIds.push(b.action); });
        } else if (plugin.id === 'pc_media') {
          if (plugin.config?.pc_enabled) currentIds.push('pc_control');
        } else if (plugin.id === 'pc_disks') {
          if (plugin.config?.selected_disks) {
            currentIds.push(...plugin.config.selected_disks);
          }
        }
      }
      
      setSelectedWizardItems(currentIds);
      setIsWizardLoading(false);
    });

    return () => { s.disconnect(); };
  }, []);

  const saveMaster = (newConfig: MasterConfig) => {
    socket?.emit('save_master_config', newConfig);
    setMasterConfig(newConfig);
  };

  const togglePlugin = (id: string) => {
    if (!masterConfig) return;
    const active = masterConfig.active_plugins.includes(id);
    const pInfo = allPlugins.find(p => p.id === id);
    
    if (!active && pInfo?.config?.dependencies) {
      const missing = pInfo.config.dependencies.filter((depId: string) => !masterConfig.active_plugins.includes(depId));
      if (missing.length > 0) {
        const names = missing.map((mId: string) => allPlugins.find(p => p.id === mId)?.name || mId).join(', ');
        alert(`Для работы '${pInfo.name}' необходимо сначала включить: ${names}`);
        return;
      }
    } 
    
    if (active) {
      const dependents = allPlugins.filter(p => 
        masterConfig.active_plugins.includes(p.id) && 
        p.config?.dependencies?.includes(id)
      );
      
      if (dependents.length > 0) {
        const names = dependents.map(p => p.name).join(', ');
        if (!confirm(`Выключение '${pInfo?.name}' повлияет на работу следующих плагинов: ${names}. Продолжить?`)) {
          return;
        }
      }
    }

    const newActive = active 
      ? masterConfig.active_plugins.filter(p => p !== id)
      : [...masterConfig.active_plugins, id];
    
    saveMaster({ ...masterConfig, active_plugins: newActive });
  };

  const movePlugin = (idx: number, direction: 'up' | 'down') => {
    if (!masterConfig) return;
    const newOrder = [...masterConfig.active_plugins];
    const targetIdx = direction === 'up' ? idx - 1 : idx + 1;
    if (targetIdx < 0 || targetIdx >= newOrder.length) return;
    
    [newOrder[idx], newOrder[targetIdx]] = [newOrder[targetIdx], newOrder[idx]];
    saveMaster({ ...masterConfig, active_plugins: newOrder });
  };

  const updateOrder = (newOrder: string[]) => {
    if (!masterConfig) return;
    saveMaster({ ...masterConfig, active_plugins: newOrder });
  };

  const changeLanguage = (newLang: Language) => {
    if (!masterConfig) return;
    saveMaster({ ...masterConfig, language: newLang });
    setLang(newLang);
  };

  const openEditor = (plugin: PluginInfo) => {
    setEditingPlugin(plugin);
    setConfigText(JSON.stringify(plugin.config || {}, null, 2));
    setWizardData(null);
    setSelectedWizardItems([]);
    setIsWizardLoading(true);
    socket?.emit('plugin_command', { plugin_id: plugin.id, target: 'all', action: 'get_wizard' });
    
    setTimeout(() => {
      setIsWizardLoading(prev => {
        if (prev) console.log("Wizard timeout - falling back to JSON");
        return false;
      });
    }, 3000);
  };

  const applyWizard = () => {
    if (!editingPlugin) return;
    socket?.emit('apply_plugin_wizard', { plugin_id: editingPlugin.id, selections: selectedWizardItems });
    setEditingPlugin(null);
  };

  const savePluginConfig = () => {
    if (!editingPlugin) return;
    try {
      const parsed = JSON.parse(configText);
      socket?.emit('save_plugin_config', { id: editingPlugin.id, config: parsed });
      setEditingPlugin(null);
    } catch (e) {
      alert("Invalid JSON format!");
    }
  };

  return (
    <div className="app-container">
      <aside className="sidebar">
        <div className="sidebar-logo">
          <Monitor size={28} />
          <span>MONITHOME</span>
        </div>
        
        <nav className="nav-links">
          <div className={`nav-item ${activeTab === 'plugins' ? 'active' : ''}`} onClick={() => setActiveTab('plugins')}>
            <Puzzle size={20} /> {t.sidebar.plugins}
          </div>
          <div className={`nav-item ${activeTab === 'layout' ? 'active' : ''}`} onClick={() => setActiveTab('layout')}>
            <Layers size={20} /> {t.sidebar.layout}
          </div>
          <div className={`nav-item ${activeTab === 'import' ? 'active' : ''}`} onClick={() => setActiveTab('import')}>
            <Upload size={20} /> {t.sidebar.import}
          </div>
        </nav>

        <div style={{ padding: '0 1.5rem', marginBottom: '2rem' }}>
          <div style={{ 
            display: 'flex', 
            background: 'rgba(255,255,255,0.05)', 
            borderRadius: '10px', 
            padding: '4px',
            border: '1px solid rgba(255,255,255,0.05)'
          }}>
            <button 
              onClick={() => changeLanguage('ru')}
              style={{ 
                flex: 1, 
                padding: '6px', 
                borderRadius: '8px', 
                border: 'none',
                background: lang === 'ru' ? 'var(--accent-cyan)' : 'transparent',
                color: lang === 'ru' ? '#000' : '#fff',
                fontSize: '0.75rem',
                fontWeight: 700,
                cursor: 'pointer',
                transition: 'all 0.2s'
              }}
            >
              RU
            </button>
            <button 
              onClick={() => changeLanguage('en')}
              style={{ 
                flex: 1, 
                padding: '6px', 
                borderRadius: '8px', 
                border: 'none',
                background: lang === 'en' ? 'var(--accent-cyan)' : 'transparent',
                color: lang === 'en' ? '#000' : '#fff',
                fontSize: '0.75rem',
                fontWeight: 700,
                cursor: 'pointer',
                transition: 'all 0.2s'
              }}
            >
              EN
            </button>
          </div>
        </div>

        <div style={{ marginTop: 'auto' }}>
          <div className="status-badge" style={!connected ? { color: '#ff4444', background: 'rgba(255,0,0,0.1)', borderColor: 'rgba(255,0,0,0.2)' } : {}}>
            <div style={{ 
              width: 8, height: 8, borderRadius: '50%', 
              background: connected ? '#00ff88' : '#ff4444',
              boxShadow: `0 0 10px ${connected ? '#00ff88' : '#ff4444'}`
            }} />
            {connected ? t.sidebar.serverOnline : t.sidebar.serverOffline}
          </div>

          <a 
            href="https://github.com/blackalex1/Monithome" 
            target="_blank" 
            rel="noreferrer"
            className="nav-item"
            style={{ marginTop: '1rem', border: '1px solid rgba(255,255,255,0.05)', background: 'rgba(255,255,255,0.02)' }}
          >
            <svg 
              width="20" height="20" 
              viewBox="0 0 24 24" 
              fill="none" 
              stroke="currentColor" 
              strokeWidth="2" 
              strokeLinecap="round" 
              strokeLinejoin="round"
            >
              <path d="M15 22v-4a4.8 4.8 0 0 0-1-3.5c3 0 6-2 6-5.5.08-1.25-.27-2.48-1-3.5.28-1.15.28-2.35 0-3.5 0 0-1 0-3 1.5-2.64-.5-5.36-.5-8 0C6 2 5 2 5 2c-.28 1.15-.28 2.35 0 3.5A5.403 5.403 0 0 0 4 9c0 3.5 3 5.5 6 5.5-.39.49-.68 1.05-.85 1.65-.17.6-.22 1.23-.15 1.85v4"></path>
              <path d="M9 18c-4.51 2-5-2-7-2"></path>
            </svg>
            <span style={{ fontSize: '0.8rem' }}>GitHub Project</span>
          </a>
        </div>
      </aside>

      <main className="main-content">
        <header className="header">
          <div>
            <h1 style={{ fontSize: '1.75rem', fontWeight: 700 }}>
              {activeTab === 'plugins' ? t.header.managePlugins : 
               activeTab === 'layout' ? t.header.tabletOrder : t.header.importPlugin}
            </h1>
            <p style={{ color: 'rgba(255,255,255,0.5)', marginTop: '0.25rem' }}>
              {t.header.subtitle}
            </p>
          </div>
        </header>

        <AnimatePresence mode="wait">
          {activeTab === 'plugins' && (
            <motion.div key="plugins" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -10 }} className="stats-grid">
              {allPlugins.map((plugin) => (
                <PluginCard 
                  key={plugin.id} 
                  plugin={plugin} 
                  allPlugins={allPlugins}
                  togglePlugin={togglePlugin} 
                  openEditor={openEditor} 
                  openInfo={(p) => setInfoPlugin(p)}
                  t={t}
                />
              ))}
            </motion.div>
          )}

          {activeTab === 'layout' && (
            <motion.div key="layout" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -10 }} className="layout-container">
              <Reorder.Group axis="y" values={masterConfig?.active_plugins || []} onReorder={updateOrder} className="layout-list" as="div">
                {masterConfig?.active_plugins
                  .filter(pId => {
                    const pInfo = allPlugins.find(p => p.id === pId);
                    return (pInfo?.config?.widgets?.length || 0) > 0 || (pInfo?.config?.actions?.length || 0) > 0;
                  })
                  .map((pId, idx, filteredArr) => (
                  <LayoutItem 
                    key={pId} pId={pId} idx={idx} 
                    pInfo={allPlugins.find(p => p.id === pId)}
                    movePlugin={movePlugin} isLast={idx === filteredArr.length - 1}
                  />
                ))}
              </Reorder.Group>
            </motion.div>
          )}

          {activeTab === 'import' && (
             <motion.div key="import" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -10 }} className="glass-card" style={{ textAlign: 'center', padding: '4rem 2rem' }}>
                <Upload size={40} color="var(--accent-cyan)" />
                <h2 style={{ fontSize: '1.5rem', fontWeight: 700, margin: '2rem 0 1rem' }}>{t.import.title}</h2>
                <button className="control-btn" style={{ padding: '0.75rem 2rem', background: 'var(--accent-cyan)', color: '#000', border: 'none', fontWeight: 700 }}>
                  {t.import.button}
                </button>
             </motion.div>
          )}
        </AnimatePresence>

        {editingPlugin && (
          <WizardModal 
            editingPlugin={editingPlugin}
            isWizardLoading={isWizardLoading}
            wizardData={wizardData}
            selectedWizardItems={selectedWizardItems}
            setSelectedWizardItems={setSelectedWizardItems}
            configText={configText}
            setConfigText={setConfigText}
            onClose={() => setEditingPlugin(null)}
            onApplyWizard={applyWizard}
            onSaveConfig={savePluginConfig}
            onPluginCommand={(action) => {
              socket?.emit('plugin_command', { plugin_id: editingPlugin.id, action });
            }}
            t={t}
          />
        )}

        {infoPlugin && (
          <InfoModal 
            plugin={infoPlugin} 
            allPlugins={allPlugins} 
            onClose={() => setInfoPlugin(null)} 
            t={t}
          />
        )}
      </main>
      {/* Pairing Modal */}
      {pairingRequest && (
        <div className="wizard-overlay" style={{ zIndex: 1000 }}>
          <div className="glass-card" style={{ maxWidth: '450px', width: '90%', textAlign: 'center', padding: '3rem' }}>
             <h2 style={{ fontSize: '2rem', marginBottom: '1rem', color: 'var(--accent-cyan)' }}>{t.pairing.title}</h2>
             <p style={{ opacity: 0.8, marginBottom: '2rem', lineHeight: 1.5 }}>{t.pairing.description}</p>
             
             <div style={{ 
               fontSize: '4rem', 
               fontWeight: '900', 
               letterSpacing: '12px', 
               margin: '2rem 0', 
               color: '#fff',
               background: 'rgba(255,255,255,0.05)',
               padding: '1.5rem',
               borderRadius: '20px',
               textShadow: '0 0 20px rgba(56, 189, 248, 0.5)'
             }}>
               {pairingRequest.code}
             </div>
             
             <button 
               className="control-btn" 
               style={{ width: '100%', marginTop: '1rem', background: 'rgba(239, 68, 68, 0.2)', borderColor: 'rgba(239, 68, 68, 0.3)' }} 
               onClick={() => {
                 socket?.emit('cancel_pairing', { sid: pairingRequest.sid });
                 setPairingRequest(null);
               }}
             >
               {t.pairing.cancel}
             </button>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
