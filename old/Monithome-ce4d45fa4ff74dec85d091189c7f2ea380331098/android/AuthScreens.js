import React from 'react';
import { View, Text, StatusBar, Pressable, TextInput, ActivityIndicator, Dimensions, Linking } from 'react-native';
import { styles } from './styles';
import { Zap, IconMap } from './IconMap';

const { width } = Dimensions.get('window');

export const PairingScreen = ({ 
  appLanguage, 
  pairingInput, 
  setPairingInput, 
  submitPairingCode, 
  cancelPairing 
}) => (
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
      
      <Pressable onPress={cancelPairing} style={{ marginTop: 24, alignItems: 'center' }}>
        <Text style={{ color: '#ef4444', fontSize: 14, fontWeight: '700' }}>
          {appLanguage === 'ru' ? 'ОТМЕНА' : 'CANCEL'}
        </Text>
      </Pressable>
    </View>
  </View>
);

export const LoginScreen = ({ 
  appLanguage, 
  serverIp, 
  setServerIp, 
  connectToServer, 
  loading, 
  connectionStatus 
}) => (
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
