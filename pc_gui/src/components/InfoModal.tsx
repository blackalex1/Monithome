import { X, User, ExternalLink, Tag, BookOpen } from 'lucide-react';
import type { PluginInfo } from '../types';

interface InfoModalProps {
  plugin: PluginInfo;
  allPlugins: PluginInfo[];
  onClose: () => void;
  t: any;
}

export function InfoModal({ plugin, allPlugins, onClose, t }: InfoModalProps) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" style={{ maxWidth: '500px' }} onClick={e => e.stopPropagation()}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '2rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <div style={{ width: 40, height: 40, borderRadius: '10px', background: 'rgba(0, 242, 255, 0.1)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <BookOpen size={24} color="var(--accent-cyan)" />
            </div>
            <h2 style={{ fontSize: '1.5rem', fontWeight: 700, margin: 0 }}>{t.plugins.info}</h2>
          </div>
          <button className="action-btn" onClick={onClose}><X size={24} /></button>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
          <div>
            <h3 style={{ fontSize: '1.1rem', fontWeight: 700, marginBottom: '0.5rem' }}>
              {t.sidebar.plugins === 'Plugins' ? plugin.name_en || plugin.name : plugin.name}
            </h3>
            <p style={{ color: 'rgba(255,255,255,0.6)', fontSize: '0.9rem', lineHeight: '1.6', margin: 0 }}>
              {t.sidebar.plugins === 'Plugins' ? plugin.description_en || plugin.description : plugin.description}
            </p>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
            <div className="glass-card" style={{ padding: '1rem', background: 'rgba(255,255,255,0.02)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'rgba(255,255,255,0.4)', fontSize: '0.75rem', marginBottom: '4px' }}>
                <Tag size={14} /> {t.plugins.version}
              </div>
              <div style={{ fontWeight: 600 }}>{plugin.version}</div>
            </div>
            <div className="glass-card" style={{ padding: '1rem', background: 'rgba(255,255,255,0.02)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'rgba(255,255,255,0.4)', fontSize: '0.75rem', marginBottom: '4px' }}>
                <User size={14} /> {t.plugins.author}
              </div>
              <div style={{ fontWeight: 600, fontSize: '0.85rem', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {plugin.author_name || t.plugins.unknownAuthor}
              </div>
            </div>
          </div>

          <div className="glass-card" style={{ padding: '1rem', background: 'rgba(255,255,255,0.02)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'rgba(255,255,255,0.4)', fontSize: '0.75rem', marginBottom: '12px' }}>
              <ExternalLink size={14} /> {t.plugins.authorResources}
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {plugin.config?.links ? (
                plugin.config.links.map((link: any, idx: number) => (
                  <a 
                    key={idx}
                    href={link.url} 
                    target="_blank" 
                    rel="noreferrer"
                    style={{ 
                      color: 'var(--accent-cyan)', 
                      fontSize: '0.85rem', 
                      textDecoration: 'none', 
                      display: 'flex', 
                      alignItems: 'center', 
                      gap: '8px',
                      background: 'rgba(0, 242, 255, 0.03)',
                      padding: '6px 10px',
                      borderRadius: '6px',
                      border: '1px solid rgba(0, 242, 255, 0.05)'
                    }}
                  >
                    <span style={{ fontWeight: 600 }}>{link.label}:</span>
                    <span style={{ opacity: 0.7, fontSize: '0.75rem', overflow: 'hidden', textOverflow: 'ellipsis' }}>{link.url}</span>
                  </a>
                ))
              ) : (
                plugin.author ? (
                  <a 
                    href={plugin.author} 
                    target="_blank" 
                    rel="noreferrer"
                    style={{ color: 'var(--accent-cyan)', fontSize: '0.85rem', textDecoration: 'none', wordBreak: 'break-all' }}
                  >
                    {plugin.author}
                  </a>
                ) : (
                  <span style={{ color: 'rgba(255,255,255,0.4)', fontSize: '0.85rem' }}>
                    {t.plugins.unknownAuthor}
                  </span>
                )
              )}
            </div>
          </div>

          {plugin.dependencies && plugin.dependencies.length > 0 && (
            <div>
              <div style={{ fontSize: '0.75rem', color: 'rgba(255,255,255,0.4)', marginBottom: '8px' }}>{t.plugins.dependencies}</div>
              <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                {plugin.dependencies.map((dep: string) => (
                  <span key={dep} style={{ 
                    fontSize: '0.7rem', 
                    padding: '4px 10px', 
                    borderRadius: '6px', 
                    background: 'rgba(0, 242, 255, 0.05)', 
                    color: 'var(--accent-cyan)',
                    border: '1px solid rgba(0, 242, 255, 0.1)'
                  }}>
                    {(() => {
                      const depP = allPlugins.find(p => p.id === dep);
                      if (!depP) return dep;
                      return t.sidebar.plugins === 'Plugins' ? depP.name_en || depP.name : depP.name;
                    })()}
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>

        <button 
          className="control-btn" 
          style={{ width: '100%', marginTop: '2rem', background: 'var(--accent-cyan)', color: '#000', border: 'none', fontWeight: 700 }}
          onClick={onClose}
        >
          {t.plugins.close}
        </button>
      </div>
    </div>
  );
}
