package com.monithome.presentation.components.lyrics

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.domain.models.LyricLine
import com.monithome.domain.usecase.SyncLyricsUseCase
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun LyricsWidget(
    lyrics: List<LyricLine>,
    currentTimeMs: Long,
    modifier: Modifier = Modifier
) {
    if (lyrics.isEmpty()) return

    val syncUseCase: SyncLyricsUseCase = koinInject()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // O(log N) поиск текущей строки
    val activeIndex by remember(lyrics, currentTimeMs) {
        derivedStateOf { syncUseCase(lyrics, currentTimeMs) }
    }

    // Автоскролл
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            coroutineScope.launch {
                // Центрируем строку. (Примерный offset для центрирования можно задать)
                listState.animateScrollToItem(index = maxOf(0, activeIndex - 2))
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(400.dp) // Задаем фиксированную высоту или берем из Grid
            .background(Color(0xFF121212), RoundedCornerShape(24.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 150.dp), // Отступы сверху и снизу для прокрутки
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(
                items = lyrics,
                key = { _, item -> item.id }
            ) { index, line ->
                val isActive = index == activeIndex
                val isPast = index < activeIndex
                
                // Изолируем анимацию альфы, чтобы не вызывать рекомпозицию текста
                val alpha by animateFloatAsState(
                    targetValue = if (isActive) 1f else if (isPast) 0.3f else 0.5f,
                    animationSpec = tween(300),
                    label = "alpha"
                )

                val scale by animateFloatAsState(
                    targetValue = if (isActive) 1.1f else 1.0f,
                    animationSpec = tween(300),
                    label = "scale"
                )

                Text(
                    text = line.text,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .graphicsLayer {
                            this.alpha = alpha
                            this.scaleX = scale
                            this.scaleY = scale
                        }
                )
            }
        }
    }
}
