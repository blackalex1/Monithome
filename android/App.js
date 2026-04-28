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
  const [appLanguage, setAppLanguage] = useState('ru');
  const [allStats, setAllStats] = useState({});
  const [history, setHistory] = useState({}); 
  const [activeTargets, setActiveTargets] = useState({});
  const [lastUpdate, setLastUpdate] = useState('Нет данных');
  
  const [isPairing, setIsPairing] = useState(false);
  const [pairingInput, setPairingInput] = useState('');
  const [authToken, setAuthToken] = useState(null);

  useEffect(() => {
    AsyncStorage.getItem('server_ip').then(ip => { if (ip) setServerIp(ip); });
    AsyncStorage.getItem('auth_token').then(token => { if (token) setAuthToken(token); });
  }, []);

  const connectToServer = useCallback(() => {
    if (!serverIp) return;
    setLoading(true);
    setConnectionStatus(`Подключение...`);
    
    const newSocket = io(`http://${serverIp}:5000`, {
      auth: { token: authToken },
      transports: ['websocket', 'polling'],
      reconnection: true,
      reconnectionAttempts: Infinity,
      timeout: 10000
    });
    
    setSocket(newSocket);
    
    newSocket.on('connect', () => {
      setConnected(true); 
      setLoading(false); 
      setConnectionStatus('Подключено');
      AsyncStorage.setItem('server_ip', serverIp);
    });

    newSocket.on('disconnect', () => {
      setConnected(false);
      setLoading(false);
      setIsPairing(false);
    });

    newSocket.on('auth_required', () => {
      setIsPairing(true);
      setLoading(false);
      setConnectionStatus('Требуется авторизация');
    });

    newSocket.on('auth_success', (data) => {
      AsyncStorage.setItem('auth_token', data.token);
      setAuthToken(data.token);
      setIsPairing(false);
      setConnectionStatus('Авторизовано');
    });

    newSocket.on('auth_failed', (data) => {
      Alert.alert(appLanguage === 'ru' ? 'Ошибка' : 'Error', 
                  appLanguage === 'ru' ? 'Неверный код' : 'Invalid code');
    });

    newSocket.on('pairing_cancel', () => {
      setIsPairing(false);
      setConnectionStatus(appLanguage === 'ru' ? 'Отклонено сервером' : 'Rejected by server');
      newSocket.disconnect();
    });

    newSocket.on('connect_error', (err) => {
      setConnectionStatus(`Ошибка: ${err.message}`); setLoading(false); setConnected(false);
    });
    
    newSocket.on('ui_config', (data) => {
      const now = new Date().toLocaleTimeString();
      const configs = data.config || [];
      const lang = data.language || 'ru';
      setUiConfigs(configs);
      setAppLanguage(lang);
      setLastUpdate(now);
    });

    newSocket.on('stats', (data) => {
      const pluginId = data.plugin_id || 'system_stats';
      
      setAllStats(prev => {
        const prevPluginData = prev[pluginId] || {};
        // Если пришла пустая обложка, сохраняем старую
        const mergedData = { ...data };
        if (mergedData.cover === null && prevPluginData.cover) {
          mergedData.cover = prevPluginData.cover;
        }
        return { ...prev, [pluginId]: mergedData };
      });
      
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
  }, [serverIp, authToken, appLanguage]);

  const sendCommand = useCallback((pluginId, action, target) => {
    const finalTarget = target || activeTargets[pluginId] || 'pc';
    if (socket && connected) {
      socket.emit('command', { plugin_id: pluginId, action: action, target: finalTarget });
    }
  }, [socket, connected, activeTargets]);

  const setTarget = useCallback((pluginId, targetId) => {
    setActiveTargets(prev => ({ ...prev, [pluginId]: targetId }));
  }, []);

  const submitPairingCode = () => {
    if (socket && pairingInput) {
      socket.emit('auth_attempt', { code: pairingInput });
    }
  };

  // Экран авторизации (Pairing)
  if (isPairing) {
    return (
      <View style={[styles.container, { justifyContent: 'center', alignItems: 'center' }]}>
        <StatusBar barStyle="light-content" />
        <View style={[styles.glassCard, { width: width - 40, padding: 32 }]}>
          <View style={{ width: 80, height: 80, borderRadius: 40, backgroundColor: 'rgba(56, 189, 248, 0.1)', alignItems: 'center', justifyContent: 'center', alignSelf: 'center', marginBottom: 24 }}>
            <IconMap.Shield size={40} color="#38bdf8" />
          </View>
          <Text style={[styles.hostname, { textAlign: 'center', fontSize: 24 }]}>
            {appLanguage === 'ru' ? 'Авторизация' : 'Authorization'}
          </Text>
          <Text style={[styles.osText, { textAlign: 'center', marginBottom: 32 }]}>
            {appLanguage === 'ru' ? 'Введите код, отображаемый на вашем ПК' : 'Enter the code displayed on your PC'}
          </Text>
          
          <View style={[styles.voiceInputContainer, { marginTop: 0, marginBottom: 24, paddingHorizontal: 0 }]}>
            <TextInput
              style={[styles.voiceInput, { textAlign: 'center', fontSize: 32, letterSpacing: 10, fontWeight: 'bold' }]}
              placeholder="000000"
              placeholderTextColor="#475569"
              keyboardType="number-pad"
              maxLength={6}
              value={pairingInput}
              onChangeText={setPairingInput}
            />
          </View>
          
          <Pressable 
            onPress={submitPairingCode}
            style={({pressed}) => [
              styles.playBtn, 
              { width: '100%', borderRadius: 20, height: 60 },
              pressed && { opacity: 0.8 }
            ]}
          >
            <Text style={{ color: '#0f172a', fontSize: 18, fontWeight: '800' }}>
              {appLanguage === 'ru' ? 'ПОДТВЕРДИТЬ' : 'CONFIRM'}
            </Text>
          </Pressable>
          
          <Pressable onPress={() => { setIsPairing(false); setConnected(false); socket?.disconnect(); }} style={{ marginTop: 24, alignItems: 'center' }}>
            <Text style={{ color: '#ef4444', fontSize: 14, fontWeight: '700' }}>
              {appLanguage === 'ru' ? 'ОТМЕНА' : 'CANCEL'}
            </Text>
          </Pressable>
        </View>
      </View>
    );
  }

  if (!connected) {
    return (
      <View style={[styles.container, { justifyContent: 'center', alignItems: 'center' }]}>
        <StatusBar barStyle="light-content" />
        <View style={[styles.glassCard, { width: width - 40, padding: 32 }]}>
          <View style={{ width: 80, height: 80, borderRadius: 40, backgroundColor: 'rgba(56, 189, 248, 0.1)', alignItems: 'center', justifyContent: 'center', alignSelf: 'center', marginBottom: 24 }}>
            <Zap size={40} color="#38bdf8" />
          </View>
          <Text style={[styles.hostname, { textAlign: 'center', fontSize: 32 }]}>Monithome</Text>
          <Text style={[styles.osText, { textAlign: 'center', marginBottom: 32 }]}>Smart PC & Home Control</Text>
          
          <Text style={styles.sectionTitle}>{appLanguage === 'ru' ? 'IP адрес сервера' : 'Server IP Address'}</Text>
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
            {loading ? <ActivityIndicator color="#fff" /> : <Text style={{ color: '#0f172a', fontSize: 18, fontWeight: '800' }}>{appLanguage === 'ru' ? 'ВОЙТИ В СИСТЕМУ' : 'LOGIN'}</Text>}
          </Pressable>
          <Text style={[styles.statusText, { textAlign: 'center', marginTop: 20, color: '#64748b' }]}>{connectionStatus}</Text>
          
          <Pressable onPress={() => Linking.openURL('https://github.com/blackalex1')} style={{ marginTop: 40, alignItems: 'center' }}>
            <Text style={{ color: '#475569', fontSize: 12 }}>{appLanguage === 'ru' ? 'Разработано BlackAlex1' : 'Developed by BlackAlex1'}</Text>
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
            <Text style={styles.hostname}>{allStats['system_stats']?.hostname || (appLanguage === 'ru' ? 'Мой ПК' : 'My PC')}</Text>
            <Text style={styles.osText}>{allStats['system_stats']?.os || 'Windows 11'} • Online</Text>
          </View>
          <View style={styles.statusBadge}>
            <Text style={styles.statusText}>Live</Text>
          </View>
        </View>

        <View style={styles.mainLayout}>
          {(() => {
            const renderedMedia = new Set();
            return uiConfigs.map(plugin => {
              if ((!plugin.widgets || plugin.widgets.length === 0) && (!plugin.actions || plugin.actions.length === 0)) return null;
              return (
                <View key={plugin.id}>
                  <Text style={styles.pluginTitle}>
                    {appLanguage === 'en' ? plugin.name_en || plugin.name : plugin.name}
                  </Text>
                  {plugin.widgets?.map(w => {
                    if (w.type === 'unified_media') {
                      if (renderedMedia.has('unified_media')) return null;
                      renderedMedia.add('unified_media');
                    }
                    return (
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
                        lang={appLanguage}
                      />
                    );
                  })}
                {plugin.actions?.map(a => (
                  <View key={a.id} style={styles.glassCard}>
                    <Text style={styles.sectionTitle}>{appLanguage === 'en' ? a.label_en || a.label : a.label}</Text>
                    <View style={styles.rowLayout}>
                      {a.buttons?.map((btn, i) => {
                        const IconComp = IconMap[btn.icon] || Power;
                        return (
                          <Pressable 
                            key={i} 
                            onPress={() => {
                              if (btn.need_confirm) {
                                Alert.alert(
                                  appLanguage === 'ru' ? 'Подтверждение' : 'Confirmation',
                                  appLanguage === 'ru' 
                                    ? `Вы уверены, что хотите выполнить: ${btn.label}?` 
                                    : `Are you sure you want to: ${btn.label_en || btn.label}?`,
                                  [
                                    { text: appLanguage === 'ru' ? 'Отмена' : 'Cancel', style: 'cancel' },
                                    { text: appLanguage === 'ru' ? 'Да' : 'Yes', onPress: () => sendCommand(plugin.id, btn.action) }
                                  ]
                                );
                              } else {
                                sendCommand(plugin.id, btn.action);
                              }
                            }}
                            style={({pressed}) => [styles.actionBtn, pressed && {backgroundColor: 'rgba(255,255,255,0.1)'}]}
                          >
                            <IconComp size={20} color={btn.color === 'text-red-500' ? '#ef4444' : '#38bdf8'} />
                            <Text style={styles.actionBtnText}>{appLanguage === 'en' ? btn.label_en || btn.label : btn.label}</Text>
                          </Pressable>
                        );
                      })}
                    </View>
                  </View>
                ))}
              </View>
            );
          })})() }
        </View>
          
          <View style={{ paddingVertical: 20, alignItems: 'center', borderTopWidth: 1, borderTopColor: 'rgba(255,255,255,0.05)', marginTop: 20 }}>
            <Text style={{ color: '#38bdf8', fontSize: 10, fontWeight: '700' }}>LAST SYNC: {lastUpdate}</Text>
          </View>
          
          <Pressable onPress={() => Linking.openURL('https://github.com/blackalex1')} style={{ paddingVertical: 30, alignItems: 'center', opacity: 0.5 }}>
            <Text style={{ color: '#fff', fontSize: 12 }}>Разработано BlackAlex1</Text>
            <Text style={{ color: '#38bdf8', fontSize: 12, marginTop: 4 }}>github.com/blackalex1</Text>
          </Pressable>
        </ScrollView>
      </View>
  );
}
