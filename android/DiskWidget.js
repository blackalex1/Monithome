import React from 'react';
import { View, Text } from 'react-native';
import { styles } from './styles';
import { HardDrive } from './IconMap';

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
          <View key={idx}>
            <View style={[styles.row, { justifyContent: 'space-between', marginBottom: 6 }]}>
              <Text style={{ color: '#f8fafc', fontWeight: 'bold' }}>{disk.device} ({disk.label})</Text>
              <Text style={{ color: disk.percent > 90 ? '#ef4444' : '#38bdf8', fontWeight: 'bold' }}>{disk.percent}%</Text>
            </View>
            <View style={styles.volumeSliderTrack}>
              <View style={[styles.volumeSliderFill, { 
                width: `${disk.percent}%`, 
                backgroundColor: disk.percent > 90 ? '#ef4444' : '#38bdf8' 
              }]} />
            </View>
            <Text style={{ color: '#64748b', fontSize: 10, marginTop: 4 }}>
              {t('Свободно', 'Free')} {disk.free} {t('ГБ', 'GB')} {t('из', 'of')} {disk.total} {t('ГБ', 'GB')}
            </Text>
          </View>
        ))}
      </View>
    </View>
  );
};

export default DiskWidget;
