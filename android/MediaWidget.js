import React, { useState, useMemo, useEffect, useRef } from 'react';
import { View, Text, Pressable, Image, TextInput, Dimensions, ScrollView, Modal, FlatList, StyleSheet } from 'react-native';
import { styles } from './styles';
import { usePluginStats } from './store';
import { Theme } from './Theme';
import { 
  Music, Play, Pause, SkipBack, SkipForward, Volume2, Mic, X
} from './IconMap';

const { width: screenWidth } = Dimensions.get('window');

const LyricLine = React.memo(({ item, isActive }) => {
  return (
    <View style={[
      styles.lyricsLineContainer, 
      { borderBottomWidth: 0, height: 100, alignItems: 'center', justifyContent: 'center' }
    ]}>
      <Text 
        numberOfLines={2}
        style={[
          styles.lyricsLine, 
          { textAlign: 'center', fontSize: isActive ? 32 : 22, width: '90%' },
          isActive && styles.lyricsLineActive
        ]}
      >
        {item.text}
      </Text>
    </View>
  );
});

// Изолированный компонент текста для предотвращения лишних перерисовок основного виджета
const LyricsView = React.memo(({ currentSource, lang, onClose }) => {
  const [currentLineIndex, setCurrentLineIndex] = useState(-1);
  const currentLineIndexRef = useRef(-1);
  const flatListRef = useRef(null);

  useEffect(() => {
    if (!currentSource || !currentSource.timings || currentSource.timings.length === 0) return;
    
    const SYNC_OFFSET_MS = 400;
    const updateIndex = () => {
      const progress = currentSource.progress || 0;
      const isPlaying = currentSource.playing;
      const lastUpdate = currentSource.last_update || 0;
      const serverSentAt = currentSource._server_time || 0;
      const localReceivedAt = currentSource._local_received_at || (Date.now() / 1000);
      const now = Date.now() / 1000;
      
      const serverLag = (serverSentAt > lastUpdate) ? (serverSentAt - lastUpdate) : 0;
      const localLag = now - localReceivedAt;
      const totalDrift = serverLag + localLag;
      const currentTimeMs = (progress + (isPlaying ? totalDrift : 0)) * 1000 + SYNC_OFFSET_MS;
      
      let index = -1;
      const timings = currentSource.timings;
      for (let i = 0; i < timings.length; i++) {
        if (timings[i].time <= currentTimeMs) index = i;
        else break;
      }
      
      if (index !== currentLineIndexRef.current) {
        currentLineIndexRef.current = index;
        setCurrentLineIndex(index);
        if (flatListRef.current && index !== -1) {
          try { 
            flatListRef.current.scrollToIndex({ index, animated: true, viewPosition: 0.5 }); 
          } catch (e) {}
        }
      }
    };

    const interval = setInterval(updateIndex, 200); // Чуть увеличим интервал для разгрузки потока
    updateIndex();
    return () => clearInterval(interval);
  }, [currentSource?.track_id, currentSource?.progress, currentSource?.playing]);

  return (
    <View style={[styles.lyricsModal, { flex: 1 }]}>
      {currentSource?.cover && (
        <Image 
          source={{ uri: currentSource.cover }} 
          style={[StyleSheet.absoluteFill, { opacity: 0.2 }]} 
          blurRadius={50}
        />
      )}
      <View style={styles.lyricsHeader}>
        <View style={{ flex: 1, marginRight: 15 }}>
          <Text style={styles.lyricsTrackTitle} numberOfLines={1}>{currentSource?.title}</Text>
          <Text style={styles.lyricsTrackArtist} numberOfLines={1}>{currentSource?.subtitle}</Text>
        </View>
        <Pressable onPress={onClose} style={styles.miniBtn} hitSlop={20}>
          <X size={24} color="#fff" />
        </Pressable>
      </View>
      
      {currentSource?.timings && currentSource.timings.length > 0 ? (
        <FlatList
          ref={flatListRef}
          data={currentSource.timings}
          keyExtractor={(_, i) => i.toString()}
          showsVerticalScrollIndicator={false}
          ListHeaderComponent={() => <View style={{ height: 300 }} />}
          ListFooterComponent={() => <View style={{ height: 400 }} />}
          initialNumToRender={10}
          maxToRenderPerBatch={5}
          windowSize={5}
          removeClippedSubviews={true}
          renderItem={({ item, index }) => (
            <LyricLine item={item} isActive={index === currentLineIndex} />
          )}
          getItemLayout={(data, index) => ({
            length: 100, offset: 100 * index + 300, index
          })}
        />
      ) : (
        <ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={{ paddingBottom: 100 }}>
          <Text style={[styles.lyricsLine, { color: '#fff', opacity: 0.8, textAlign: 'center', marginTop: 100 }]}>
            {currentSource?.lyrics || (lang === 'ru' ? 'Текст песни отсутствует' : 'No lyrics available')}
          </Text>
        </ScrollView>
      )}
    </View>
  );
});

const MediaWidget = React.memo(({ widget, allPlugins, activeTargets, onCommand, onInteraction, lang, serverIp }) => {
  const t = (label, labelEn) => lang === 'en' ? (labelEn || label) : label;
  const [aliceText, setAliceText] = useState("");
  const [localSource, setLocalSource] = useState(null);
  const [slidingVol, setSlidingVol] = useState(null);
  const [showLyrics, setShowLyrics] = useState(false);

  const formatCover = (cover) => {
    if (!cover) return null;
    if (cover.startsWith('http') || cover.startsWith('data:image')) return cover;
    return `data:image/png;base64,${cover}`;
  };

  const mediaIds = useMemo(() => allPlugins.filter(p => p.type === 'media_source' || p.type === 'lyrics_provider').map(p => p.id), [allPlugins]);
  const relevantStats = usePluginStats(mediaIds);

  const sources = useMemo(() => {
    const mediaPlugins = allPlugins.filter(p => p && p.type === 'media_source');
    const lyricsProviders = allPlugins.filter(p => p && p.type === 'lyrics_provider');
    const allSources = [];
    
    for (const p of mediaPlugins) {
      const pStats = relevantStats[p.id];
      if (!pStats) continue;
      
      if (Array.isArray(pStats.devices)) {
        for (const d of pStats.devices) {
          const source = { 
            ...d, 
            type: 'remote', 
            plugin_id: p.id,
            plugin_name: p.name,
            cover: formatCover(d.cover)
          };
          for (const lp of lyricsProviders) {
            const lData = relevantStats[lp.id]?.devices?.[d.id];
            if (lData) {
              source.lyrics = lData.lyrics;
              source.timings = lData.timings;
              break; 
            }
          }
          allSources.push(source);
        }
      } else {
        allSources.push({ 
          ...pStats,
          id: p.id, 
          name: p.name || 'Unknown Source', 
          type: 'standalone', 
          online: true, 
          plugin_id: p.id,
          plugin_name: p.name,
          cover: formatCover(pStats.cover)
        });
      }
    }
    return widget.device_id ? allSources.filter(s => s.id === widget.device_id) : allSources;
  }, [allPlugins, relevantStats, widget.device_id]);

  const [lastActiveId, setLastActiveId] = useState(widget.device_id || (sources.find(s => s.playing)?.id) || sources[0]?.id);

  useEffect(() => {
    const playing = sources.find(s => s.playing);
    if (playing) setLastActiveId(playing.id);
  }, [sources]);

  const currentSourceId = useMemo(() => {
    if (widget.device_id) return widget.device_id;
    if (localSource && sources.find(s => s.id === localSource)) return localSource;
    if (lastActiveId && sources.find(s => s.id === lastActiveId)) return lastActiveId;
    return sources.find(s => s.playing)?.id || sources[0]?.id;
  }, [widget.device_id, localSource, lastActiveId, sources]);

  const currentSource = useMemo(() => {
    if (sources.length === 0) return null;
    return sources.find(s => s.id === currentSourceId) || sources[0];
  }, [sources, currentSourceId]);

  if (!currentSource || sources.length === 0) return null;

  const isStandalone = currentSource.type === 'standalone';
  const pluginToUse = currentSource.plugin_id;
  const finalTarget = isStandalone ? 'pc' : currentSource.id;

  return (
    <View style={styles.glassCard}>
      {/* Вкладки источников */}
      {!widget.device_id && sources.length > 1 && (
        <View style={{ marginBottom: 20 }}>
          <ScrollView horizontal showsHorizontalScrollIndicator={false}>
            {sources.map(s => (
              <Pressable 
                key={s.id} 
                onPress={() => setLocalSource(s.id)}
                style={[styles.sourceTab, currentSourceId === s.id && styles.sourceTabActive]}
              >
                <Text style={[styles.sourceTabText, currentSourceId === s.id && styles.sourceTabTextActive]}>{s.name}</Text>
              </Pressable>
            ))}
          </ScrollView>
        </View>
      )}

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
            <Text style={styles.miniLabel}>{currentSource.plugin_name || 'Source'}</Text>
            <Text style={styles.mediaTitle} numberOfLines={1}>{currentSource.title || 'Тишина...'}</Text>
            <Text style={styles.mediaArtist} numberOfLines={1}>{currentSource.subtitle || '—'}</Text>
            {(currentSource.lyrics || (currentSource.timings && currentSource.timings.length > 0)) && (
              <Pressable onPress={() => setShowLyrics(true)} style={styles.lyricsBadge}>
                <Text style={styles.lyricsBadgeText}>{lang === 'ru' ? 'ТЕКСТ ПЕСНИ' : 'VIEW LYRICS'}</Text>
              </Pressable>
            )}
          </View>
          
          <View style={{ flexDirection: 'row', gap: 10 }}>
            <Pressable onPress={() => onCommand(pluginToUse, 'prev_track', finalTarget)} style={styles.miniBtn}><SkipBack size={22} color="#fff" /></Pressable>
            <Pressable onPress={() => onCommand(pluginToUse, 'play_pause', finalTarget)} style={styles.playBtn}>
              {currentSource.playing ? <Pause size={28} color="#0f172a" /> : <Play size={28} color="#0f172a" />}
            </Pressable>
            <Pressable onPress={() => onCommand(pluginToUse, 'next_track', finalTarget)} style={styles.miniBtn}><SkipForward size={22} color="#fff" /></Pressable>
          </View>
        </View>
      </View>

      <View style={styles.volumeSliderContainer}>
        <View style={[styles.row, { justifyContent: 'space-between', marginBottom: 8 }]}>
          <Volume2 size={16} color="#64748b" />
          <Text style={{ color: '#64748b', fontSize: 12, fontWeight: 'bold' }}>{slidingVol !== null ? slidingVol : (currentSource.volume || 0)}%</Text>
        </View>
        <View 
          style={styles.volumeSliderTrack}
          onStartShouldSetResponder={() => true}
          onResponderGrant={(e) => {
            const containerWidth = screenWidth - 80;
            const newVol = Math.round(Math.min(100, Math.max(0, (e.nativeEvent.locationX / containerWidth) * 100)));
            setSlidingVol(newVol);
            onCommand(pluginToUse, `set_volume:${newVol}`, finalTarget);
          }}
          onResponderMove={(e) => {
            const containerWidth = screenWidth - 80;
            const newVol = Math.round(Math.min(100, Math.max(0, (e.nativeEvent.locationX / containerWidth) * 100)));
            setSlidingVol(newVol);
            if (newVol % 3 === 0) onCommand(pluginToUse, `set_volume:${newVol}`, finalTarget);
          }}
          onResponderRelease={() => {
            setTimeout(() => setSlidingVol(null), 500);
          }}
        >
          <View style={{ 
            ...styles.volumeSliderFill, 
            width: `${slidingVol !== null ? slidingVol : (currentSource.volume || 0)}%` 
          }} />
          <View style={{ 
            ...styles.volumeSliderThumb, 
            left: `${slidingVol !== null ? slidingVol : (currentSource.volume || 0)}%` 
          }} />
        </View>
      </View>

      {!isStandalone && (
        <View style={styles.voiceInputContainer}>
          <Mic size={18} color="#38bdf8" />
          <TextInput
            style={styles.voiceInput}
            placeholder={t('Сказать Алисе...', 'Talk to Alice...')}
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

      <Modal visible={showLyrics} animationType="none" transparent={true} onRequestClose={() => setShowLyrics(false)}>
        <LyricsView 
          currentSource={currentSource} 
          lang={lang} 
          onClose={() => setShowLyrics(false)} 
        />
      </Modal>
    </View>
  );
});

export default MediaWidget;
