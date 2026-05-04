package com.monithome.presentation.components.launcher

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.monithome.presentation.dashboard.DashboardIntent
import com.monithome.presentation.dashboard.util.t
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.remember
import android.graphics.BitmapFactory

@Composable
fun LauncherWidget(
    title: String,
    apps: List<Map<String, Any>>,
    onIntent: (DashboardIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (apps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Приложения не добавлены",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 80.dp),
                    modifier = Modifier.heightIn(max = 400.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(apps) { app ->
                        AppButton(
                            label = app["label"] as? String ?: "",
                            icon = app["icon"] as? String ?: "",
                            onClick = {
                                val path = app["data"] as? String ?: ""
                                onIntent(DashboardIntent.PluginCommand("app_launcher", "launch", data = mapOf("path" to path)))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppButton(
    label: String,
    icon: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(8.dp)
            .width(80.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp),
            contentAlignment = Alignment.Center
        ) {
            if (icon.startsWith("data:image")) {
                val bitmap = remember(icon) {
                    try {
                        val base64String = icon.substringAfter(",")
                        val imageBytes = Base64.decode(base64String, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = label,
                        modifier = Modifier.size(56.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(text = "🚀", fontSize = 28.sp)
                }
            } else {
                // Fallback for emojis or text icons
                val emoji = when (icon) {
                    "Globe" -> "🌐"
                    "MessageSquare" -> "💬"
                    "Gamepad" -> "🎮"
                    "Code" -> "💻"
                    "Music" -> "🎵"
                    "Terminal" -> "⌨️"
                    "AppWindow" -> "🪟"
                    else -> icon.ifEmpty { "🚀" }
                }
                Text(text = emoji, fontSize = 28.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
