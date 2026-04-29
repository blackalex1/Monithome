import React, { useState, useEffect, useCallback, useRef } from 'react';
import { InteractionManager, Alert } from 'react-native';
import io from 'socket.io-client';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { PairingScreen, LoginScreen } from './AuthScreens';
import Dashboard from './Dashboard';

export default function App() {
  const [socket, setSocket] = useState(null);
  const [serverIp, setServerIp] = useState('');
  const [authToken, setAuthToken] = useState(null);
  const [connected, setConnected] = useState(false);
  const [loading, setLoading] = useState(false);
  const [connectionStatus, setConnectionStatus] = useState('Ожидание IP');
  const [uiConfigs, setUiConfigs] = useState([]);
  const [appLanguage, setAppLanguage] = useState('ru');
  const [allStats, setAllStats] = useState({});
  const [mediaStats, setMediaStats] = useState({});
  const [history, setHistory] = useState({}); 
  const [activeTargets, setActiveTargets] = useState({});
  const [lastUpdate, setLastUpdate] = useState('Нет данных');
  
  const [isPairing, setIsPairing] = useState(false);
  const [pairingInput, setPairingInput] = useState('');
  const [userInteracting, setUserInteracting] = useState(false);
  const [pcStatus, setPcStatus] = useState({ status: 'offline', hostname: '', os: '' });
  
  const pendingStats = useRef({});
  const isInteractingRef = useRef(false);
  const lastHistoryUpdate = useRef(0);
  const pendingHistory = useRef({});

  useEffect(() => {
    const flushInterval = setInterval(() => {
      if (isInteractingRef.current) return;
      const hasStats = Object.keys(pendingStats.current).length > 0;
      if (!hasStats) return;

      InteractionManager.runAfterInteractions(() => {
        const updates = { ...pendingStats.current };
        pendingStats.current = {};
        
        let mediaChanged = false;
        const newHistEntries = {};

        // 1. Сначала рассчитываем все изменения
        Object.keys(updates).forEach(pId => {
          const data = updates[pId];
          const plugin = uiConfigs.find(p => p.id === pId);
          if (plugin && (plugin.type === 'media_source' || plugin.type === 'lyrics_provider')) {
            mediaChanged = true;
          }

          // Подготовка истории
          const numericKeys = Object.keys(data).filter(k => typeof data[k] === 'number');
          if (numericKeys.length > 0) {
            newHistEntries[pId] = { keys: numericKeys, data: data };
          }
        });

        // 2. Обновляем основной стейт
        setAllStats(prev => {
          const newState = { ...prev };
          Object.keys(updates).forEach(pId => {
            const prevData = newState[pId] || {};
            const merged = { ...prevData, ...updates[pId] };
            if (merged.cover === null && prevData.cover) merged.cover = prevData.cover;
            newState[pId] = merged;
          });

          // 3. Если были медиа-данные, обновляем медиа-стейт
          if (mediaChanged) {
            setMediaStats(prevMedia => {
              const newMedia = { ...prevMedia };
              Object.keys(updates).forEach(pId => {
                const plugin = uiConfigs.find(p => p.id === pId);
                if (plugin && (plugin.type === 'media_source' || plugin.type === 'lyrics_provider')) {
                  newMedia[pId] = newState[pId];
                }
              });
              return newMedia;
            });
          }
          
          return newState;
        });

        // 4. Обновляем историю параллельно
        if (Object.keys(newHistEntries).length > 0) {
          setHistory(hPrev => {
            const newHistory = { ...hPrev };
            Object.keys(newHistEntries).forEach(pId => {
              const { keys, data } = newHistEntries[pId];
              const pluginHist = newHistory[pId] || {};
              const newPluginHist = { ...pluginHist };
              keys.forEach(key => {
                const currentArr = pluginHist[key] || Array(30).fill(0);
                newPluginHist[key] = [...currentArr, data[key]].slice(-30);
              });
              newHistory[pId] = newPluginHist;
            });
            return newHistory;
          });
        }
      });
    }, 300);
    return () => clearInterval(flushInterval);
  }, [uiConfigs]);

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
      transports: ['websocket'],
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
      setUiConfigs(data.config || []);
      setAppLanguage(data.language || 'ru');
      setLastUpdate(now);
      
      // Попробуем обновить статус из конфига, если он там есть
      if (data.hostname) {
        setPcStatus(prev => ({ ...prev, status: 'online', hostname: data.hostname, os: data.os || 'Windows' }));
      }
    });

    newSocket.on('status', (data) => {
      setPcStatus(data);
    });

    newSocket.on('stats', (data) => {
      const pluginId = data.plugin_id || 'system_stats';
      pendingStats.current[pluginId] = data;
    });
    return () => newSocket.close();
  }, [serverIp, authToken, appLanguage]);

  const sendCommand = useCallback((pluginId, action, target) => {
    const finalTarget = target || activeTargets[pluginId] || 'pc';
    if (socket && connected) {
      const now = Date.now();
      console.log(`[SOCKET] EMIT command '${action}' to ${pluginId} at ${new Date(now).toISOString()}`);
      
      // Используем специализированные события для скорости
      const eventName = pluginId === 'yandex_station' || pluginId === 'pc_media' ? 'media_command' : 'plugin_command';
      socket.emit(eventName, { plugin_id: pluginId, action: action, target: finalTarget });
    }
  }, [socket, connected, activeTargets]);

  const setTarget = useCallback((pluginId, targetId) => {
    setActiveTargets(prev => ({ ...prev, [pluginId]: targetId }));
  }, []);

  const handleInteraction = useCallback((active) => {
    isInteractingRef.current = active;
    setUserInteracting(active);
  }, []);

  const submitPairingCode = () => {
    if (socket && pairingInput) {
      socket.emit('auth_attempt', { code: pairingInput });
    }
  };

  if (isPairing) {
    return (
      <PairingScreen 
        appLanguage={appLanguage}
        pairingInput={pairingInput}
        setPairingInput={setPairingInput}
        submitPairingCode={submitPairingCode}
        cancelPairing={() => { setIsPairing(false); setConnected(false); socket?.disconnect(); }}
      />
    );
  }

  if (!connected) {
    return (
      <LoginScreen 
        appLanguage={appLanguage}
        serverIp={serverIp}
        setServerIp={setServerIp}
        connectToServer={connectToServer}
        loading={loading}
        connectionStatus={connectionStatus}
      />
    );
  }

  return (
    <Dashboard 
      connected={connected}
      allStats={allStats}
      mediaStats={mediaStats}
      uiConfigs={uiConfigs}
      history={history}
      pcStatus={pcStatus}
      activeTargets={activeTargets}
      sendCommand={sendCommand}
      setTarget={setTarget}
      lastUpdate={lastUpdate}
      appLanguage={appLanguage}
      handleInteraction={handleInteraction}
    />
  );
}
