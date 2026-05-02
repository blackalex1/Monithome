import React from 'react';
import { View, Text } from 'react-native';
import { styles } from './styles';
import MediaWidget from './MediaWidget';
import { ChartWidget } from './StatWidget';
import { usePluginStats, useHistory } from './store';
import { ValueBlock, AnimatedProgressBar, WidgetContainer } from './UniversalComponents';

// Глобальный диспетчер виджетов (Universal Renderer)
const MemoWidget = React.memo(({ 
  widget, 
  pluginId, 
  onCommand, 
  isSmall, 
  allPlugins, 
  activeTargets, 
  lang, 
  onInteraction,
  serverIp
}) => {
  const stats = usePluginStats(pluginId);
  const history = useHistory(pluginId);
  const t = (label, labelEn) => lang === 'en' ? (labelEn || label) : label;
  
  if (widget.condition && stats && !stats[widget.condition]) return null;

  // Рекурсивный рендеринг строк/колонок
  if (widget.type === 'row' || widget.type === 'container') {
    return (
      <View style={[widget.type === 'row' ? styles.rowLayout : {}, { width: '100%' }]}>
        {widget.children?.map((child, idx) => (
          <View key={idx} style={{ flex: 1 }}>
            <MemoWidget 
              widget={child} 
              pluginId={pluginId} 
              onCommand={onCommand} 
              isSmall={true}
              activeTargets={activeTargets}
              allPlugins={allPlugins}
              lang={lang}
              onInteraction={onInteraction}
              serverIp={serverIp}
            />
          </View>
        ))}
      </View>
    );
  }

  // Универсальный рендеринг списков (например, диски, процессы)
  if (widget.type === 'list' || widget.list_key) {
    const listData = stats?.[widget.list_key || 'items'] || [];
    return (
      <WidgetContainer isSmall={isSmall}>
        <View style={[styles.row, { marginBottom: 10 }]}>
           <Text style={styles.cardTitle}>{t(widget.label, widget.label_en)}</Text>
        </View>
        {listData.map((item, idx) => (
          <View key={idx} style={{ marginBottom: 12 }}>
             <ValueBlock 
                label={item[widget.item_label_key || 'name'] || item.label || ''}
                value={item[widget.item_value_key || 'value'] || item.percent || ''}
                unit={widget.item_unit || '%'}
                icon={widget.icon}
                isSmall={true}
                secondaryValue={item[widget.item_secondary_key] || item.free_text}
             />
             <AnimatedProgressBar 
                value={item[widget.item_value_key || 'value'] || item.percent || 0} 
                colorRanges={widget.color_ranges}
             />
          </View>
        ))}
      </WidgetContainer>
    );
  }

  // Универсальный атомарный виджет (Stat/Gauge)
  if (widget.type === 'stat') {
    return (
      <WidgetContainer isSmall={isSmall}>
        <ValueBlock 
          label={t(widget.label, widget.label_en)}
          value={stats?.[`display_${widget.data_key}`] || stats?.[widget.data_key] || 0}
          unit={stats?.[`display_${widget.data_key}`] ? '' : (widget.unit || '')}
          icon={widget.icon}
          isSmall={isSmall}
          secondaryValue={stats?.[`secondary_${widget.data_key}`]}
        />
        <AnimatedProgressBar 
          value={stats?.[`${widget.data_key}_percent`] || stats?.[widget.data_key] || 0}
          colorRanges={widget.color_ranges}
        />
      </WidgetContainer>
    );
  }

  if (widget.type === 'chart') {
    return <ChartWidget widget={widget} stats={stats} history={history} pluginId={pluginId} lang={lang} />;
  }

  if (widget.type === 'unified_media') {
    return (
      <MediaWidget 
        widget={widget}
        allPlugins={allPlugins}
        activeTargets={activeTargets}
        onCommand={onCommand}
        onInteraction={onInteraction}
        lang={lang}
        serverIp={serverIp}
      />
    );
  }

  return null;
});

export default MemoWidget;
