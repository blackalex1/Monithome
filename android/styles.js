import { StyleSheet, Dimensions } from 'react-native';
import { Theme } from './Theme';

const { width } = Dimensions.get('window');

export const styles = StyleSheet.create({
  container: { 
    flex: 1, 
    backgroundColor: Theme.colors.background, 
    padding: Theme.spacing.md 
  },
  header: { 
    flexDirection: 'row', 
    justifyContent: 'space-between', 
    alignItems: 'center',
    marginBottom: Theme.spacing.xl, 
    marginTop: 40,
    paddingHorizontal: Theme.spacing.sm
  },
  hostname: { 
    color: Theme.colors.textPrimary, 
    fontSize: 28, 
    fontWeight: '900',
    letterSpacing: -0.5
  },
  osText: { 
    color: Theme.colors.textSecondary, 
    fontSize: 13, 
    fontWeight: '600',
    marginTop: 2
  },
  statusBadge: { 
    paddingHorizontal: 10, 
    paddingVertical: 4, 
    borderRadius: Theme.radius.small, 
    backgroundColor: 'rgba(34, 197, 94, 0.15)',
    borderWidth: 1,
    borderColor: 'rgba(34, 197, 94, 0.2)'
  },
  statusText: { 
    color: Theme.colors.successText, 
    fontSize: 11, 
    fontWeight: '800',
    textTransform: 'uppercase',
    letterSpacing: 1
  },
  mainLayout: { gap: Theme.spacing.lg },
  pluginTitle: { 
    color: Theme.colors.accent, 
    fontSize: 11, 
    fontWeight: '900', 
    letterSpacing: 2, 
    marginBottom: Theme.spacing.md, 
    textTransform: 'uppercase',
    opacity: 0.8
  },
  glassCard: { 
    backgroundColor: Theme.colors.surface, 
    borderRadius: Theme.radius.card, 
    padding: Theme.spacing.lg, 
    borderWidth: 1, 
    borderColor: Theme.colors.border,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 20 },
    shadowOpacity: 0.4,
    shadowRadius: 40,
    elevation: 10,
    marginBottom: Theme.spacing.md,
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
    marginTop: Theme.spacing.sm,
    letterSpacing: -1
  },
  sectionTitle: { 
    color: Theme.colors.textMuted, 
    fontSize: 11, 
    fontWeight: '800', 
    marginBottom: 18, 
    textTransform: 'uppercase',
    letterSpacing: 1.5
  },
  grid: { width: '100%' },
  row: { flexDirection: 'row', alignItems: 'center' },
  rowLayout: { flexDirection: 'row', gap: 10, width: '100%', marginBottom: Theme.spacing.md },
  actionBtn: { 
    flex: 1,
    flexDirection: 'column', 
    alignItems: 'center', 
    backgroundColor: Theme.colors.white04, 
    paddingVertical: 14, 
    borderRadius: Theme.radius.button,
    borderWidth: 1,
    borderColor: Theme.colors.white06
  },
  actionBtnText: { 
    color: Theme.colors.textPrimary, 
    fontSize: 10, 
    fontWeight: '800', 
    marginTop: Theme.spacing.sm,
    textTransform: 'uppercase',
    letterSpacing: 0.5
  },
  
  // Media Center Styles
  miniLabel: { 
    color: Theme.colors.textSecondary, 
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
    color: Theme.colors.accent, 
    fontSize: 15, 
    fontWeight: '600',
    marginBottom: Theme.spacing.lg,
    opacity: 0.9
  },
  miniBtn: { 
    width: 44,
    height: 44,
    borderRadius: 22, 
    backgroundColor: Theme.colors.white06,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: Theme.colors.white05
  },
  playBtn: {
    width: 52,
    height: 52,
    borderRadius: 26,
    backgroundColor: Theme.colors.accent,
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: Theme.colors.accent,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 8,
    elevation: 5
  },

  albumArt: { 
    width: 110, 
    height: 110, 
    borderRadius: 20, 
    backgroundColor: Theme.colors.white04,
    borderWidth: 1,
    borderColor: Theme.colors.white05
  },
  mediaHeader: {
    flexDirection: 'row',
    gap: Theme.spacing.md,
    marginBottom: Theme.spacing.lg
  },

  volumeSliderContainer: { 
    marginTop: 30, 
    height: 40, 
    justifyContent: 'center' 
  },
  volumeSliderTrack: { 
    height: 8, 
    backgroundColor: Theme.colors.white08, 
    borderRadius: 4, 
    position: 'relative',
    overflow: 'hidden'
  },
  volumeSliderFill: { 
    height: '100%', 
    backgroundColor: Theme.colors.accent, 
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
  
  voiceInputContainer: { 
    flexDirection: 'row', 
    alignItems: 'center', 
    backgroundColor: Theme.colors.surfaceDark, 
    borderRadius: 20, 
    paddingHorizontal: Theme.spacing.md,
    marginTop: 15,
    borderWidth: 1,
    borderColor: Theme.colors.white08
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
    paddingHorizontal: Theme.spacing.md,
    paddingVertical: Theme.spacing.sm,
    borderRadius: Theme.radius.pill,
    backgroundColor: Theme.colors.white05,
    marginRight: Theme.spacing.sm
  },
  sourceTabActive: {
    backgroundColor: Theme.colors.accent,
  },
  sourceTabText: {
    color: Theme.colors.textMuted,
    fontSize: 12,
    fontWeight: '700'
  },
  sourceTabTextActive: {
    color: Theme.colors.accentDark
  },
  subTitleText: {
    color: Theme.colors.textSecondary,
    fontSize: 10,
    fontWeight: '700',
    marginLeft: 12,
    marginTop: 2
  },
  secondaryStatText: {
    color: Theme.colors.accent,
    fontSize: 16,
    fontWeight: '800'
  },
  chartValueText: {
    color: Theme.colors.accent,
    fontSize: 18,
    fontWeight: '900'
  },
  
  // Lyrics Styles
  lyricsBadge: {
    paddingHorizontal: 12,
    paddingVertical: 5,
    borderRadius: 6,
    backgroundColor: 'rgba(56, 189, 248, 0.05)',
    borderWidth: 0.8,
    borderColor: 'rgba(56, 189, 248, 0.5)',
    marginTop: 8,
    alignSelf: 'flex-start'
  },
  lyricsBadgeText: {
    color: '#38bdf8',
    fontSize: 10,
    fontWeight: '900',
    letterSpacing: 1,
    textTransform: 'uppercase'
  },
  lyricsModal: {
    flex: 1,
    backgroundColor: '#020617',
    padding: Theme.spacing.lg,
    paddingTop: 60
  },
  lyricsHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: Theme.spacing.xl
  },
  lyricsTrackTitle: {
    color: '#fff',
    fontSize: 24,
    fontWeight: '900',
    letterSpacing: -0.5
  },
  lyricsTrackArtist: {
    color: Theme.colors.accent,
    fontSize: 16,
    fontWeight: '600',
    marginTop: 4
  },
  lyricsLineContainer: {
    paddingVertical: 20,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255,255,255,0.03)'
  },
  lyricsLine: {
    color: 'rgba(255,255,255,0.15)',
    fontSize: 20,
    fontWeight: '700',
    lineHeight: 28
  },
  lyricsLineActive: {
    color: Theme.colors.accent,
    fontSize: 24,
    fontWeight: '900'
  }
});
