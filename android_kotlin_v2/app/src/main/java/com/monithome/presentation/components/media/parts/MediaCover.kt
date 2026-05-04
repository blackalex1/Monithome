package com.monithome.presentation.components.media.parts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MediaCover(coverUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var decodedModel by remember { mutableStateOf<Any?>(null) }
    var aspectRatio by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(coverUrl) {
        if (coverUrl.isEmpty()) {
            decodedModel = null
            aspectRatio = 1f
            return@LaunchedEffect
        }
        decodedModel = withContext(Dispatchers.Default) {
            if (coverUrl.startsWith("http")) coverUrl
            else {
                try {
                    val clean = if (coverUrl.contains(",")) coverUrl.substringAfter(",") else coverUrl
                    android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                } catch (e: Exception) { null }
            }
        }
    }

    Box(
        modifier = modifier
            .height(80.dp)
            .aspectRatio(aspectRatio)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.outline),
        contentAlignment = Alignment.Center
    ) {
        if (decodedModel != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(decodedModel)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { state ->
                    val drawable = state.result.drawable
                    if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                        aspectRatio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight.toFloat()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray)
        }
    }
}
