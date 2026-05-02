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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import android.util.Base64
import com.monithome.data.PluginRepository
import kotlin.math.abs
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest

@Composable
fun LyricsFullscreenView(pluginId: String, deviceId: String, onDismiss: () -> Unit) {
    val allLyrics by PluginRepository.lyrics.collectAsState()
    val lyricsData = allLyrics[deviceId]
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
    
    var interpolatedProgress by remember { mutableDoubleStateOf(baseProgress) }
    
    LaunchedEffect(baseProgress, isPlaying, lastUpdate) {
        if (!isPlaying) {
            interpolatedProgress = baseProgress
            return@LaunchedEffect
        }
        
        // Если пришло новое значение, которое СИЛЬНО отличается (больше 2 сек), прыгаем сразу (перемотка)
        // Иначе продолжаем плавное движение
        if (abs(baseProgress - interpolatedProgress) > 2.0) {
            interpolatedProgress = baseProgress
        }

        while (true) {
            val now = System.currentTimeMillis() / 1000.0
            val calculated = baseProgress + (now - lastUpdate)
            
            // Запрещаем прыжки назад меньше чем на 1 секунду (защита от сетевого джиттера)
            if (calculated > interpolatedProgress || abs(calculated - interpolatedProgress) > 1.0) {
                interpolatedProgress = calculated
            }
            
            kotlinx.coroutines.android.awaitFrame()
        }
    }
    
    val listState = rememberLazyListState()
    
    // Сброс прокрутки при смене трека
    LaunchedEffect(lyricsData?.trackId) {
        if (lyricsData?.trackId != null) {
            listState.scrollToItem(0)
        }
    }

    val currentLanguage by com.monithome.data.LanguageManager.currentLanguage.collectAsState()
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val viewHeight = this.constraints.maxHeight
        val density = androidx.compose.ui.platform.LocalDensity.current

        val currentIndex by remember(lyricsData) {
            derivedStateOf {
                lyricsData?.timings?.indexOfLast { (it.time ?: 0L) <= interpolatedProgress * 1000 } ?: -1
            }
        }

        val trackTitle = (stats["title"] as? String) ?: "Неизвестно"
        val coverData = stats["cover"] as? String
        val lyricsText = lyricsData?.lyrics ?: ""
        val isLoading = lyricsText == "loading" || lyricsText == "Загрузка..." || lyricsText == "Loading..."
        
        val hasNoLyrics = lyricsData != null && !isLoading && 
                         (lyricsText.isEmpty() || lyricsText == "null") && 
                         lyricsData.timings.isNullOrEmpty()
        
        // 1. ФОН (Вынесен в отдельный слой для оптимизации)
        LyricsBackground(coverData = coverData, blurEnabled = !hasNoLyrics)

        // 2. КОНТЕНТ
        Box(modifier = Modifier.fillMaxSize()) {
            val overlayAlpha = remember(hasNoLyrics, isLoading) {
                when {
                    hasNoLyrics -> 0.0f
                    isLoading -> 0.4f
                    else -> 0.55f
                }
            }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = overlayAlpha)))

            if (lyricsData == null || isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF38BDF8))
                }
            } else if (!hasNoLyrics) {
                if (lyricsData.timings.isNullOrEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 120.dp, start = 24.dp, end = 24.dp)) {
                        item {
                            Text(lyricsData.lyrics ?: "", color = Color.White, fontSize = 20.sp, lineHeight = 30.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    val halfHeightDp = with(density) { (viewHeight / 2).toDp() }

                    LaunchedEffect(currentIndex) {
                        if (currentIndex >= 0) {
                            listState.animateScrollToItem(
                                index = currentIndex + 1, 
                                scrollOffset = -(viewHeight / 2) + 40
                            )
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item { Spacer(modifier = Modifier.height(halfHeightDp)) }
                        itemsIndexed(lyricsData.timings, key = { i, t -> "$i-${t.time}" }) { index, timing ->
                            val isCurrent = index == currentIndex
                            val distance = abs(index - currentIndex)
                            
                            LyricLine(
                                text = timing.text ?: "", 
                                isCurrent = isCurrent, 
                                distance = distance
                            )
                        }
                        item { Spacer(modifier = Modifier.height(halfHeightDp)) }
                    }

                    // Градиенты затемнения
                    val gradientHeightDp = with(density) { (viewHeight * 0.35f).toDp() }
                    Box(modifier = Modifier.fillMaxWidth().height(gradientHeightDp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black, Color.Transparent))))
                    Box(modifier = Modifier.fillMaxWidth().height(gradientHeightDp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
                }
            }

            // 3. ШАПКА
            val trackArtist = (stats["artist"] as? String) ?: (stats["subtitle"] as? String) ?: "Неизвестный исполнитель"
            LyricsHeader(
                title = trackTitle, 
                artist = trackArtist, 
                hasNoLyrics = hasNoLyrics, 
                currentLanguageCode = currentLanguage.code,
                onDismiss = onDismiss
            )
        }
    }
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
                .allowHardware(false)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(if (blurEnabled) 20.dp else 0.dp),
            contentScale = ContentScale.Crop,
            alpha = if (blurEnabled) 0.7f else 1.0f
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

@Composable
fun LyricLine(text: String, isCurrent: Boolean, distance: Int) {
    val targetAlpha = when {
        isCurrent -> 1f
        distance == 1 -> 0.6f
        distance == 2 -> 0.3f
        else -> 0.15f
    }

    val color by animateColorAsState(
        targetValue = Color.White.copy(alpha = targetAlpha),
        animationSpec = tween(600, easing = LinearOutSlowInEasing),
        label = "color"
    )
    
    val fontSize by animateFloatAsState(
        targetValue = if (isCurrent) 34f else 22f, 
        animationSpec = tween(600, easing = LinearOutSlowInEasing),
        label = "size"
    )

    Text(
        text = text,
        color = color,
        fontSize = fontSize.sp,
        fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Bold,
        lineHeight = 44.sp,
        modifier = Modifier.padding(vertical = 14.dp, horizontal = 32.dp).fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}
