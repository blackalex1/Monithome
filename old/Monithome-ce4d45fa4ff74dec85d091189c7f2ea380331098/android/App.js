import React, { useState, useEffect, useCallback, useRef } from 'react';
import { InteractionManager, Alert } from 'react-native';
import io from 'socket.io-client';
import { decode } from "@msgpack/msgpack";
import AsyncStorage from '@react-native-async-storage/async-storage';
import { PairingScreen, LoginScreen } from './AuthScreens';
import Dashboard from './Dashboard';
import { GlobalStore, usePluginStats } from './store';

export default function App() {
  const [socket, setSocket] = useState(null);
  const [serverIp, setServerIp] = useState('');
  const [authToken, setAuthToken] = useState(null);
  const [connected, setConnected] = useState(false);
  const [loading, setLoading] = useState(false);
  const [connectionStatus, setConnectionStatus] = useState('Ожидание IP');
  const [uiConfigs, setUiConfigs] = useState([]);
  const [appLanguage, setAppLanguage] = useState('ru');
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
      const updates = { ...pendingStats.current };
      const hasStats = Object.keys(updates).length > 0;
      if (!hasStats) return;

      pendingStats.current = {};

      InteractionManager.runAfterInteractions(() => {
        const sTime = updates["_server_time"] || (Date.now() / 1000);
        GlobalStore.serverTime = sTime;

        // Массовое обновление хранилища (включая историю внутри GlobalStore)
        GlobalStore.bulkUpdate(updates);
      });
    }, 400); 
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
      let payload = data;
      // Если пришли бинарные данные (ArrayBuffer или Uint8Array)
      if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) {
        try {
          payload = decode(data);
        } catch (e) {
          console.error("[MSGPACK] Decode error:", e);
          return;
        }
      }
      
      const statsData = payload.stats || payload;
      const serverTime = payload._server_time || statsData._server_time || (Date.now() / 1000);
      const PRIORITY_PLUGINS = ['yandex_station', 'pc_media', 'yandex_lyrics'];

      const priorityUpdates = {};
      let hasPriority = false;

      if (statsData.plugin_id) {
        if (PRIORITY_PLUGINS.includes(statsData.plugin_id)) {
          priorityUpdates[statsData.plugin_id] = statsData;
          hasPriority = true;
        } else {
          pendingStats.current[statsData.plugin_id] = statsData;
        }
      } else {
        Object.keys(statsData).forEach(key => {
          if (key === "_server_time") return;
          if (PRIORITY_PLUGINS.includes(key)) {
            priorityUpdates[key] = statsData[key];
            hasPriority = true;
          } else {
            pendingStats.current[key] = statsData[key];
          }
        });
      }

      if (hasPriority) {
        GlobalStore.bulkUpdate(priorityUpdates);
      }

      pendingStats.current["_server_time"] = serverTime;
      setLoading(false);
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
      uiConfigs={uiConfigs}
      pcStatus={pcStatus}
      activeTargets={activeTargets}
      sendCommand={sendCommand}
      setTarget={setTarget}
      lastUpdate={lastUpdate}
      appLanguage={appLanguage}
      handleInteraction={handleInteraction}
      serverIp={serverIp}
    />
  );
}
