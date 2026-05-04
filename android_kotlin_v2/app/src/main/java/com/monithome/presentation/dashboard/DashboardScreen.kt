package com.monithome.presentation.dashboard

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.monithome.presentation.components.lyrics.LyricsWidget
import com.monithome.presentation.components.stats.StatsChartsWidget
import com.monithome.presentation.dashboard.components.*
import org.koin.androidx.compose.koinViewModel
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val density = LocalDensity.current

    LaunchedEffect(Unit) {
        viewModel.processIntent(DashboardIntent.Connect())
    }

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    Scaffold(
        containerColor = Color.Black
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Панель выбора цвета (появляется при перетаскивании)
                AnimatedVisibility(
                    visible = state.isReordering,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    ThemeColorPicker(
                        state = state,
                        selectedColor = state.themeColor,
                        onColorSelect = { viewModel.processIntent(DashboardIntent.ChangeThemeColor(it)) },
                        onClose = { viewModel.processIntent(DashboardIntent.StopReordering) },
                        suggestedColor = state.serverSuggestedColor
                    )
                }

                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    if (!state.isConnected) {
                        ConnectionScreen(state, viewModel)
                    } else {
                        val gridState = rememberLazyStaggeredGridState()
                        // Фильтруем только активные виджеты для отображения
                        val visibleWidgets = remember(state.widgetOrder, state.activePlugins, state.mediaState.isLyricsActive) {
                            state.widgetOrder.filter { id ->
                                when (id) {
                                    "media" -> state.activePlugins.any { it.type == "media_source" && it.active }
                                    "pc_system" -> state.activePlugins.any { it.id == "pc_system" && it.active }
                                    "yandex_lyrics" -> state.mediaState.isLyricsActive
                                    "system_stats" -> state.activePlugins.any { it.id == "system_stats" && it.active }
                                    "pc_disks" -> state.activePlugins.any { it.id == "pc_disks" && it.active }
                                    else -> state.activePlugins.any { it.id == id && it.active }
                                }
                            }
                        }

                        LazyVerticalStaggeredGrid(
                            state = gridState,
                            columns = StaggeredGridCells.Adaptive(minSize = 340.dp),
                            contentPadding = PaddingValues(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalItemSpacing = 16.dp,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(visibleWidgets.size, key = { index -> visibleWidgets[index] }) { index ->
                                val widgetId = visibleWidgets[index]
                                var offsetX by remember { mutableStateOf(0f) }
                                var offsetY by remember { mutableStateOf(0f) }
                                var isDragging by remember { mutableStateOf(false) }

                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                                        .graphicsLayer {
                                            shadowElevation = if (isDragging) density.run { 8.dp.toPx() } else 0f
                                            scaleX = if (isDragging) 1.05f else 1.0f
                                            scaleY = if (isDragging) 1.05f else 1.0f
                                            alpha = if (isDragging) 0.8f else 1.0f
                                        }
                                        .pointerInput(Unit) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { 
                                                    isDragging = true
                                                    viewModel.processIntent(DashboardIntent.StartReordering)
                                                },
                                                onDragEnd = {
                                                    isDragging = false
                                                    offsetX = 0f
                                                    offsetY = 0f
                                                },
                                                onDragCancel = {
                                                    isDragging = false
                                                    offsetX = 0f
                                                    offsetY = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    offsetX += dragAmount.x
                                                    offsetY += dragAmount.y
                                                    
                                                    val layoutInfo = gridState.layoutInfo
                                                    val currentItemInfo = layoutInfo.visibleItemsInfo.find { it.key == widgetId }
                                                    if (currentItemInfo != null) {
                                                        val centerX = currentItemInfo.offset.x + currentItemInfo.size.width / 2 + offsetX
                                                        val centerY = currentItemInfo.offset.y + currentItemInfo.size.height / 2 + offsetY
                                                        
                                                        val targetItem = layoutInfo.visibleItemsInfo.find { target ->
                                                            target.key != widgetId &&
                                                            centerX > target.offset.x && centerX < (target.offset.x + target.size.width) &&
                                                            centerY > target.offset.y && centerY < (target.offset.y + target.size.height)
                                                        }
                                                        
                                                        if (targetItem != null) {
                                                            val targetIndex = visibleWidgets.indexOf(targetItem.key)
                                                            if (targetIndex != -1) {
                                                                viewModel.processIntent(DashboardIntent.MoveWidget(index, targetIndex))
                                                                offsetX = 0f
                                                                offsetY = 0f
                                                            }
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                ) {
                                    WidgetContent(widgetId, state, viewModel)
                                }
                            }
                        }
                    }
                }
            }

            // ПОЛНОЭКРАННЫЙ ТЕКСТ
            AnimatedVisibility(
                visible = state.isLyricsFullScreen,
                enter = fadeIn() + expandIn(),
                exit = fadeOut() + shrinkOut()
            ) {
                LyricsWidget(
                    lyrics = state.lyrics,
                    baseProgressMs = (state.mediaState.baseProgress * 1000).toLong(),
                    lastUpdateUnixTime = state.mediaState.lastUpdateUnixTime,
                    isPlaying = state.mediaState.isPlaying,
                    isFullScreen = true,
                    coverUrl = state.mediaState.coverUrl,
                    onClick = { viewModel.processIntent(DashboardIntent.ToggleLyricsFullScreen) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // ГРАФИКИ СТАТИСТИКИ
            AnimatedVisibility(
                visible = state.isStatsExpanded,
                enter = fadeIn() + expandIn(),
                exit = fadeOut() + shrinkOut()
            ) {
                val systemStats = state.stats["system_stats"] ?: emptyMap()
                StatsChartsWidget(
                    cpuHistory = state.cpuHistory,
                    cpuTempHistory = state.cpuTempHistory,
                    gpuHistory = state.gpuLoadHistory,
                    gpuTempHistory = state.gpuTempHistory,
                    diskTemps = (systemStats["disk_temps"] as? List<Map<String, Any>>) ?: emptyList(),
                    currentStats = systemStats,
                    translations = state.translations,
                    onClose = { viewModel.processIntent(DashboardIntent.ToggleStatsExpanded) },
                    modifier = Modifier.fillMaxSize().wrapContentHeight(Alignment.CenterVertically)
                )
            }
        }
        
        // Диалог авторизации (поверх всего)
        if (state.isAuthRequired) {
            AuthDialog(state, viewModel)
        }


    }

    androidx.activity.compose.BackHandler(enabled = state.isLyricsFullScreen || state.isStatsExpanded) {
        if (state.isLyricsFullScreen) viewModel.processIntent(DashboardIntent.ToggleLyricsFullScreen)
        if (state.isStatsExpanded) viewModel.processIntent(DashboardIntent.ToggleStatsExpanded)
    }
}
