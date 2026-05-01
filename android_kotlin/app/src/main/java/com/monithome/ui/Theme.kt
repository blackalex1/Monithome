package com.monithome.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object MonitTheme {
    val Background = Color(0xFF020617) // Глубокий черный с синим оттенком
    val Surface = Color(0xFF0F172A)    // Очень темный синий
    val Primary = Color(0xFF38BDF8)
    val Secondary = Color(0xFF818CF8)
    val Accent = Color(0xFFF472B6)
    val TextPrimary = Color.White
    val TextSecondary = Color(0xFF64748B) // Чуть темнее для вторичного текста
    
    val GlassBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.05f), // Почти прозрачный белый
            Color.Black.copy(alpha = 0.4f)   // Темный низ для глубины
        )
    )
    
    val GlassBorder = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.2f),
            Color.White.copy(alpha = 0.05f)
        )
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MonitTheme.GlassBrush)
            .border(1.dp, MonitTheme.GlassBorder, RoundedCornerShape(cornerRadius))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            content()
        }
    }
}

@Composable
fun AnimatedBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    
    val progress1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "p1"
    )
    
    val progress2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "p2"
    )

    val color1 = lerp(Color(0xFF000000), Color(0xFF0F172A), progress1)
    val color2 = lerp(Color(0xFF020617), Color(0xFF000000), progress2)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(color1, color2),
                    center = Offset(0f, 0f),
                    radius = 2000f
                )
            )
    )
}
