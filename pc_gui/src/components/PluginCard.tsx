import { Edit3, Info, ExternalLink } from 'lucide-react';
import type { PluginInfo } from '../types';

interface PluginCardProps {
  plugin: PluginInfo;
  allPlugins: PluginInfo[];
  togglePlugin: (id: string) => void;
  openEditor: (plugin: PluginInfo) => void;
  openInfo: (plugin: PluginInfo) => void;
  t: any;
}

export function PluginCard({ plugin, allPlugins, togglePlugin, openEditor, openInfo, t }: PluginCardProps) {
  return (
    <div className="glass-card" style={{ display: 'flex', flexDirection: 'column', minHeight: '180px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '1rem' }}>
        <div>
          <h3 style={{ fontSize: '1.25rem', fontWeight: 700, margin: 0 }}>
            {t.sidebar.plugins === 'Plugins' ? plugin.name_en || plugin.name : plugin.name}
          </h3>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginTop: '4px' }}>
            {plugin.author ? (
              <a 
                href={plugin.author} 
                target="_blank" 
                rel="noreferrer" 
                style={{ fontSize: '0.7rem', color: 'var(--accent-cyan)', textDecoration: 'none', display: 'flex', alignItems: 'center', gap: '4px' }}
              >
                by {plugin.author_name || t.plugins.unknownAuthor} <ExternalLink size={10} />
              </a>
            ) : (
              <span style={{ fontSize: '0.7rem', color: 'rgba(255,255,255,0.4)', display: 'flex', alignItems: 'center', gap: '4px' }}>
                by {plugin.author_name || t.plugins.unknownAuthor}
              </span>
            )}
            <span style={{ fontSize: '0.65rem', color: 'rgba(255,255,255,0.3)', padding: '1px 4px', background: 'rgba(255,255,255,0.05)', borderRadius: '3px' }}>
              v{plugin.version}
            </span>
          </div>
        </div>
        <label className="switch">
          <input 
            type="checkbox" 
            checked={plugin.active} 
            onChange={() => togglePlugin(plugin.id)}
          />
          <span className="slider"></span>
        </label>
      </div>

      {plugin.dependencies && plugin.dependencies.length > 0 && (
        <div style={{ marginBottom: '1.5rem', display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
          {plugin.dependencies.map((dep: string) => (
            <span key={dep} style={{ 
              fontSize: '0.6rem', 
              padding: '2px 8px', 
              borderRadius: '4px', 
              background: 'rgba(0, 242, 255, 0.05)', 
              color: 'var(--accent-cyan)',
              border: '1px solid rgba(0, 242, 255, 0.1)'
            }}>
              {t.plugins.needed}: {allPlugins.find(p => p.id === dep)?.name || dep}
            </span>
          ))}
        </div>
      )}

      <div style={{ display: 'flex', gap: '0.5rem', marginTop: 'auto' }}>
        <button className="control-btn" style={{ flex: 1 }} onClick={() => openEditor(plugin)}>
          <Edit3 size={16} /> {t.plugins.setup}
        </button>
        <button className="control-btn" style={{ flex: 1 }} onClick={() => openInfo(plugin)}>
          <Info size={16} /> <span>{t.plugins.info}</span>
        </button>
      </div>
    </div>
  );
}
