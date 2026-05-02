import React, { useEffect } from 'react';
import { View, Text } from 'react-native';
import Animated, { useSharedValue, useAnimatedStyle, withTiming } from 'react-native-reanimated';
import { styles } from './styles';
import { HardDrive } from './IconMap';

// Компонент одной строки диска с UI-анимацией
const DiskRow = React.memo(({ disk, t }) => {
  const sharedProgress = useSharedValue(0);

  useEffect(() => {
    sharedProgress.value = withTiming(disk.percent, { duration: 500 });
  }, [disk.percent]);

  const animatedStyle = useAnimatedStyle(() => {
    const p = sharedProgress.value;
    return {
      width: `${p}%`,
      backgroundColor: p > 90 ? '#ef4444' : '#38bdf8',
      height: '100%',
      borderRadius: 4
    };
  });

  return (
    <View>
      <View style={[styles.row, { justifyContent: 'space-between', marginBottom: 6 }]}>
        <Text style={{ color: '#f8fafc', fontWeight: 'bold' }}>{disk.device} ({disk.label})</Text>
        <Text style={{ color: disk.percent > 90 ? '#ef4444' : '#38bdf8', fontWeight: 'bold' }}>{disk.percent}%</Text>
      </View>
      <View style={styles.volumeSliderTrack}>
        <Animated.View style={animatedStyle} />
      </View>
      <Text style={{ color: '#64748b', fontSize: 10, marginTop: 4 }}>
        {t('Свободно', 'Free')} {disk.free} {t('ГБ', 'GB')} {t('из', 'of')} {disk.total} {t('ГБ', 'GB')}
      </Text>
    </View>
  );
});

const DiskWidget = ({ stats, lang }) => {
  const t = (label, labelEn) => lang === 'en' ? (labelEn || label) : label;
  const disks = stats?.disks || [];
  
  return (
    <View style={styles.glassCard}>
      <View style={[styles.row, { marginBottom: 15 }]}>
        <HardDrive size={22} color="#38bdf8" />
        <Text style={styles.cardTitle}>{t('Диски', 'Disks')}</Text>
      </View>
      <View style={{ gap: 16 }}>
        {disks.map((disk, idx) => (
          <DiskRow key={disk.device || idx} disk={disk} t={t} />
        ))}
      </View>
    </View>
  );
};

export default React.memo(DiskWidget);
