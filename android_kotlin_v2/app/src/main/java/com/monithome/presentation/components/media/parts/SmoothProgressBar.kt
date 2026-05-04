package com.monithome.presentation.components.media.parts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.domain.usecase.ObserveMediaProgressUseCase
import org.koin.compose.koinInject

@Composable
fun SmoothProgressBar(baseProgress: Double, duration: Double, lastUpdateUnixTime: Double, isPlaying: Boolean) {
    val useCase: ObserveMediaProgressUseCase = koinInject()
    val progressRatio by useCase(baseProgress, duration, lastUpdateUnixTime, isPlaying)
        .collectAsState(initial = if (duration > 0) (baseProgress / duration).toFloat() else 0f)

    Column {
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.outline)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progressRatio)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(progressRatio.toDouble() * duration), color = Color.Gray, fontSize = 12.sp)
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
