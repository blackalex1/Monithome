package com.monithome.presentation.components.lyrics

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.domain.models.LyricLine
import com.monithome.domain.usecase.SyncLyricsUseCase
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import org.koin.compose.koinInject

@Composable
fun LyricsWidget(
    lyrics: List<LyricLine>,
    currentTimeMs: Long,
    modifier: Modifier = Modifier,
    isFullScreen: Boolean = false,
    coverUrl: String = "",
    title: String = "",
    translations: Map<String, String> = emptyMap(),
    onClick: () -> Unit = {}
) {
    val syncUseCase: SyncLyricsUseCase = koinInject()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    var containerHeightPx by remember { mutableIntStateOf(0) }

    // O(log N) поиск текущей строки
    val activeIndex by remember(lyrics, currentTimeMs) {
        derivedStateOf { syncUseCase(lyrics, currentTimeMs) }
    }

    // Автоскролл
    LaunchedEffect(activeIndex, containerHeightPx) {
        if (activeIndex >= 0 && containerHeightPx > 0) {
            coroutineScope.launch {
                // Т.к. у нас уже есть contentPadding = высота/2, 
                // то scrollOffset = 0 поставит верх элемента на середину.
                // Смещение 60 пикселей (положительное) поднимет строку ВВЕРХ, чтобы её центр совпал с центром экрана.
                listState.animateScrollToItem(
                    index = activeIndex,
                    scrollOffset = 60 
                )
            }
        }
    }

    // Декодирование обложки для фона
    val context = LocalContext.current
    var decodedBackground by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(coverUrl) {
        if (coverUrl.isEmpty()) {
            decodedBackground = null
            return@LaunchedEffect
        }
        decodedBackground = withContext(Dispatchers.Default) {
            if (coverUrl.startsWith("http")) coverUrl
            else {
                try {
                    val clean = if (coverUrl.contains(",")) coverUrl.substringAfter(",") else coverUrl
                    android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                } catch (e: Exception) { null }
            }
        }
    }

    val shape = if (isFullScreen) RoundedCornerShape(0.dp) else MaterialTheme.shapes.large

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isFullScreen) Modifier.fillMaxHeight() else Modifier.height(400.dp))
            .background(
                color = if (isFullScreen) Color.Black else MaterialTheme.colorScheme.surface,
                shape = shape
            )
            .clip(shape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // ФОНОВАЯ ОБЛОЖКА
        if (decodedBackground != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(decodedBackground)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (isFullScreen) 0.4f else 0.3f }
            )
            // Затемняющий градиент поверх
            Box(modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = if (isFullScreen) {
                        listOf(Color.Black.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.9f))
                    } else {
                        listOf(Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.8f))
                    }
                )
            ))
        }

        // КОНТЕНТ (с отступами)
        Column(modifier = Modifier.fillMaxSize().padding(if (isFullScreen) 32.dp else 16.dp)) {
            if (!isFullScreen && title.isNotEmpty()) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp).fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
            }

            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                if (lyrics.isEmpty()) {
                // Если текста нет - показываем обложку в центре
                if (decodedBackground != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(decodedBackground)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(if (isFullScreen) 280.dp else 180.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF2C2C2C))
                        )
                        if (isFullScreen) {
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = translations["lyrics_not_found"] ?: "Текст не найден",
                                color = Color.Gray.copy(alpha = 0.5f),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        containerHeightPx = coordinates.size.height
                    },
                contentPadding = PaddingValues(
                    vertical = with(density) { (containerHeightPx / 2).toDp() }
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(
                    items = lyrics,
                    key = { _, item -> item.id }
                ) { index, line ->
                    val isActive = index == activeIndex
                    val isPast = index < activeIndex
                    
                    val alpha by animateFloatAsState(
                        targetValue = if (isActive) 1f else if (isPast) 0.3f else 0.5f,
                        animationSpec = tween(300),
                        label = "alpha"
                    )

                    val scale by animateFloatAsState(
                        targetValue = if (isActive) (if (isFullScreen) 1.2f else 1.1f) else 1.0f,
                        animationSpec = tween(300),
                        label = "scale"
                    )

                    Text(
                        text = line.text,
                        color = Color.White,
                        fontSize = if (isFullScreen) 32.sp else 17.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        lineHeight = if (isFullScreen) 40.sp else 24.sp,
                        softWrap = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = if (isFullScreen) 20.dp else 10.dp)
                            .padding(horizontal = if (isFullScreen) 32.dp else 24.dp)
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
}
}
