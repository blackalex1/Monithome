import React from 'react';
import { View, Text, ScrollView, SafeAreaView, StatusBar, Pressable } from 'react-native';
import { styles } from './styles';
import { IconMap } from './IconMap';
import MemoWidget from './MemoWidget';

export default function Dashboard({ 
  allStats, 
  mediaStats, 
  uiConfigs, 
  history, 
  pcStatus, 
  activeTargets, 
  sendCommand, 
  setTarget, 
  lastUpdate, 
  appLanguage,
  handleInteraction
}) {
  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" />
      <View style={styles.header}>
        <View>
          <Text style={styles.hostname}>{pcStatus.hostname || 'PC'}</Text>
          <View style={{ flexDirection: 'row', alignItems: 'center' }}>
            <View style={[styles.statusDot, { backgroundColor: pcStatus.status === 'online' ? '#22c55e' : '#ef4444' }]} />
            <Text style={styles.osText}>{pcStatus.os || 'Windows'} • {pcStatus.status}</Text>
          </View>
        </View>
        <Pressable onPress={() => {}} style={styles.iconBtn}>
          <IconMap.Settings color="#94a3b8" size={24} />
        </Pressable>
      </View>

      <ScrollView 
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        onScrollBeginDrag={() => handleInteraction(true)}
        onScrollEndDrag={() => handleInteraction(false)}
      >
        <View style={styles.grid}>
          {uiConfigs.map(plugin => (
            <View key={plugin.id} style={{ width: '100%' }}>
              {plugin.widgets && plugin.widgets.map((widget, wIdx) => (
                <MemoWidget 
                  key={`${plugin.id}_${wIdx}`}
                  widget={widget}
                  pluginId={plugin.id}
                  stats={allStats[plugin.id] || {}}
                  history={history}
                  onCommand={sendCommand}
                  onSetTarget={setTarget}
                  activeTargets={activeTargets}
                  allStats={allStats}
                  mediaStats={mediaStats}
                  allPlugins={uiConfigs}
                  lang={appLanguage}
                  onInteraction={handleInteraction}
                />
              ))}
            </View>
          ))}
        </View>
        
        <View style={{ padding: 20, alignItems: 'center' }}>
          <Text style={{ color: '#475569', fontSize: 12 }}>
            {appLanguage === 'ru' ? 'Последнее обновление: ' : 'Last update: '} {lastUpdate}
          </Text>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}
