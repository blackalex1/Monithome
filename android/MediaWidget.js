import React, { useState, useMemo, useEffect, useRef } from 'react';
import { View, Text, Pressable, Image, TextInput, Dimensions, ScrollView, Modal, FlatList } from 'react-native';
import { styles } from './styles';
import { Theme } from './Theme';
import { 
  Music, Play, Pause, SkipBack, SkipForward, Volume2, Mic, X
} from './IconMap';

const { width: screenWidth } = Dimensions.get('window');

const MediaWidget = React.memo(({ widget, allStats, allPlugins, activeTargets, onCommand, onInteraction, lang }) => {
  const t = (label, labelEn) => lang === 'en' ? (labelEn || label) : label;
  const [aliceText, setAliceText] = useState("");
  const [localSource, setLocalSource] = useState(null);
  const [slidingVol, setSlidingVol] = useState(null);
  const [showLyrics, setShowLyrics] = useState(false);
  const [currentLineIndex, setCurrentLineIndex] = useState(-1);
  const flatListRef = useRef(null);

  const formatCover = (cover) => {
    if (!cover) return null;
    if (cover.startsWith('http') || cover.startsWith('data:image')) return cover;
    return `data:image/png;base64,${cover}`;
  };

  // Выделяем только нужные нам статы для медиа и лирики, чтобы не реагировать на каждый чих системных плагинов
  const relevantStats = useMemo(() => {
    const mediaIds = allPlugins.filter(p => p.type === 'media_source' || p.type === 'lyrics_provider').map(p => p.id);
    const obj = {};
    mediaIds.forEach(id => { obj[id] = allStats[id]; });
    return obj;
  }, [allPlugins, allStats]);

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

    // Если в виджете указан конкретный device_id, фильтруем по нему
    let filtered = allSources;
    if (widget.device_id) {
      filtered = filtered.filter(s => s.id === widget.device_id);
    }
    return filtered;
  }, [allPlugins, relevantStats, widget.device_id]);

  const [lastActiveId, setLastActiveId] = useState(widget.device_id || (sources.find(s => s.playing)?.id) || sources[0]?.id);

  // Следим за играющим источником и обновляем "последний активный"
  useEffect(() => {
    const playing = sources.find(s => s.playing);
    if (playing) {
      setLastActiveId(playing.id);
    } else if (!lastActiveId && sources.length > 0) {
      setLastActiveId(sources[0].id);
    }
  }, [sources, lastActiveId]);

  const currentSourceId = useMemo(() => {
    // 1. Приоритет явно заданному ID в настройках виджета
    if (widget.device_id) return widget.device_id;
    // 2. Приоритет ручному выбору пользователя через вкладки
    if (localSource && sources.find(s => s.id === localSource)) return localSource;
    // 3. Липкий выбор последнего активного (играющего)
    if (lastActiveId && sources.find(s => s.id === lastActiveId)) {
      return lastActiveId;
    }
    const playing = sources.find(s => s.playing);
    return playing ? playing.id : (sources[0]?.id);
  }, [widget.device_id, localSource, lastActiveId, sources]);

  const currentSource = useMemo(() => {
    if (sources.length === 0) return null;
    return sources.find(s => s.id === currentSourceId) || sources[0];
  }, [sources, currentSourceId]);

  useEffect(() => {
    if (!showLyrics || !currentSource || !currentSource.timings || currentSource.timings.length === 0) return;
    const interval = setInterval(() => {
      const progress = currentSource.progress || 0;
      const lastUpd = currentSource.last_update || (Date.now() / 1000);
      const isPlaying = currentSource.playing;
      const currentTimeMs = (progress + (isPlaying ? (Date.now() / 1000 - lastUpd) : 0)) * 1000;
      
      let index = -1;
      for (let i = 0; i < currentSource.timings.length; i++) {
        if (currentSource.timings[i].time <= currentTimeMs) index = i;
        else break;
      }
      if (index !== currentLineIndex) {
        setCurrentLineIndex(index);
        if (flatListRef.current && index !== -1) {
          try { flatListRef.current.scrollToIndex({ index, animated: true, viewPosition: 0.5 }); } catch (e) {}
        }
      }
    }, 100);
    return () => clearInterval(interval);
  }, [showLyrics, currentSource?.track_id, currentSource?.progress, currentSource?.playing, currentLineIndex]);

  const sourceTabs = useMemo(() => {
    if (widget.device_id || sources.length <= 1) return null;
    return (
      <View style={{ marginBottom: 20 }}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false}>
          {sources.map(s => {
            const isActive = currentSourceId === s.id;
            return (
              <Pressable 
                key={s.id} 
                onPress={() => setLocalSource(s.id)}
                style={[styles.sourceTab, isActive && styles.sourceTabActive]}
                hitSlop={{ top: 15, bottom: 15, left: 10, right: 10 }}
              >
                <Text style={[styles.sourceTabText, isActive && styles.sourceTabTextActive]}>{s.name}</Text>
              </Pressable>
            );
          })}
        </ScrollView>
      </View>
    );
  }, [sources, currentSourceId, widget.device_id]);

  if (!currentSource || sources.length === 0) return null;

  const isStandalone = currentSource.type === 'standalone';
  const pluginToUse = currentSource.plugin_id;
  const finalTarget = isStandalone ? 'pc' : currentSource.id;

  return (
    <View style={styles.glassCard}>
      {sourceTabs}

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
            <Text style={styles.miniLabel}>{currentSource.plugin_name || (isStandalone ? 'System' : 'Remote')}</Text>
            <Text style={styles.mediaTitle} numberOfLines={1}>{currentSource.title || 'Тишина...'}</Text>
            <Text style={styles.mediaArtist} numberOfLines={1}>{currentSource.subtitle || '—'}</Text>
            {(currentSource.lyrics || (currentSource.timings && currentSource.timings.length > 0)) && (
              <Pressable onPress={() => setShowLyrics(true)} style={styles.lyricsBadge}>
                <Text style={styles.lyricsBadgeText}>{lang === 'ru' ? 'ТЕКСТ ПЕСНИ' : 'VIEW LYRICS'}</Text>
              </Pressable>
            )}
          </View>
          
          <View style={{ flexDirection: 'row', gap: 10 }}>
            <Pressable 
              onPressIn={() => {
                onInteraction(true);
                console.log(`[MEDIA] Pressed PREV at ${new Date().toISOString()}`);
                onCommand(pluginToUse, 'prev_track', finalTarget);
                setTimeout(() => onInteraction(false), 1000);
              }} 
              style={styles.miniBtn}
            >
              <SkipBack size={22} color="#fff" />
            </Pressable>
            <Pressable 
              onPressIn={() => {
                onInteraction(true);
                console.log(`[MEDIA] Pressed PLAY/PAUSE at ${new Date().toISOString()}`);
                onCommand(pluginToUse, 'play_pause', finalTarget);
                setTimeout(() => onInteraction(false), 1000);
              }} 
              style={styles.playBtn}
            >
              {currentSource.playing ? <Pause size={28} color="#0f172a" /> : <Play size={28} color="#0f172a" />}
            </Pressable>
            <Pressable 
              onPressIn={() => {
                onInteraction(true);
                console.log(`[MEDIA] Pressed NEXT at ${new Date().toISOString()}`);
                onCommand(pluginToUse, 'next_track', finalTarget);
                setTimeout(() => onInteraction(false), 1000);
              }} 
              style={styles.miniBtn}
            >
              <SkipForward size={22} color="#fff" />
            </Pressable>
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
            onInteraction(true);
            const newVol = Math.round((e.nativeEvent.locationX / (screenWidth - 80)) * 100);
            setSlidingVol(newVol);
            onCommand(pluginToUse, `set_volume:${newVol}`, finalTarget);
          }}
          onResponderMove={(e) => {
            const newVol = Math.round((e.nativeEvent.locationX / (screenWidth - 80)) * 100);
            setSlidingVol(newVol);
            if (newVol % 3 === 0) onCommand(pluginToUse, `set_volume:${newVol}`, finalTarget);
          }}
          onResponderRelease={() => {
            onInteraction(false);
            setTimeout(() => setSlidingVol(null), 500);
          }}
        >
          <View style={[styles.volumeSliderFill, { width: `${slidingVol !== null ? slidingVol : (currentSource.volume || 0)}%` }]} />
          <View style={[styles.volumeSliderThumb, { left: `${slidingVol !== null ? slidingVol : (currentSource.volume || 0)}%` }]} />
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

      <Modal visible={showLyrics} animationType="slide" transparent={true}>
        <View style={styles.lyricsModal}>
          <View style={styles.lyricsHeader}>
            <View style={{ flex: 1, marginRight: 15 }}>
              <Text style={styles.lyricsTrackTitle} numberOfLines={1}>{currentSource.title}</Text>
              <Text style={styles.lyricsTrackArtist} numberOfLines={1}>{currentSource.subtitle}</Text>
            </View>
            <Pressable onPress={() => setShowLyrics(false)} style={styles.miniBtn}><X size={24} color="#fff" /></Pressable>
          </View>
          
          {currentSource.timings && currentSource.timings.length > 0 ? (
            <FlatList
              ref={flatListRef}
              data={currentSource.timings}
              keyExtractor={(_, i) => i.toString()}
              showsVerticalScrollIndicator={false}
              contentContainerStyle={{ paddingBottom: 100 }}
              renderItem={({ item, index }) => (
                <View style={styles.lyricsLineContainer}>
                  <Text style={[styles.lyricsLine, index === currentLineIndex && styles.lyricsLineActive]}>
                    {item.text}
                  </Text>
                </View>
              )}
            />
          ) : (
            <ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={{ paddingBottom: 100 }}>
              <Text style={[styles.lyricsLine, { color: '#fff', opacity: 0.8 }]}>
                {currentSource.lyrics || (lang === 'ru' ? 'Текст песни отсутствует' : 'No lyrics available')}
              </Text>
            </ScrollView>
          )}
        </View>
      </Modal>
    </View>
  );
});

export default MediaWidget;
