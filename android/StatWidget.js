import React from 'react';
import { View, Text } from 'react-native';
import { styles } from './styles';
import { Theme } from './Theme';
import { IconMap, Activity, Layers } from './IconMap';

export const StatWidget = ({ widget, stats, isSmall, lang }) => {
  const t = (label, labelEn) => lang === 'en' ? (labelEn || label) : label;
  const Icon = IconMap[widget.icon] || Activity;
  
  let value = stats?.[widget.data_key] || 0;
  let unit = widget.unit || '';
  
  // Универсальное отображение: используем display_[key] если оно есть, иначе собираем из значения и юнита
  let displayValue = stats?.[`display_${widget.data_key}`] || (stats?.[widget.data_key] !== undefined ? `${stats[widget.data_key]}${unit}` : `0${unit}`);
  let secondaryValue = stats?.[`secondary_${widget.data_key}`] || null;

  // Если это процентный показатель (есть в ключе или юните), используем его для прогресс-бара
  const progressValue = stats?.[`${widget.data_key}_percent`] || (unit === '%' ? value : value);

  return (
    <View style={[styles.glassCard, isSmall && { padding: 16 }]}>
      <View style={[styles.row, { justifyContent: 'space-between' }]}>
        <View style={styles.row}>
          <Icon size={isSmall ? 18 : 22} color="#38bdf8" />
          <Text style={styles.cardTitle}>{t(widget.label, widget.label_en)}</Text>
        </View>
        {secondaryValue && <Text style={styles.secondaryStatText}>{secondaryValue}</Text>}
      </View>
      <Text style={[styles.statValueText, isSmall && { fontSize: 24, marginTop: 8 }]}>{displayValue}</Text>
      <View style={styles.volumeSliderTrack}>
        <View style={[styles.volumeSliderFill, { 
          width: `${Math.min(100, Math.max(0, progressValue))}%`, 
          backgroundColor: progressValue > 80 ? '#ef4444' : (progressValue > 60 ? '#f59e0b' : '#38bdf8') 
        }]} />
      </View>
    </View>
  );
};

export const ChartWidget = ({ widget, stats, history, pluginId, lang }) => {
  const t = (label, labelEn) => lang === 'en' ? (labelEn || label) : label;
  const ChartIcon = IconMap[widget.icon] || Layers;
  const componentName = stats?.[widget.data_key + '_name'] || '';
  
  // Правильный поиск данных в истории с учетом ID плагина
  const pluginHistory = history?.[pluginId] || {};
  let chartData = pluginHistory[widget.data_key] || Array(30).fill(0);
  if (chartData.length < 2) chartData = Array(30).fill(0);
  
  // Здесь мы упростим отображение графика для мобилки (имитация линии или реальная библиотека)
  // Для краткости оставим заглушку стиля, которую можно расширить
  return (
    <View style={styles.glassCard}>
      <View style={[styles.row, { justifyContent: 'space-between', marginBottom: 15 }]}>
        <View style={styles.row}>
          <ChartIcon size={22} color={widget.color || "#38bdf8"} />
          <View>
            <Text style={styles.cardTitle}>{t(widget.label, widget.label_en)}</Text>
            {componentName ? <Text style={styles.subTitleText}>{componentName}</Text> : null}
          </View>
        </View>
        <Text style={styles.chartValueText}>{stats?.[widget.data_key] || 0}{widget.unit || '%'}</Text>
      </View>
      
      <View style={{ height: 60, width: '100%', flexDirection: 'row', alignItems: 'flex-end', gap: 2, paddingBottom: 5 }}>
        {chartData.map((val, i) => (
          <View 
            key={i} 
            style={{ 
              flex: 1, 
              height: `${Math.max(10, Math.min(100, val))}%`, 
              backgroundColor: widget.color || '#38bdf8', 
              opacity: 0.3 + (i / 40),
              borderRadius: 3
            }} 
          />
        ))}
      </View>
    </View>
  );
};
