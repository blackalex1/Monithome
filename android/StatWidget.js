import React, { useEffect } from 'react';
import { View, Text } from 'react-native';
import Animated, { 
  useSharedValue, 
  useAnimatedStyle, 
  withTiming, 
  interpolateColor 
} from 'react-native-reanimated';
import { styles } from './styles';
import { IconMap, Activity, Layers } from './IconMap';

// Оптимизированный виджет статов с использованием Reanimated для UI-потока
export const StatWidget = React.memo(({ widget, stats, isSmall, lang }) => {
  const t = (label, labelEn) => lang === 'en' ? (labelEn || label) : label;
  const Icon = IconMap[widget.icon] || Activity;
  
  const value = stats?.[widget.data_key] || 0;
  const unit = widget.unit || '';
  const displayValue = stats?.[`display_${widget.data_key}`] || (stats?.[widget.data_key] !== undefined ? `${stats[widget.data_key]}${unit}` : `0${unit}`);
  const secondaryValue = stats?.[`secondary_${widget.data_key}`] || null;

  // Прогресс-бар на уровне UI-потока
  const progressPercent = stats?.[`${widget.data_key}_percent`] || (unit === '%' ? value : value);
  const sharedProgress = useSharedValue(0);

  useEffect(() => {
    sharedProgress.value = withTiming(Math.min(100, Math.max(0, Number(progressPercent) || 0)), { duration: 300 });
  }, [progressPercent]);

  const animatedProgressStyle = useAnimatedStyle(() => {
    const p = sharedProgress.value;
    const color = p > 80 ? '#ef4444' : (p > 60 ? '#f59e0b' : '#38bdf8');
    return {
      width: `${p}%`,
      backgroundColor: color,
      height: '100%',
      borderRadius: 4
    };
  });

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
        <Animated.View style={animatedProgressStyle} />
      </View>
    </View>
  );
});

export const ChartWidget = React.memo(({ widget, stats, history, pluginId, lang }) => {
  const t = (label, labelEn) => lang === 'en' ? (labelEn || label) : label;
  const ChartIcon = IconMap[widget.icon] || Layers;
  const componentName = stats?.[widget.data_key + '_name'] || '';
  
  const pluginHistory = history?.[widget.data_key] || Array(20).fill(0);
  let chartData = pluginHistory;
  if (chartData.length > 20) chartData = chartData.slice(-20);
  else if (chartData.length < 20) chartData = [...Array(20 - chartData.length).fill(0), ...chartData];
  
  return (
    <View style={styles.glassCard}>
      <View style={[styles.row, { justifyContent: 'space-between', marginBottom: 12 }]}>
        <View style={styles.row}>
          <ChartIcon size={20} color={widget.color || "#38bdf8"} />
          <View>
            <Text style={styles.cardTitle}>{t(widget.label, widget.label_en)}</Text>
            {componentName ? <Text style={styles.subTitleText} numberOfLines={1}>{componentName}</Text> : null}
          </View>
        </View>
        <Text style={styles.chartValueText}>{stats?.[widget.data_key] || 0}{widget.unit || '%'}</Text>
      </View>
      
      <View style={{ height: 50, width: '100%', flexDirection: 'row', alignItems: 'flex-end', gap: 3 }}>
        {chartData.map((val, i) => (
          <View 
            key={i} 
            style={{ 
              flex: 1, 
              height: `${Math.max(15, Math.min(100, Number(val) || 0))}%`, 
              backgroundColor: widget.color || '#38bdf8', 
              opacity: 0.6,
              borderRadius: 2
            }} 
          />
        ))}
      </View>
    </View>
  );
});
