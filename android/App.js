import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { 
  View, Text, ScrollView, StatusBar, Pressable, 
  ActivityIndicator, TextInput, Alert, Dimensions, Linking
} from 'react-native';
import io from 'socket.io-client';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Activity, Power, IconMap, Cpu, Zap } from './IconMap';
import { styles } from './styles';
import MemoWidget from './MemoWidget';

const { width } = Dimensions.get('window');

export default function App() {
  const [socket, setSocket] = useState(null);
  const [serverIp, setServerIp] = useState('');
  const [connected, setConnected] = useState(false);
  const [loading, setLoading] = useState(false);
  const [connectionStatus, setConnectionStatus] = useState('Ожидание IP');
  const [uiConfigs, setUiConfigs] = useState([]);
  const [allStats, setAllStats] = useState({});
  const [history, setHistory] = useState({}); 
  const [activeTargets, setActiveTargets] = useState({});

  useEffect(() => {
    AsyncStorage.getItem('server_ip').then(ip => { if (ip) setServerIp(ip); });
  }, []);

  const connectToServer = useCallback(() => {
    if (!serverIp) return;
    setLoading(true);
    setConnectionStatus(`Подключение...`);
    const newSocket = io(`http://${serverIp}:5000`, {
      auth: { token: '1234' },
      transports: ['websocket', 'polling'],
      reconnection: true,
      reconnectionAttempts: Infinity,
      timeout: 10000
    });
    setSocket(newSocket);
    newSocket.on('connect', () => {
      setConnected(true); setLoading(false); setConnectionStatus('Подключено');
      AsyncStorage.setItem('server_ip', serverIp);
    });
    newSocket.on('connect_error', (err) => {
      setConnectionStatus(`Ошибка: ${err.message}`); setLoading(false); setConnected(false);
    });
    newSocket.on('ui_config', (configs) => setUiConfigs(configs));
    newSocket.on('stats', (data) => {
      const pluginId = data.plugin_id || 'system_stats';
      setAllStats(prev => ({ ...prev, [pluginId]: data }));
      
      const numericKeys = Object.keys(data).filter(k => typeof data[k] === 'number');
      if (numericKeys.length > 0) {
        setHistory(prev => {
          const pluginHist = { ...(prev[pluginId] || {}) };
          numericKeys.forEach(key => {
            const currentArr = pluginHist[key] || Array(30).fill(0);
            pluginHist[key] = [...currentArr, data[key]].slice(-30);
          });
          return { ...prev, [pluginId]: pluginHist };
        });
      }
    });
    return () => newSocket.close();
  }, [serverIp]);

  const sendCommand = useCallback((pluginId, action, target) => {
    const finalTarget = target || activeTargets[pluginId] || 'pc';
    if (socket && connected) {
      socket.emit('command', { plugin_id: pluginId, action: action, target: finalTarget });
    }
  }, [socket, connected, activeTargets]);

  const setTarget = useCallback((pluginId, targetId) => {
    setActiveTargets(prev => ({ ...prev, [pluginId]: targetId }));
  }, []);

  if (!connected) {
    return (
      <View style={[styles.container, { justifyContent: 'center', alignItems: 'center' }]}>
        <StatusBar barStyle="light-content" />
        <View style={[styles.glassCard, { width: width - 40, padding: 32 }]}>
          <View style={{ width: 80, height: 80, borderRadius: 40, backgroundColor: 'rgba(56, 189, 248, 0.1)', alignItems: 'center', justifyContent: 'center', alignSelf: 'center', marginBottom: 24 }}>
            <Zap size={40} color="#38bdf8" />
          </View>
          <Text style={[styles.hostname, { textAlign: 'center', fontSize: 32 }]}>PC Monitor</Text>
          <Text style={[styles.osText, { textAlign: 'center', marginBottom: 32 }]}>Удаленный мониторинг ПК</Text>
          
          <Text style={styles.sectionTitle}>IP адрес сервера</Text>
          <View style={[styles.voiceInputContainer, { marginTop: 0, marginBottom: 24 }]}>
            <TextInput
              style={styles.voiceInput}
              placeholder="Например: 192.168.1.100"
              placeholderTextColor="#475569"
              value={serverIp}
              onChangeText={setServerIp}
            />
          </View>
          
          <Pressable 
            onPress={connectToServer}
            disabled={loading}
            style={({pressed}) => [
              styles.playBtn, 
              { width: '100%', borderRadius: 20, height: 60 },
              pressed && { opacity: 0.8 },
              loading && { backgroundColor: '#1e293b' }
            ]}
          >
            {loading ? <ActivityIndicator color="#fff" /> : <Text style={{ color: '#0f172a', fontSize: 18, fontWeight: '800' }}>ВОЙТИ В СИСТЕМУ</Text>}
          </Pressable>
          <Text style={[styles.statusText, { textAlign: 'center', marginTop: 20, color: '#64748b' }]}>{connectionStatus}</Text>
          
          <Pressable onPress={() => Linking.openURL('https://github.com/blackalex1')} style={{ marginTop: 40, alignItems: 'center' }}>
            <Text style={{ color: '#475569', fontSize: 12 }}>Разработано BlackAlex1</Text>
            <Text style={{ color: '#38bdf8', fontSize: 12, marginTop: 4 }}>github.com/blackalex1</Text>
          </Pressable>
        </View>
      </View>
    );
  }

  return (
    <View style={{ flex: 1 }}>
      {!connected && (
        <View style={{ position: 'absolute', top: 0, left: 0, right: 0, zIndex: 999, backgroundColor: '#ef4444', padding: 8, alignItems: 'center' }}>
          <Text style={{ color: '#fff', fontSize: 11, fontWeight: '900', letterSpacing: 1 }}>СОЕДИНЕНИЕ ПОТЕРЯНО • ПЕРЕПОДКЛЮЧЕНИЕ...</Text>
        </View>
      )}
      <ScrollView style={styles.container} contentContainerStyle={{ paddingBottom: 60 }}>
        <StatusBar barStyle="light-content" />
        <View style={styles.header}>
          <View style={{ flex: 1 }}>
            <Text style={styles.hostname}>{allStats['system_stats']?.hostname || 'Мой ПК'}</Text>
            <Text style={styles.osText}>{allStats['system_stats']?.os || 'Windows 11'} • Online</Text>
          </View>
          <View style={styles.statusBadge}>
            <Text style={styles.statusText}>Live</Text>
          </View>
        </View>

        <View style={styles.mainLayout}>
          {uiConfigs.map(plugin => {
            if ((!plugin.widgets || plugin.widgets.length === 0) && (!plugin.actions || plugin.actions.length === 0)) return null;
            return (
              <View key={plugin.id}>
                <Text style={styles.pluginTitle}>{plugin.name}</Text>
                {plugin.widgets?.map(w => (
                  <MemoWidget 
                    key={w.id} 
                    widget={w} 
                    pluginId={plugin.id} 
                    stats={allStats[plugin.id]} 
                    history={history[plugin.id]}
                    onCommand={sendCommand}
                    activeTarget={activeTargets[plugin.id]}
                    activeTargets={activeTargets}
                    onSetTarget={setTarget}
                    allStats={allStats}
                  />
                ))}
                {plugin.actions?.map(a => (
                  <View key={a.id} style={styles.glassCard}>
                    <Text style={styles.sectionTitle}>{a.label}</Text>
                    <View style={styles.rowLayout}>
                      {a.buttons?.map((btn, i) => {
                        const IconComp = IconMap[btn.icon] || Power;
                        return (
                          <Pressable 
                            key={i} 
                            onPress={() => {
                              if (btn.need_confirm) {
                                Alert.alert(
                                  'Подтверждение',
                                  `Вы уверены, что хотите выполнить: ${btn.label}?`,
                                  [
                                    { text: 'Отмена', style: 'cancel' },
                                    { text: 'Да', onPress: () => sendCommand(plugin.id, btn.action) }
                                  ]
                                );
                              } else {
                                sendCommand(plugin.id, btn.action);
                              }
                            }}
                            style={({pressed}) => [styles.actionBtn, pressed && {backgroundColor: 'rgba(255,255,255,0.1)'}]}
                          >
                            <IconComp size={20} color={btn.color === 'text-red-500' ? '#ef4444' : '#38bdf8'} />
                            <Text style={styles.actionBtnText}>{btn.label}</Text>
                          </Pressable>
                        );
                      })}
                    </View>
                  </View>
                ))}
              </View>
            );
          })}
          </View>
          
          <Pressable onPress={() => Linking.openURL('https://github.com/blackalex1')} style={{ paddingVertical: 30, alignItems: 'center', opacity: 0.5 }}>
            <Text style={{ color: '#fff', fontSize: 12 }}>Разработано BlackAlex1</Text>
            <Text style={{ color: '#38bdf8', fontSize: 12, marginTop: 4 }}>github.com/blackalex1</Text>
          </Pressable>
        </ScrollView>
      </View>
  );
}
