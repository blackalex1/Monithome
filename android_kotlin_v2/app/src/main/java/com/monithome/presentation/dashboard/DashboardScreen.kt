package com.monithome.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.domain.models.LyricLine
import com.monithome.presentation.components.lyrics.LyricsWidget
import com.monithome.presentation.components.media.MediaWidget
import org.koin.androidx.compose.koinViewModel

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.processIntent(DashboardIntent.Connect)
    }

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF4CAF50))
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        if (!state.isConnected) {
            Text(
                "Подключение к серверу...",
                color = Color.Gray,
                modifier = Modifier.align(Alignment.Center)
            )
            return@Box
        }

        // Адаптивная сетка: на телефонах (ширина < 600dp) будет 1 колонка, на планшетах - 2 колонки.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            
            // Медиа Виджет
            if (state.activePlugins.any { it.type == "media_source" && it.active }) {
                item(span = { GridItemSpan(1) }) {
                    MediaWidget(
                        state = state.mediaState,
                        onIntent = { viewModel.processIntent(it) }
                    )
                }
            }

            // Виджет Текстов песен (если включен)
            if (state.mediaState.isLyricsActive) {
                item(span = { GridItemSpan(1) }) {
                    // Пока передаем пустой список для макета. В будущем тексты придут из State.
                    val dummyLyrics = listOf(
                        LyricLine(0, "Music plays..."),
                        LyricLine(5000, "First line of the song")
                    )
                    LyricsWidget(
                        lyrics = dummyLyrics,
                        currentTimeMs = (state.mediaState.baseProgress * 1000).toLong()
                    )
                }
            }

            // Статистика ПК (заготовка)
            if (state.activePlugins.any { it.id == "pc_stats" && it.active }) {
                item(span = { GridItemSpan(1) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(Color(0xFF1E1E1E), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("PC Stats Widget", color = Color.Gray, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
