import React from 'react';
import { View } from 'react-native';
import { styles } from './styles';
import MediaWidget from './MediaWidget';
import { StatWidget, ChartWidget } from './StatWidget';
import DiskWidget from './DiskWidget';

const MemoWidget = React.memo(({ 
  widget, 
  pluginId, 
  stats, 
  history, 
  onCommand, 
  activeTarget, 
  onSetTarget, 
  isSmall, 
  allStats, 
  mediaStats,
  allPlugins, 
  activeTargets, 
  lang, 
  onInteraction 
}) => {
  
  if (widget.condition && stats && !stats[widget.condition]) return null;

  switch (widget.type) {
    case 'row':
      return (
        <View style={[styles.rowLayout, { width: '100%' }]}>
          {widget.children?.map((child, idx) => (
            <View key={idx} style={{ flex: 1 }}>
              <MemoWidget 
                widget={child} 
                pluginId={pluginId} 
                stats={stats} 
                history={history} 
                onCommand={onCommand} 
                activeTarget={activeTarget || activeTargets?.[pluginId]}
                onSetTarget={onSetTarget}
                isSmall={true}
                activeTargets={activeTargets}
                allStats={allStats}
                mediaStats={mediaStats}
                allPlugins={allPlugins}
                lang={lang}
                onInteraction={onInteraction}
              />
            </View>
          ))}
        </View>
      );

    case 'stat':
      return <StatWidget widget={widget} stats={stats} isSmall={isSmall} lang={lang} />;

    case 'chart':
      return <ChartWidget widget={widget} stats={stats} history={history} pluginId={pluginId} lang={lang} />;

    case 'disks':
    case 'disk_list':
      return <DiskWidget stats={stats} lang={lang} />;

    case 'unified_media':
      return (
        <MediaWidget 
          widget={widget}
          allStats={mediaStats}
          allPlugins={allPlugins}
          activeTargets={activeTargets}
          onCommand={onCommand}
          onInteraction={onInteraction}
          lang={lang}
        />
      );

    default:
      return null;
  }
});

export default MemoWidget;
