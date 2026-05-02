package com.monithome.ui
 
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import android.util.Base64
import com.monithome.data.PluginRepository
import kotlin.math.abs
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import com.monithome.models.LyricsData
import com.monithome.models.LyricTiming
import kotlinx.coroutines.flow.map

@Composable
fun LyricsFullscreenView(pluginId: String, deviceId: String, onDismiss: () -> Unit) {
    // ОПТИМИЗАЦИЯ: Подписываемся только на нужный девайс, чтобы не перерисовывать экран при обновлении текстов в других комнатах
    val lyricsData by remember(deviceId) {
        PluginRepository.lyrics.map { it[deviceId] }
    }.collectAsState(initial = null)

    val pluginStats by PluginRepository.getPluginStats(pluginId).collectAsState()
    
    val stats = remember(pluginStats, deviceId) {
        if (pluginId == "yandex_station") {
            @Suppress("UNCHECKED_CAST")
            val devices = (pluginStats["devices"] as? List<Map<String, Any>>)
            devices?.find { it["id"] == deviceId } ?: emptyMap()
        } else {
            pluginStats
        }
    }
    
    val baseProgress = (stats["progress"] as? Number)?.toDouble() ?: 0.0
    val isPlaying = (stats["playing"] as? Boolean) ?: false
    val lastUpdate = (stats["local_last_update"] as? Number)?.toDouble() ?: (System.currentTimeMillis() / 1000.0)
    // Изолируем метаданные точечно, чтобы не триггерить перерисовку при изменении прогресса
    val trackTitle = remember(stats["title"]) { (stats["title"] as? String) ?: "..." }
    val trackArtist = remember(stats["artist"], stats["subtitle"]) { (stats["artist"] as? String) ?: (stats["subtitle"] as? String) ?: "—" }
    val coverData = remember(stats["cover"]) { stats["cover"] as? String }
    
    val currentLanguage by com.monithome.data.LanguageManager.currentLanguage.collectAsState()
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val data = lyricsData
        val lyricsText = data?.lyrics ?: ""
        val isLoading = lyricsText == "loading" || lyricsText == "Загрузка..." || lyricsText == "Loading..."
        val hasNoLyrics = data != null && !isLoading && 
                         (lyricsText.isEmpty() || lyricsText == "null") && 
                         data.timings.isNullOrEmpty()

        // 1. ФОН
        LyricsBackground(coverData = coverData, blurEnabled = !hasNoLyrics)

        // 2. ЗАТЕМНЕНИЕ
        val overlayAlpha = remember(hasNoLyrics, isLoading) {
            if (hasNoLyrics) 0.0f else if (isLoading) 0.4f else 0.6f
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = overlayAlpha)))

        // 3. КОНТЕНТ
        if (data == null || isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF38BDF8))
            }
        } else if (!hasNoLyrics) {
            LyricsListContent(
                lyricsData = data,
                baseProgress = baseProgress,
                isPlaying = isPlaying,
                lastUpdate = lastUpdate
            )
        }

        // 4. ШАПКА
        LyricsHeader(
            title = trackTitle, 
            artist = trackArtist, 
            hasNoLyrics = hasNoLyrics, 
            currentLanguageCode = currentLanguage.code,
            onDismiss = onDismiss
        )
    }
}

@Composable
fun LyricsListContent(
    lyricsData: LyricsData,
    baseProgress: Double,
    isPlaying: Boolean,
    lastUpdate: Double
) {
    val timingsStable = remember(lyricsData.trackId) { lyricsData.timings ?: emptyList() }
    val listState = rememberLazyListState()
    
    // Рассчитываем текущий индекс (ОПТИМИЗАЦИЯ: 10 FPS вместо 60 FPS)
    val currentIndex by produceState(initialValue = -1, timingsStable, isPlaying, baseProgress, lastUpdate) {
        val lastUpdateMs = (lastUpdate * 1000).toLong()
        while (true) {
            val now = System.currentTimeMillis()
            val elapsed = if (isPlaying) (now - lastUpdateMs).coerceAtLeast(0) else 0
            val currentPos = (baseProgress * 1000).toLong() + elapsed
            val newIndex = timingsStable.indexOfLast { it.time != null && it.time <= currentPos }
            if (newIndex != value) value = newIndex
            kotlinx.coroutines.delay(100)
        }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val centerOffsetPx = remember(configuration, density) {
        val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
        // Ровно середина экрана
        (screenHeightPx * 0.5f).toInt()
    }

    // Прокрутка к текущей строке (с учетом верхнего Spacer на индексе 0)
    var lastScrolledIndex by remember { mutableStateOf(-1) }
    
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex != lastScrolledIndex) {
            val diff = currentIndex - lastScrolledIndex
            val targetIndex = currentIndex + 1
            
            if (diff == 1) {
                // Плавная прокрутка при обычном воспроизведении
                listState.animateScrollToItem(
                    index = targetIndex,
                    scrollOffset = -centerOffsetPx
                )
            } else {
                // Мгновенный прыжок при перемотке или в начале
                listState.scrollToItem(
                    index = targetIndex,
                    scrollOffset = -centerOffsetPx
                )
            }
            lastScrolledIndex = currentIndex
        }
    }

    LaunchedEffect(lyricsData?.trackId) {
        if (lyricsData?.trackId != null) {
            listState.scrollToItem(0)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (timingsStable.isEmpty()) {
            Text(
                lyricsData.lyrics ?: "", 
                color = Color.White, 
                fontSize = 22.sp, 
                lineHeight = 34.sp, 
                textAlign = TextAlign.Center, 
                modifier = Modifier.fillMaxWidth().padding(top = 160.dp, start = 32.dp, end = 32.dp)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().graphicsLayer(),
            ) {
                // ПУСТОЕ МЕСТО В НАЧАЛЕ (для центрирования первой строки)
                item { Spacer(modifier = Modifier.height(300.dp)) }
                
                itemsIndexed(timingsStable) { index, timing ->
                    LyricsLine(
                        text = timing.text ?: "",
                        isCurrent = index == currentIndex
                    )
                }
                
                // ПУСТОЕ МЕСТО В КОНЦЕ
                item { Spacer(modifier = Modifier.height(500.dp)) }
            }
            
            // Статичные градиенты (без пересчета размера каждый раз)
            val topGradient = remember { Brush.verticalGradient(listOf(Color.Black, Color.Transparent)) }
            val bottomGradient = remember { Brush.verticalGradient(listOf(Color.Transparent, Color.Black)) }
            
            Box(modifier = Modifier.fillMaxWidth().height(150.dp).align(Alignment.TopCenter)
                .graphicsLayer()
                .drawWithCache {
                    onDrawWithContent {
                        drawRect(topGradient)
                    }
                }
            )
            Box(modifier = Modifier.fillMaxWidth().height(150.dp).align(Alignment.BottomCenter)
                .graphicsLayer()
                .drawWithCache {
                    onDrawWithContent {
                        drawRect(bottomGradient)
                    }
                }
            )
        }
    }
}

@Composable
fun LyricsLine(
    text: String,
    isCurrent: Boolean
) {
    val alpha by animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0.35f,
        animationSpec = tween(400),
        label = "alpha"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (isCurrent) 1.12f else 1.0f,
        animationSpec = tween(400),
        label = "scale"
    )

    Text(
        text = text,
        color = Color.White,
        fontSize = 24.sp,
        // Использование одинаковой жирности предотвращает "скачки" верстки при смене строки
        fontWeight = FontWeight.Bold, 
        lineHeight = 38.sp,
        modifier = Modifier
            .padding(vertical = 12.dp, horizontal = 36.dp)
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
        textAlign = TextAlign.Center
    )
}

@Composable
fun LyricsBackground(coverData: String?, blurEnabled: Boolean) {
    if (coverData.isNullOrEmpty()) return
    val context = LocalContext.current
    val model = remember(coverData) {
        if (coverData.startsWith("http")) coverData 
        else if (coverData.startsWith("//")) "https:$coverData"
        else {
            try {
                val cleanBase64 = if (coverData.contains(",")) coverData.substringAfter(",") else coverData
                Base64.decode(cleanBase64, Base64.DEFAULT)
            } catch (e: Exception) { null }
        }
    }

    if (model != null) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(model)
                .crossfade(true)
                .allowHardware(true)
                .build(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurEnabled) Modifier.blur(25.dp) else Modifier),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
fun LyricsHeader(title: String, artist: String, hasNoLyrics: Boolean, currentLanguageCode: String, onDismiss: () -> Unit) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                if (artist.isNotEmpty()) {
                    Text(artist, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp, maxLines = 1)
                }
                if (hasNoLyrics) {
                    Text(
                        text = if (currentLanguageCode == "ru") "Текст недоступен" else "Lyrics not available",
                        color = Color(0xFF38BDF8),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
            }
        }
    }
}
