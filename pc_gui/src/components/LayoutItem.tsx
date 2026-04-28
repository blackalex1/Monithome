import { Reorder, useDragControls } from 'framer-motion';
import { GripVertical, ChevronUp, ChevronDown } from 'lucide-react';
import type { PluginInfo } from '../types';

interface LayoutItemProps {
  pId: string;
  idx: number;
  pInfo: PluginInfo | undefined;
  movePlugin: (idx: number, direction: 'up' | 'down') => void;
  isLast: boolean;
}

export function LayoutItem({ pId, idx, pInfo, movePlugin, isLast }: LayoutItemProps) {
  const dragControls = useDragControls();
  
  return (
    <Reorder.Item 
      value={pId}
      className="layout-item"
      as="div"
      dragListener={false}
      dragControls={dragControls}
      whileHover={{ 
        x: 5,
        borderColor: "rgba(0, 242, 255, 0.4)"
      }}
      whileDrag={{ 
        scale: 1.02, 
        backgroundColor: "rgba(255, 255, 255, 0.12)",
        boxShadow: "0 20px 50px rgba(0,0,0,0.5)",
        zIndex: 9999
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flex: 1 }}>
        <div 
          onPointerDown={(e) => {
            e.preventDefault();
            dragControls.start(e);
          }}
          style={{ cursor: 'grab', color: 'var(--accent-cyan)', display: 'flex', alignItems: 'center', padding: '0.5rem', marginLeft: '-0.5rem', touchAction: 'none' }}
        >
          <GripVertical size={20} />
        </div>
        <div style={{ width: 32, height: 32, borderRadius: '8px', background: 'rgba(255,255,255,0.05)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 700, fontSize: '0.875rem' }}>
          {idx + 1}
        </div>
        <span style={{ fontWeight: 600 }}>{pInfo?.name || pId}</span>
      </div>
      
      <div className="layout-actions" onClick={(e) => e.stopPropagation()}>
        <button className="action-btn" onClick={() => movePlugin(idx, 'up')} disabled={idx === 0}>
          <ChevronUp size={18} />
        </button>
        <button className="action-btn" onClick={() => movePlugin(idx, 'down')} disabled={isLast}>
          <ChevronDown size={18} />
        </button>
      </div>
    </Reorder.Item>
  );
}
