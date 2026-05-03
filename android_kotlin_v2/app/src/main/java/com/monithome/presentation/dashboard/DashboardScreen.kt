package com.monithome.presentation.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandIn
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.monithome.presentation.components.stats.StatWidget
import com.monithome.presentation.components.stats.DisksWidget
import org.koin.androidx.compose.koinViewModel

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.processIntent(DashboardIntent.Connect())
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
    ) {
        // ОСНОВНОЙ КОНТЕНТ (с отступами)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (!state.isConnected && !state.isAuthRequired) {
                val manualIp = remember { mutableStateOf("192.168.1.100") }
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (state.pcError != null) {
                        Text("Ошибка: ${state.pcError}", color = Color.Red, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text("Выберите сервер или введите IP:", color = Color.White, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    state.discoveredServers.forEach { server ->
                        Button(
                            onClick = { viewModel.processIntent(DashboardIntent.Connect(server.url)) },
                            modifier = Modifier.fillMaxWidth(0.7f).padding(vertical = 4.dp)
                        ) {
                            Text("${server.name} (${server.url})")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Ручной ввод:", color = Color.Gray)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = manualIp.value,
                            onValueChange = { manualIp.value = it },
                            modifier = Modifier.width(200.dp),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { viewModel.processIntent(DashboardIntent.Connect(manualIp.value)) }) {
                            Text("ОК")
                        }
                    }
                }
            } else if (state.isAuthRequired) {
                val password = remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Авторизация") },
                    text = {
                        Column {
                            Text("Введите код доступа для MonitHome:")
                            Spacer(modifier = Modifier.height(8.dp))
                            TextField(
                                value = password.value,
                                onValueChange = { password.value = it },
                                placeholder = { Text("Пароль") },
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = { viewModel.processIntent(DashboardIntent.Auth(password.value)) }) {
                            Text("Войти")
                        }
                    }
                )
            } else {
                // СЕТКА ВИДЖЕТОВ
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

                    // Виджет Текстов песен
                    if (state.mediaState.isLyricsActive) {
                        item(span = { GridItemSpan(1) }) {
                            LyricsWidget(
                                lyrics = state.lyrics,
                                currentTimeMs = (state.mediaState.currentProgress * 1000).toLong(),
                                coverUrl = state.mediaState.coverUrl,
                                onClick = { viewModel.processIntent(DashboardIntent.ToggleLyricsFullScreen) }
                            )
                        }
                    }

                    // Статистика ПК
                    if (state.activePlugins.any { it.id == "system_stats" && it.active }) {
                        item(span = { GridItemSpan(1) }) {
                            StatWidget(
                                title = "Производительность",
                                stats = state.stats["system_stats"] ?: emptyMap()
                            )
                        }
                    }

                    // Диски
                    if (state.activePlugins.any { it.id == "pc_disks" && it.active }) {
                        item(span = { GridItemSpan(1) }) {
                            @Suppress("UNCHECKED_CAST")
                            DisksWidget(
                                disks = (state.stats["pc_disks"]?.get("disks") as? List<Map<String, Any>>) ?: emptyList()
                            )
                        }
                    }
                }
            }
        }

        // Обработка кнопки Назад
        androidx.activity.compose.BackHandler(enabled = state.isLyricsFullScreen) {
            viewModel.processIntent(DashboardIntent.ToggleLyricsFullScreen)
        }

        // ПОЛНОЭКРАННЫЙ ТЕКСТ (теперь реально на весь экран, без отступов)
        AnimatedVisibility(
            visible = state.isLyricsFullScreen,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            LyricsWidget(
                lyrics = state.lyrics,
                currentTimeMs = (state.mediaState.currentProgress * 1000).toLong(),
                isFullScreen = true,
                coverUrl = state.mediaState.coverUrl,
                onClick = { viewModel.processIntent(DashboardIntent.ToggleLyricsFullScreen) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
