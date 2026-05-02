import { X, Check, RefreshCw } from 'lucide-react';
import type { PluginInfo } from '../types';

interface WizardModalProps {
  editingPlugin: PluginInfo;
  isWizardLoading: boolean;
  wizardData: any;
  selectedWizardItems: string[];
  setSelectedWizardItems: React.Dispatch<React.SetStateAction<string[]>>;
  configText: string;
  setConfigText: (val: string) => void;
  onClose: () => void;
  onApplyWizard: () => void;
  onSaveConfig: () => void;
  onPluginCommand: (action: string) => void;
  t: any;
}

export function WizardModal({
  editingPlugin,
  isWizardLoading,
  wizardData,
  selectedWizardItems,
  setSelectedWizardItems,
  configText,
  setConfigText,
  onClose,
  onApplyWizard,
  onSaveConfig,
  onPluginCommand,
  t
}: WizardModalProps) {
  return (
    <div className="modal-overlay">
      <div className="modal-content" style={{ maxWidth: editingPlugin.id === 'system_stats' ? '600px' : '800px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '15px' }}>
            <h2 style={{ fontSize: '1.5rem', fontWeight: 700, margin: 0 }}>{t.wizard.title}: {editingPlugin.name}</h2>
            {wizardData?.actions?.map((action: any) => (
              <button 
                key={action.id} 
                className="control-btn" 
                style={{ padding: '4px 10px', fontSize: '0.75rem', gap: '6px' }}
                onClick={() => onPluginCommand(action.id)}
                title={action.label}
              >
                {action.icon === 'RefreshCw' && <RefreshCw size={14} />}
                <span>{action.label}</span>
              </button>
            ))}
          </div>
          <button className="action-btn" onClick={onClose}><X size={24} /></button>
        </div>
        
        {isWizardLoading ? (
          <div style={{ textAlign: 'center', padding: '3rem 0' }}>
            <div className="loader" style={{ margin: '0 auto 1.5rem' }}></div>
            <p style={{ color: 'rgba(255,255,255,0.5)' }}>{t.wizard.loading}</p>
          </div>
        ) : wizardData ? (
          <div style={{ padding: '0.5rem 0' }}>
            <p style={{ fontSize: '0.875rem', color: 'rgba(255,255,255,0.5)', marginBottom: '1.5rem' }}>
              {wizardData.description}
            </p>
            
            <div style={{ display: 'flex', flexDirection: 'column', gap: '15px', marginBottom: '2rem' }}>
              {/* Informational items (text, images, buttons, links) */}
              {wizardData?.items?.filter((i: any) => i.type && i.type !== 'yandex_station' && i.type !== 'setting').map((item: any) => {
                if (item.type === 'text') {
                  return (
                    <div key={item.id} style={{ padding: '4px 0', fontSize: '0.95rem', color: item.color || '#fff', textAlign: 'center' }}>
                      {item.label}
                      {item.description && <div style={{ fontSize: '0.8rem', opacity: 0.6, marginTop: '4px' }}>{item.description}</div>}
                    </div>
                  );
                }

                if (item.type === 'image' || item.type === 'qr') {
                  return (
                    <div key={item.id} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '10px', padding: '15px', background: 'rgba(255,255,255,0.03)', borderRadius: '12px' }}>
                      <div style={{ background: '#fff', padding: '10px', borderRadius: '8px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                        <img src={item.url} alt={item.label} style={{ width: item.size || '250px', height: 'auto' }} />
                      </div>
                      {item.label && <span style={{ fontSize: '0.85rem', opacity: 0.8 }}>{item.label}</span>}
                    </div>
                  );
                }

                if (item.type === 'button') {
                  return (
                    <button 
                      key={item.id} 
                      className="control-btn" 
                      style={{ width: '100%', padding: '12px', justifyContent: 'center', background: 'var(--accent-cyan)', color: '#000' }}
                      onClick={() => onPluginCommand(item.id.replace('action:', ''))}
                    >
                      {item.label}
                    </button>
                  );
                }

                if (item.type === 'link') {
                   return (
                     <div key={item.id} style={{ textAlign: 'center' }}>
                       <a href={item.url} target="_blank" rel="noreferrer" style={{ color: 'var(--accent-cyan)', textDecoration: 'none', wordBreak: 'break-all', fontSize: '0.9rem' }}>
                         {item.label}
                       </a>
                     </div>
                   );
                }
                return null;
              })}

              {/* Selectable settings/devices in a grid */}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                {wizardData?.items?.filter((i: any) => !i.type || i.type === 'yandex_station' || i.type === 'setting').map((item: any) => {
                  const isSelected = selectedWizardItems.includes(item.id);
                  return (
                    <div 
                      key={item.id}
                      onClick={() => {
                        setSelectedWizardItems(prev => 
                          isSelected ? prev.filter(id => id !== item.id) : [...prev, item.id]
                        );
                      }}
                      style={{ 
                        background: isSelected ? 'rgba(0, 242, 255, 0.1)' : 'rgba(255,255,255,0.03)',
                        border: `1px solid ${isSelected ? 'var(--accent-cyan)' : 'rgba(255,255,255,0.1)'}`,
                        borderRadius: '12px',
                        padding: '12px',
                        cursor: 'pointer',
                        display: 'flex',
                        alignItems: 'center',
                        gap: '12px',
                        transition: 'all 0.2s'
                      }}
                    >
                      <div style={{ 
                        width: 20, height: 20, borderRadius: '6px', 
                        border: '2px solid var(--accent-cyan)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        background: isSelected ? 'var(--accent-cyan)' : 'transparent'
                      }}>
                        {isSelected && <Check size={14} color="#000" strokeWidth={4} />}
                      </div>
                      <span style={{ fontSize: '0.9rem', fontWeight: isSelected ? '600' : '400' }}>{item.label}</span>
                    </div>
                  );
                })}
              </div>
            </div>
            
            <div style={{ display: 'flex', gap: '1rem', justifyContent: 'flex-end' }}>
              <button className="control-btn" style={{ padding: '0.75rem 2rem' }} onClick={onClose}> {t.wizard.cancel} </button>
              <button className="control-btn" style={{ padding: '0.75rem 2rem', background: 'var(--accent-cyan)', color: '#000', border: 'none' }} onClick={onApplyWizard}>
                <Check size={18} /> {t.wizard.save}
              </button>
            </div>
          </div>
        ) : (
          <>
            <p style={{ fontSize: '0.875rem', color: 'rgba(255,255,255,0.5)' }}>Измените JSON конфигурацию плагина. Будьте осторожны с синтаксисом.</p>
            
            <textarea 
              className="json-editor"
              value={configText}
              onChange={(e) => setConfigText(e.target.value)}
              spellCheck={false}
            />
            
            <div style={{ display: 'flex', gap: '1rem', justifyContent: 'flex-end' }}>
              <button className="control-btn" style={{ padding: '0.75rem 2rem' }} onClick={onClose}> {t.wizard.cancel} </button>
              <button className="control-btn" style={{ padding: '0.75rem 2rem', background: 'var(--accent-cyan)', color: '#000', border: 'none' }} onClick={onSaveConfig}>
                <Check size={18} /> {t.wizard.save}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
