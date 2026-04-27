import { StyleSheet, Dimensions } from 'react-native';

const { width } = Dimensions.get('window');

export const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#020617', padding: 16 },
  header: { 
    flexDirection: 'row', 
    justifyContent: 'space-between', 
    alignItems: 'center',
    marginBottom: 32, 
    marginTop: 40,
    paddingHorizontal: 8
  },
  hostname: { 
    color: '#f8fafc', 
    fontSize: 28, 
    fontWeight: '900',
    letterSpacing: -0.5
  },
  osText: { 
    color: '#64748b', 
    fontSize: 13, 
    fontWeight: '600',
    marginTop: 2
  },
  statusBadge: { 
    paddingHorizontal: 10, 
    paddingVertical: 4, 
    borderRadius: 12, 
    backgroundColor: 'rgba(34, 197, 94, 0.15)',
    borderWidth: 1,
    borderColor: 'rgba(34, 197, 94, 0.2)'
  },
  statusText: { 
    color: '#4ade80', 
    fontSize: 11, 
    fontWeight: '800',
    textTransform: 'uppercase',
    letterSpacing: 1
  },
  mainLayout: { gap: 24 },
  pluginTitle: { 
    color: '#38bdf8', 
    fontSize: 11, 
    fontWeight: '900', 
    letterSpacing: 2, 
    marginBottom: 16, 
    textTransform: 'uppercase',
    opacity: 0.8
  },
  glassCard: { 
    backgroundColor: 'rgba(15, 23, 42, 0.6)', 
    borderRadius: 32, 
    padding: 24, 
    borderWidth: 1, 
    borderColor: 'rgba(255,255,255,0.08)',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 20 },
    shadowOpacity: 0.4,
    shadowRadius: 40,
    elevation: 10,
    marginBottom: 16,
    overflow: 'hidden'
  },
  cardTitle: { 
    color: '#f1f5f9', 
    fontSize: 17, 
    fontWeight: '700', 
    marginLeft: 12,
    letterSpacing: -0.2
  },
  statValueText: { 
    color: '#fff', 
    fontSize: 40, 
    fontWeight: '900', 
    marginTop: 12,
    letterSpacing: -1
  },
  sectionTitle: { 
    color: '#94a3b8', 
    fontSize: 11, 
    fontWeight: '800', 
    marginBottom: 18, 
    textTransform: 'uppercase',
    letterSpacing: 1.5
  },
  row: { flexDirection: 'row', alignItems: 'center' },
  rowLayout: { flexDirection: 'row', gap: 10 },
  actionBtn: { 
    flex: 1,
    flexDirection: 'column', 
    alignItems: 'center', 
    backgroundColor: 'rgba(255,255,255,0.04)', 
    paddingVertical: 14, 
    borderRadius: 22,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.06)'
  },
  actionBtnText: { 
    color: '#f8fafc', 
    fontSize: 10, 
    fontWeight: '800', 
    marginTop: 8,
    textTransform: 'uppercase',
    letterSpacing: 0.5
  },
  
  // Media Center Styles
  miniLabel: { 
    color: '#64748b', 
    fontSize: 10, 
    fontWeight: '900', 
    letterSpacing: 1.5,
    textTransform: 'uppercase'
  },
  mediaTitle: { 
    color: '#fff', 
    fontSize: 20, 
    fontWeight: '900', 
    marginTop: 12,
    letterSpacing: -0.5
  },
  mediaArtist: { 
    color: '#38bdf8', 
    fontSize: 15, 
    fontWeight: '600',
    marginBottom: 24,
    opacity: 0.9
  },
  mediaControls: { 
    flexDirection: 'column', 
    alignItems: 'center', 
    gap: 10,
    justifyContent: 'center'
  },
  mediaControlsVertical: {
    flexDirection: 'column',
    alignItems: 'center',
    gap: 12
  },
  miniBtn: { 
    width: 44,
    height: 44,
    borderRadius: 22, 
    backgroundColor: 'rgba(255,255,255,0.06)',
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)'
  },
  playBtn: {
    width: 52,
    height: 52,
    borderRadius: 26,
    backgroundColor: '#38bdf8',
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#38bdf8',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 8,
    elevation: 5
  },

  albumArt: { 
    width: 110, 
    height: 110, 
    borderRadius: 20, 
    backgroundColor: 'rgba(255,255,255,0.03)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)'
  },
  mediaHeader: {
    flexDirection: 'row',
    gap: 16,
    marginBottom: 24
  },
  mediaInfo: {
    flex: 1,
    justifyContent: 'center'
  },

  volumeSliderContainer: { 
    marginTop: 30, 
    height: 40, 
    justifyContent: 'center' 
  },
  volumeSliderTrack: { 
    height: 8, 
    backgroundColor: 'rgba(255,255,255,0.08)', 
    borderRadius: 4, 
    position: 'relative',
    overflow: 'hidden'
  },
  volumeSliderFill: { 
    height: '100%', 
    backgroundColor: '#38bdf8', 
    borderRadius: 4 
  },
  volumeSliderThumb: { 
    width: 20, 
    height: 20, 
    borderRadius: 10, 
    backgroundColor: '#fff', 
    position: 'absolute', 
    top: -6, 
    marginLeft: -10, 
    elevation: 5,
    shadowColor: '#000',
    shadowOpacity: 0.5,
    shadowRadius: 5
  },
  
  // Voice Input
  voiceInputContainer: { 
    flexDirection: 'row', 
    alignItems: 'center', 
    backgroundColor: 'rgba(15, 23, 42, 0.8)', 
    borderRadius: 20, 
    paddingHorizontal: 16,
    marginTop: 15,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)'
  },
  voiceInput: { 
    flex: 1, 
    color: '#fff', 
    paddingVertical: 14, 
    fontSize: 15,
    fontWeight: '500'
  },

  // Tabs & Sources
  sourceTab: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 12,
    backgroundColor: 'rgba(255,255,255,0.05)',
    marginRight: 8
  },
  sourceTabActive: {
    backgroundColor: '#38bdf8',
  },
  sourceTabText: {
    color: '#94a3b8',
    fontSize: 12,
    fontWeight: '700'
  },
  sourceTabTextActive: {
    color: '#0f172a'
  }
});
