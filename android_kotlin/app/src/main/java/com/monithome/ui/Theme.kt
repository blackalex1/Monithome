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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp

object MonitTheme {
    val Background = Color(0xFF020617)
    val Surface = Color(0xFF0F172A)
    val Primary = Color(0xFF38BDF8)
    val Secondary = Color(0xFF818CF8)
    val Accent = Color(0xFFF472B6)
    val TextPrimary = Color.White
    val TextSecondary = Color(0xFF64748B)
    
    val GlassBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.05f),
            Color.Black.copy(alpha = 0.4f)
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
fun RGBCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(modifier, cornerRadius, content)
}

@Composable
fun UltraGlowCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val transition = rememberInfiniteTransition(label = "ultraGlow")

    val angle by transition.animateFloat(
        initialValue = 0f, 
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing)
        ),
        label = "angle"
    )

    val pulse by transition.animateFloat(
        initialValue = 0.6f, 
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val shift by transition.animateFloat(
        initialValue = 0f, 
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shift"
    )

    val colors = listOf(
        Color(0xFF00F5FF),
        Color(0xFF9B5CFF),
        Color(0xFFFF2E88),
        Color(0xFFFFA800),
        Color(0xFF00F5FF)
    )

    Box(
        modifier = modifier
            .padding(20.dp)
            .drawBehind {
                val radius = size.maxDimension / 2f

                // 🌈 1. ГЛУБОКОЕ АМБИЕНТ-СВЕЧЕНИЕ
                rotate(angle) {
                    drawCircle(
                        brush = Brush.sweepGradient(colors),
                        radius = radius * 1.4f * pulse,
                        alpha = 0.25f,
                        blendMode = BlendMode.Screen
                    )
                }

                // 💡 2. МЯГКОЕ РАССЕЯННОЕ СВЕЧЕНИЕ
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF38BDF8).copy(alpha = 0.15f * pulse),
                            Color.Transparent
                        )
                    ),
                    radius = radius * 1.8f
                )
            }
            .drawWithContent {
                val corner = cornerRadius.toPx()
                val strokeWidth = 2.5.dp.toPx()
                
                val path = Path().apply {
                    addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(corner)))
                }

                // 🌈 3. RGB КОНТУР (по всему телу карточки, но обрезанный)
                clipPath(path) {
                    rotate(angle) {
                        drawCircle(
                            brush = Brush.sweepGradient(colors),
                            radius = size.maxDimension
                        )
                    }
                }

                // 🧊 4. ТЕЛО КАРТОЧКИ (закрываем центр, оставляя рамку)
                val innerPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(strokeWidth, strokeWidth, size.width - strokeWidth, size.height - strokeWidth),
                            cornerRadius = CornerRadius(corner - strokeWidth)
                        )
                    )
                }
                
                drawPath(innerPath, color = Color(0xFF020617))
                drawPath(innerPath, brush = MonitTheme.GlassBrush)

                // ✨ 5. INNER GLOW (блик)
                clipPath(innerPath) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.08f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * shift, size.height * shift),
                            radius = size.minDimension
                        )
                    )
                }

                drawContent()
            }
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
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
                Brush.radialGradient(
                    colors = listOf(color1, color2),
                    center = Offset(0f, 0f),
                    radius = 2000f
                )
            )
    )
}