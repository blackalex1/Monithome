import React, { useState } from 'react';
import { View, Text, Pressable, Image, TextInput, Dimensions, ScrollView } from 'react-native';
import { styles } from './styles';
import { LineChart } from 'react-native-chart-kit';
import { 
  IconMap, Activity, Cpu, HardDrive, Music, Play, Pause, 
  SkipBack, SkipForward, Volume2, Mic, RefreshCw,
  Thermometer, Zap, Layers
} from './IconMap';

const { width: screenWidth } = Dimensions.get('window');

const MemoWidget = React.memo(({ widget, pluginId, stats, history, onCommand, activeTarget, onSetTarget, isSmall, allStats, activeTargets }) => {
  const [aliceText, setAliceText] = useState("");
  const [localSource, setLocalSource] = useState(null);

  if (widget.condition && stats && !stats[widget.condition]) return null;

  const renderProgress = (val, color = '#38bdf8', height = 8) => (
    <View style={[styles.volumeSliderTrack, { height, marginTop: 8 }]}>
      <View style={[styles.volumeSliderFill, { width: `${Math.min(100, Math.max(0, val))}%`, backgroundColor: color }]} />
    </View>
  );

  switch (widget.type) {
    case 'row':
      return (
        <View style={styles.rowLayout}>
          {widget.children?.map((child, idx) => (
            <View key={idx} style={{ flex: 1 }}>
              <MemoWidget 
                widget={child} 
                pluginId={pluginId} 
                stats={stats} 
                history={history} 
                onCommand={onCommand} 
                activeTarget={activeTarget}
                onSetTarget={onSetTarget}
                isSmall={true}
                allStats={allStats}
                activeTargets={activeTargets}
              />
            </View>
          ))}
        </View>
      );

    case 'stat':
      const Icon = IconMap[widget.icon] || Activity;
      let value = stats?.[widget.data_key] || 0;
      let unit = widget.unit || '';
      let displayValue = `${value}${unit}`;

      if (widget.data_key === 'ram_percent' && stats?.ram_used) {
        displayValue = `${stats.ram_used} / ${stats.ram_total} GB`;
      }

      return (
        <View style={[styles.glassCard, isSmall && { padding: 16 }]}>
          <View style={styles.row}>
            <Icon size={isSmall ? 18 : 22} color="#38bdf8" />
            <Text style={styles.cardTitle}>{widget.label}</Text>
          </View>
          <Text style={[styles.statValueText, isSmall && { fontSize: 24, marginTop: 8 }]}>{displayValue}</Text>
          {renderProgress(value, value > 80 ? '#ef4444' : (value > 60 ? '#f59e0b' : '#38bdf8'))}
        </View>
      );

    case 'chart':
      const ChartIcon = IconMap[widget.icon] || Layers;
      const componentName = stats?.[widget.data_key + '_name'] || '';
      let chartData = history?.[widget.data_key] || Array(30).fill(0);
      // Гарантируем, что данных достаточно для графика
      if (chartData.length < 2) chartData = Array(30).fill(0);
      
      return (
        <View style={styles.glassCard}>
          <View style={[styles.row, { justifyContent: 'space-between', marginBottom: 15 }]}>
            <View style={styles.row}>
              <ChartIcon size={22} color="#38bdf8" />
              <View>
                <Text style={styles.cardTitle}>{widget.label}</Text>
                {componentName ? <Text style={[styles.osText, { marginLeft: 12, fontSize: 10 }]}>{componentName}</Text> : null}
              </View>
            </View>
            <Text style={[styles.statValueText, { marginTop: 0, fontSize: 20 }]}>
              {stats?.[widget.data_key] || 0}{widget.unit || '%'}
            </Text>
          </View>
          
          <LineChart
            data={{ datasets: [{ data: chartData }] }}
            width={screenWidth - 80}
            height={100}
            withDots={false}
            withInnerLines={false}
            withOuterLines={false}
            withHorizontalLabels={false}
            withVerticalLabels={false}
            chartConfig={{
              backgroundColor: 'transparent',
              backgroundGradientFrom: 'rgba(30, 41, 59, 0)',
              backgroundGradientTo: 'rgba(30, 41, 59, 0)',
              decimalPlaces: 0,
              color: (opacity = 1) => `rgba(56, 189, 248, ${opacity})`,
              style: { borderRadius: 16 },
              propsForBackgroundLines: { strokeWidth: 0 }
            }}
            bezier
            style={{ marginVertical: 8, borderRadius: 16, marginLeft: -20 }}
          />
        </View>
      );

    case 'disk_list':
      const disks = stats?.disks || [];
      return (
        <View style={styles.glassCard}>
          <View style={[styles.row, { justifyContent: 'space-between', marginBottom: 15 }]}>
            <View style={styles.row}>
              <HardDrive size={22} color="#38bdf8" />
              <Text style={styles.cardTitle}>Диски</Text>
            </View>
            <Pressable onPress={() => onCommand(pluginId, 'update_disks')} style={styles.miniBtn}>
              <RefreshCw size={16} color="#fff" />
            </Pressable>
          </View>
          {disks.map((disk, i) => (
            <View key={i} style={{ marginBottom: 16 }}>
              <View style={[styles.row, { justifyContent: 'space-between' }]}>
                <Text style={{ color: '#fff', fontSize: 14, fontWeight: '600' }}>{disk.device} ({disk.label || 'Локальный диск'})</Text>
                <Text style={{ color: '#94a3b8', fontSize: 12 }}>{disk.percent}%</Text>
              </View>
              {renderProgress(disk.percent, disk.percent > 90 ? '#ef4444' : '#38bdf8')}
              <Text style={{ color: '#64748b', fontSize: 10, marginTop: 4 }}>
                Свободно {disk.free} ГБ из {disk.total} ГБ
              </Text>
            </View>
          ))}
        </View>
      );

    case 'unified_media':
      const yandexStats = allStats?.['yandex_station']?.devices || [];
      const pcStats = allStats?.['pc_media'] || {};
      const sources = [
        { id: 'pc', name: 'Компьютер', type: 'pc', online: true, ...pcStats },
        ...(Array.isArray(yandexStats) ? yandexStats : []).map(d => ({ ...d, type: 'yandex' }))
      ];

      const yandexActive = activeTargets?.['yandex_station'];
      const pcActive = activeTargets?.['pc_media'];
      const currentSourceId = localSource || activeTarget || yandexActive || pcActive || 'pc';
      const currentSource = sources.find(s => s.id === currentSourceId) || sources[0];
      const isYandex = currentSource.type === 'yandex';
      const pluginToUse = isYandex ? 'yandex_station' : 'pc_media';
      const finalTarget = isYandex ? currentSource.id : 'pc';

      return (
        <View style={styles.glassCard}>
          <View style={{ marginBottom: 20, zIndex: 10 }}>
            <ScrollView 
              horizontal 
              showsHorizontalScrollIndicator={false}
              contentContainerStyle={{ paddingRight: 20 }}
              keyboardShouldPersistTaps="handled"
            >
              {sources.map(s => (
                <Pressable 
                  key={s.id} 
                  onPress={() => {
                    setLocalSource(s.id);
                    onSetTarget(s.type === 'yandex' ? 'yandex_station' : 'pc_media', s.id);
                  }}
                  style={[styles.sourceTab, currentSourceId === s.id && styles.sourceTabActive]}
                  hitSlop={{ top: 10, bottom: 10, left: 5, right: 5 }}
                >
                  <Text style={[styles.sourceTabText, currentSourceId === s.id && styles.sourceTabTextActive]}>{s.name}</Text>
                </Pressable>
              ))}
            </ScrollView>
          </View>

          <View style={styles.mediaHeader}>
            {currentSource.cover ? (
              <Image source={{ uri: currentSource.cover }} style={styles.albumArt} resizeMode="cover" />
            ) : (
              <View style={[styles.albumArt, { alignItems: 'center', justifyContent: 'center' }]}>
                <Music size={40} color="rgba(255,255,255,0.1)" />
              </View>
            )}
            
            <View style={{ flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 10 }}>
              <View style={{ flex: 1 }}>
                <Text style={styles.miniLabel}>{isYandex ? 'Яндекс Станция' : 'Windows Media'}</Text>
                <Text style={styles.mediaTitle} numberOfLines={1}>{currentSource.title || 'Тишина...'}</Text>
                <Text style={styles.mediaArtist} numberOfLines={1}>{currentSource.subtitle || '—'}</Text>
              </View>
              
              <View style={{ flexDirection: 'row', gap: 10, alignItems: 'center' }}>
                <Pressable onPress={() => onCommand(pluginToUse, 'prev_track', finalTarget)} style={[styles.miniBtn, { width: 44, height: 44 }]}>
                  <SkipBack size={22} color="#fff" />
                </Pressable>
                <Pressable onPress={() => onCommand(pluginToUse, 'play_pause', finalTarget)} style={[styles.playBtn, { width: 56, height: 56, borderRadius: 28 }]}>
                  {currentSource.playing ? <Pause size={28} color="#0f172a" /> : <Play size={28} color="#0f172a" />}
                </Pressable>
                <Pressable onPress={() => onCommand(pluginToUse, 'next_track', finalTarget)} style={[styles.miniBtn, { width: 44, height: 44 }]}>
                  <SkipForward size={22} color="#fff" />
                </Pressable>
              </View>
            </View>
          </View>

          <View style={styles.volumeSliderContainer}>
            <View style={[styles.row, { justifyContent: 'space-between', marginBottom: 8 }]}>
              <Volume2 size={16} color="#64748b" />
              <Text style={{ color: '#64748b', fontSize: 12, fontWeight: 'bold' }}>{currentSource.volume || 0}%</Text>
            </View>
            <Pressable 
              style={styles.volumeSliderTrack}
              onPress={(e) => {
                const newVol = Math.round((e.nativeEvent.locationX / (screenWidth - 80)) * 100);
                onCommand(pluginToUse, `set_volume:${newVol}`, finalTarget);
              }}
            >
              <View style={[styles.volumeSliderFill, { width: `${currentSource.volume || 0}%` }]} />
              <View style={[styles.volumeSliderThumb, { left: `${currentSource.volume || 0}%` }]} />
            </Pressable>
          </View>

          {isYandex && (
            <View style={styles.voiceInputContainer}>
              <Mic size={18} color="#38bdf8" />
              <TextInput
                style={styles.voiceInput}
                placeholder="Сказать Алисе..."
                placeholderTextColor="#64748b"
                value={aliceText}
                onChangeText={setAliceText}
                onSubmitEditing={() => {
                  onCommand(pluginToUse, `voice:${aliceText}`, finalTarget);
                  setAliceText("");
                }}
              />
            </View>
          )}
        </View>
      );

    default:
      return null;
  }
});

export default MemoWidget;
