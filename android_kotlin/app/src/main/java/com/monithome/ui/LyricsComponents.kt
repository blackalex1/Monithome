package com.monithome.ui
 
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import coil.compose.AsyncImage
import android.util.Base64
import com.monithome.data.PluginRepository
import com.monithome.models.LyricTiming
import kotlin.math.abs

@Composable
fun LyricsDialog(deviceId: String, stats: Map<String, Any>, onDismiss: () -> Unit) {
    val allLyrics by PluginRepository.lyrics.collectAsState()
    val lyricsData = allLyrics[deviceId]
    
    // Параметры для интерполяции
    val baseProgress = (stats["progress"] as? Number)?.toDouble() ?: 0.0
    val isPlaying = (stats["playing"] as? Boolean) ?: false
    val lastUpdate = (stats["local_last_update"] as? Number)?.toDouble() ?: (System.currentTimeMillis() / 1000.0)
    
    var interpolatedProgress by remember { mutableDoubleStateOf(baseProgress) }
    
    LaunchedEffect(baseProgress, isPlaying, lastUpdate) {
        if (!isPlaying) {
            interpolatedProgress = baseProgress
            return@LaunchedEffect
        }
        while (true) {
            val now = System.currentTimeMillis() / 1000.0
            interpolatedProgress = baseProgress + (now - lastUpdate)
            kotlinx.coroutines.android.awaitFrame()
        }
    }
    
    val listState = rememberLazyListState()
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val viewHeight = constraints.maxHeight

            val currentIndex by remember(lyricsData) {
                derivedStateOf {
                    lyricsData?.timings?.indexOfLast { (it.time ?: 0L) <= interpolatedProgress * 1000 } ?: -1
                }
            }

            val trackTitle = (stats["title"] as? String) ?: "Неизвестно"
            
            LaunchedEffect(trackTitle) {
                listState.scrollToItem(0)
            }

            LaunchedEffect(currentIndex) {
                if (currentIndex >= 0) {
                    listState.animateScrollToItem(
                        index = currentIndex + 1, 
                        scrollOffset = -(viewHeight / 2) + 40
                    )
                }
            }

            val coverData = stats["cover"] as? String
            val lyricsText = lyricsData?.lyrics ?: ""
            val isLoading = lyricsText == "loading" || lyricsText == "Загрузка..." || lyricsText == "Loading..."
            
            val hasNoLyrics = lyricsData != null && !isLoading && 
                             (lyricsText.isEmpty() || lyricsText == "null") && 
                             lyricsData.timings.isNullOrEmpty()
            
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                if (!coverData.isNullOrEmpty()) {
                    val model: Any = remember(coverData) {
                        if (coverData.startsWith("http")) coverData else {
                            try { Base64.decode(coverData, Base64.DEFAULT) } catch (e: Exception) { coverData }
                        }
                    }

                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().blur(if (hasNoLyrics) 0.dp else 6.dp),
                        contentScale = ContentScale.Crop,
                        alpha = if (hasNoLyrics) 1.0f else 0.4f
                    )
                }

                val overlayAlpha = remember(hasNoLyrics, isLoading) {
                    when {
                        hasNoLyrics -> 0.0f
                        isLoading -> 0.3f
                        else -> 0.8f
                    }
                }
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = overlayAlpha)))

                if (lyricsData == null || isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF38BDF8))
                    }
                } else if (hasNoLyrics) {
                    // Текст недоступен - просто обложка
                } else if (lyricsData.timings.isNullOrEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 100.dp, start = 24.dp, end = 24.dp)) {
                        item {
                            Text(lyricsData.lyrics ?: "", color = Color.White, fontSize = 18.sp, lineHeight = 28.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val halfHeightDp = with(density) { (viewHeight / 2).toDp() }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.1f to Color.Black,
                                    0.9f to Color.Black,
                                    1f to Color.Transparent
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        },
                    ) {
                        item { Spacer(modifier = Modifier.height(halfHeightDp)) }
                        itemsIndexed(lyricsData.timings ?: emptyList()) { index, timing ->
                            val distance = abs(index - currentIndex)
                            LyricLine(
                                text = timing.text ?: "", 
                                isCurrent = index == currentIndex, 
                                distance = distance
                            )
                        }
                        item { Spacer(modifier = Modifier.height(halfHeightDp)) }
                    }
                }

                // Шапка
                val trackArtist = (stats["artist"] as? String) ?: (stats["subtitle"] as? String) ?: "Неизвестный исполнитель"

                Surface(
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(trackTitle, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            if (trackArtist.isNotEmpty()) {
                                Text(trackArtist, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, maxLines = 1)
                            }
                            if (hasNoLyrics) {
                                Text(
                                    text = if (com.monithome.data.LanguageManager.currentLanguage.value.code == "ru") "Текст недоступен" else "Lyrics not available",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LyricLine(text: String, isCurrent: Boolean, distance: Int) {
    val targetAlpha = when {
        isCurrent -> 1f
        distance == 1 -> 0.5f
        distance == 2 -> 0.2f
        else -> 0.1f
    }

    val color by animateColorAsState(
        targetValue = Color.White.copy(alpha = targetAlpha),
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "color"
    )
    
    val fontSize by animateFloatAsState(
        targetValue = if (isCurrent) 32f else 22f, 
        animationSpec = tween(800, easing = LinearOutSlowInEasing),
        label = "size"
    )

    Text(
        text = text,
        color = color,
        fontSize = fontSize.sp,
        fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Medium,
        lineHeight = 40.sp,
        modifier = Modifier.padding(vertical = 16.dp, horizontal = 32.dp).fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}
