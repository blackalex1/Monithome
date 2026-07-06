package com.monithome.presentation.components.media.parts

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.domain.usecase.ObserveMediaProgressUseCase
import org.koin.compose.koinInject

@Composable
fun SmoothProgressBar(
    baseProgress: Double,
    duration: Double,
    lastUpdateUnixTime: Double,
    isPlaying: Boolean,
    onSeek: (Double) -> Unit
) {
    val useCase: ObserveMediaProgressUseCase = koinInject()
    val progressRatio by useCase(baseProgress, duration, lastUpdateUnixTime, isPlaying)
        .collectAsState(initial = if (duration > 0) (baseProgress / duration).toFloat() else 0f)

    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val activeProgress = if (isDragging) dragProgress else progressRatio

    Column {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .pointerInput(duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isDragging = true
                        dragProgress = (down.position.x / size.width).coerceIn(0f, 1f)
                        
                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                            if (change == null || !change.pressed) {
                                break
                            }
                            change.consume()
                            dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                        
                        isDragging = false
                        onSeek(dragProgress.toDouble() * duration)
                    }
                }
        ) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            
            val primaryColor = MaterialTheme.colorScheme.primary
            val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            
            val density = LocalDensity.current
            val thumbRadius = with(density) { (if (isDragging) 8.dp else 6.dp).toPx() }
            val trackHeight = with(density) { 4.dp.toPx() }
            
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerY = height / 2
                
                // 1. Draw inactive track (grey line)
                drawLine(
                    color = outlineColor,
                    start = androidx.compose.ui.geometry.Offset(0f, centerY),
                    end = androidx.compose.ui.geometry.Offset(width, centerY),
                    strokeWidth = trackHeight,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                
                // 2. Draw active track (colored progress line)
                val activeX = activeProgress * width
                if (activeX > 0f) {
                    drawLine(
                        color = primaryColor,
                        start = androidx.compose.ui.geometry.Offset(0f, centerY),
                        end = androidx.compose.ui.geometry.Offset(activeX, centerY),
                        strokeWidth = trackHeight,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
                
                // 3. Draw thumb (white circle with colored border outline)
                drawCircle(
                    color = Color.White,
                    radius = thumbRadius,
                    center = androidx.compose.ui.geometry.Offset(activeX, centerY)
                )
                drawCircle(
                    color = primaryColor,
                    radius = thumbRadius - 1.5f.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(activeX, centerY),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 3.dp.toPx()
                    )
                )
            }
        }
        
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val currentSeconds = (activeProgress.toDouble() * duration).toLong()
            Text(formatTime(currentSeconds.toDouble()), color = Color.Gray, fontSize = 12.sp)
            Text(formatTime(duration), color = Color.Gray, fontSize = 12.sp)
        }
    }
}

fun formatTime(seconds: Double): String {
    if (seconds.isNaN()) return "00:00"
    val totalSeconds = seconds.toLong()
    val hours = totalSeconds / 3600
    val mins = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, mins, secs) else "%02d:%02d".format(mins, secs)
}
