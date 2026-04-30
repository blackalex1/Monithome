package com.monithome.ui

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
import androidx.compose.ui.draw.blur
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import android.util.Base64
import com.monithome.data.PluginRepository
import com.monithome.models.LyricTiming
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val viewHeight = constraints.maxHeight

            // Вычисляем индекс текущей строки
            val currentIndex by remember(interpolatedProgress, lyricsData) {
                derivedStateOf {
                    lyricsData?.timings?.indexOfLast { (it.time ?: 0L) <= interpolatedProgress * 1000 } ?: -1
                }
            }

            // Авто-скролл: прокручиваем так, чтобы активная строка была в центре
            LaunchedEffect(currentIndex) {
                if (currentIndex >= 0) {
                    // Мы прокручиваем к самому currentIndex (который идет после Spacer-а)
                    // с нулевым смещением. Но так как у нас есть Spacer сверху,
                    // нам нужно прокрутить к элементу так, чтобы он был по центру.
                    // Самый простой способ: прокрутить к currentIndex и дать смещение - (viewHeight/2)
                    listState.animateScrollToItem(currentIndex + 1, scrollOffset = -(viewHeight / 2) + 40)
                }
            }

            val coverData = stats["cover"] as? String
            val hasNoLyrics = lyricsData != null && 
                             (lyricsData.lyrics.isNullOrEmpty() || lyricsData.lyrics == "null") && 
                             lyricsData.timings.isNullOrEmpty()
            
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                // Фоновая обложка
                if (!coverData.isNullOrEmpty()) {
                    val model: Any = if (coverData.startsWith("http")) coverData else {
                        try { Base64.decode(coverData, Base64.DEFAULT) } catch (e: Exception) { coverData }
                    }

                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().blur(if (hasNoLyrics) 0.dp else 60.dp, edgeTreatment = androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded),
                        contentScale = ContentScale.Crop,
                        alpha = if (hasNoLyrics) 0.8f else 0.4f
                    )
                }

                // Слой затемнения (прозрачный, если текста нет)
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (hasNoLyrics) 0.1f else 0.8f)))

                // Список на весь экран (под шапкой)
                if (lyricsData == null) {
                    // Состояние первичной загрузки (оставляем индикатор)
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF38BDF8))
                    }
                } else if (hasNoLyrics) {
                    // Просто пустой экран с обложкой (надпись удалена по просьбе)
                } else if (lyricsData.timings.isNullOrEmpty()) {
                    // Только статический текст
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 100.dp, start = 24.dp, end = 24.dp)) {
                        item {
                            Text(lyricsData.lyrics ?: "Текст отсутствует", color = Color.White, fontSize = 18.sp, lineHeight = 28.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val halfHeightDp = with(density) { (viewHeight / 2).toDp() }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Распорка сверху
                        item { Spacer(modifier = Modifier.height(halfHeightDp)) }

                        itemsIndexed(lyricsData.timings ?: emptyList()) { index, timing ->
                            val isCurrent = (timing.time ?: 0L) <= interpolatedProgress * 1000 &&
                                    (index == (lyricsData?.timings?.size ?: 0) - 1 || 
                                     (lyricsData?.timings?.get(index + 1)?.time ?: 0L) > interpolatedProgress * 1000)
                            
                            LyricLine(timing.text ?: "", isCurrent)
                        }

                        // Распорка снизу
                        item { Spacer(modifier = Modifier.height(halfHeightDp)) }
                    }
                }

                // Шапка ПОВЕРХ списка
                val trackTitle = (stats["title"] as? String) ?: "Неизвестно"
                val trackArtist = (stats["artist"] as? String) ?: (stats["subtitle"] as? String) ?: "Неизвестный исполнитель"

                Surface(
                    color = Color.Transparent, // Прозрачная плашка
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
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape) // Крестик в кружке
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
fun LyricLine(text: String, isCurrent: Boolean) {
    Text(
        text = text,
        color = if (isCurrent) Color.White else Color.Gray.copy(alpha = 0.5f),
        fontSize = if (isCurrent) 28.sp else 22.sp,
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        lineHeight = 34.sp,
        modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}
