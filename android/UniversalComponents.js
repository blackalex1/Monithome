import React, { useEffect } from 'react';
import { View, Text } from 'react-native';
import Animated, { useSharedValue, useAnimatedStyle, withTiming } from 'react-native-reanimated';
import { styles } from './styles';
import { IconMap, Activity } from './IconMap';
import { usePluginStats } from './store';

// 1. Универсальная анимированная полоса прогресса (UI Thread)
export const AnimatedProgressBar = React.memo(({ value, colorRanges }) => {
  const sharedValue = useSharedValue(0);

  useEffect(() => {
    sharedValue.value = withTiming(Math.min(100, Math.max(0, value)), { duration: 400 });
  }, [value]);

  const animatedStyle = useAnimatedStyle(() => {
    let color = '#38bdf8'; // Default blue
    if (colorRanges) {
      // Динамический выбор цвета на основе переданных диапазонов
      for (const range of colorRanges) {
        if (sharedValue.value >= range.min && sharedValue.value <= range.max) {
          color = range.color;
          break;
        }
      }
    } else {
      // Fallback логика
      color = sharedValue.value > 80 ? '#ef4444' : (sharedValue.value > 60 ? '#f59e0b' : '#38bdf8');
    }

    return {
      width: `${sharedValue.value}%`,
      backgroundColor: color
    };
  });

  return (
    <View style={styles.volumeSliderTrack}>
      <Animated.View style={[styles.volumeSliderFill, animatedStyle]} />
    </View>
  );
});

// 2. Универсальный текстовый блок со значением
export const ValueBlock = React.memo(({ value, unit, label, icon, isSmall, secondaryValue }) => {
  const Icon = IconMap[icon] || Activity;
  return (
    <View style={{ marginBottom: 10 }}>
      <View style={[styles.row, { justifyContent: 'space-between' }]}>
        <View style={styles.row}>
          <Icon size={isSmall ? 18 : 22} color="#38bdf8" />
          <Text style={styles.cardTitle}>{label}</Text>
        </View>
        {secondaryValue && <Text style={styles.secondaryStatText}>{secondaryValue}</Text>}
      </View>
      <Text style={[styles.statValueText, isSmall && { fontSize: 24, marginTop: 4 }]}>
        {value}{unit}
      </Text>
    </View>
  );
});

// 3. Универсальный контейнер виджета
export const WidgetContainer = ({ children, isSmall }) => (
  <View style={[styles.glassCard, isSmall && { padding: 12, margin: 4 }]}>
    {children}
  </View>
);
