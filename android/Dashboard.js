import React, { useCallback, Component } from 'react';
import { View, Text, FlatList, SafeAreaView, StatusBar, Pressable } from 'react-native';
import { styles } from './styles';
import { IconMap } from './IconMap';
import MemoWidget from './MemoWidget';

// Предохранитель для изоляции ошибок отрисовки конкретного плагина
class PluginErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(error) {
    return { hasError: true };
  }

  componentDidCatch(error, errorInfo) {
    console.log(`[PLUGIN ERROR] ${this.props.pluginId}:`, error, errorInfo);
  }

  render() {
    if (this.state.hasError) {
      return (
        <View style={[styles.glassCard, { padding: 15, borderColor: '#ef4444', borderStyle: 'dashed' }]}>
          <Text style={{ color: '#ef4444', fontWeight: 'bold' }}>Plugin Error: {this.props.pluginId}</Text>
          <Pressable onPress={() => this.setState({ hasError: false })} style={{ marginTop: 10 }}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>Tap to Retry</Text>
          </Pressable>
        </View>
      );
    }
    return this.props.children;
  }
}

const PluginItem = React.memo(({ 
  plugin, 
  sendCommand, 
  setTarget, 
  activeTargets, 
  allPlugins, 
  appLanguage, 
  handleInteraction, 
  serverIp 
}) => {
  return (
    <PluginErrorBoundary pluginId={plugin.id}>
      <View style={{ width: '100%', marginBottom: 10 }}>
        {plugin.widgets && plugin.widgets.map((widget, wIdx) => (
          <MemoWidget 
            key={`${plugin.id}_${wIdx}`}
            widget={widget}
            pluginId={plugin.id}
            onCommand={sendCommand}
            onSetTarget={setTarget}
            activeTargets={activeTargets}
            allPlugins={allPlugins}
            lang={appLanguage}
            onInteraction={handleInteraction}
            serverIp={serverIp}
          />
        ))}
      </View>
    </PluginErrorBoundary>
  );
});

const DashboardFooter = React.memo(({ lastUpdate, appLanguage }) => (
  <View style={{ padding: 20, alignItems: 'center' }}>
    <Text style={{ color: '#475569', fontSize: 12 }}>
      {appLanguage === 'ru' ? 'Последнее обновление: ' : 'Last update: '} {lastUpdate}
    </Text>
  </View>
));

export default function Dashboard({ 
  uiConfigs, 
  pcStatus, 
  activeTargets, 
  sendCommand, 
  setTarget, 
  lastUpdate, 
  appLanguage,
  handleInteraction,
  serverIp
}) {
  
  const renderPlugin = useCallback(({ item: plugin }) => (
    <PluginItem 
      plugin={plugin}
      sendCommand={sendCommand}
      setTarget={setTarget}
      activeTargets={activeTargets}
      allPlugins={uiConfigs}
      appLanguage={appLanguage}
      handleInteraction={handleInteraction}
      serverIp={serverIp}
    />
  ), [uiConfigs, activeTargets, appLanguage, serverIp]);

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" />
      
      <FlatList
        data={uiConfigs}
        renderItem={renderPlugin}
        keyExtractor={item => item.id}
        contentContainerStyle={[styles.scrollContent, { paddingBottom: 100 }]}
        removeClippedSubviews={true}
        initialNumToRender={5}
        maxToRenderPerBatch={3}
        windowSize={5}
        ListHeaderComponent={() => (
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
        )}
        ListFooterComponent={() => (
          <DashboardFooter lastUpdate={lastUpdate} appLanguage={appLanguage} />
        )}
      />
    </SafeAreaView>
  );
}
