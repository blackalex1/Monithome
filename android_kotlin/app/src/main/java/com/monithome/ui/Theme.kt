package com.monithome.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp

object MonitTheme {
    val Primary = Color(0xFF38BDF8)
    val Secondary = Color(0xFF818CF8)
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
            animation = tween(12000, easing = LinearEasing) // Замедлил для плавности
        ),
        label = "angle"
    )

    val pulse by transition.animateFloat(
        initialValue = 0.8f, 
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val shift by transition.animateFloat(
        initialValue = 0f, 
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shift"
    )

    // Кэшируем тяжелые объекты
    val glowColors = remember {
        listOf(
            Color(0xFF00F5FF),
            Color(0xFF9B5CFF),
            Color(0xFFFF2E88),
            Color(0xFFFFA800),
            Color(0xFF00F5FF)
        )
    }
    
    val sweepBrush = remember(glowColors) { Brush.sweepGradient(glowColors) }
    val ambientColor = remember { Color(0xFF38BDF8) }
    
    val path = remember { Path() }
    val innerPath = remember { Path() }

    Box(
        modifier = modifier
            .padding(20.dp)
            .drawBehind {
                val radius = size.maxDimension / 2f

                // 🌈 1. ГЛУБОКОЕ АМБИЕНТ-СВЕЧЕНИЕ
                rotate(angle) {
                    drawCircle(
                        brush = sweepBrush,
                        radius = radius * 1.3f * pulse,
                        alpha = 0.2f,
                        blendMode = BlendMode.Screen
                    )
                }

                // 💡 2. МЯГКОЕ РАССЕЯННОЕ СВЕЧЕНИЕ
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ambientColor.copy(alpha = 0.12f * pulse), Color.Transparent),
                        radius = radius * 1.6f
                    ),
                    radius = radius * 1.6f
                )
            }
            .drawWithContent {
                val corner = cornerRadius.toPx()
                val strokeWidth = 2.dp.toPx()
                
                path.rewind()
                path.addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(corner, corner)))

                // 🌈 3. RGB КОНТУР
                clipPath(path) {
                    rotate(angle) {
                        drawCircle(
                            brush = sweepBrush,
                            radius = size.maxDimension
                        )
                    }
                }

                // 🧊 4. ТЕЛО КАРТОЧКИ
                innerPath.rewind()
                innerPath.addRoundRect(
                    RoundRect(
                        rect = Rect(strokeWidth, strokeWidth, size.width - strokeWidth, size.height - strokeWidth),
                        cornerRadius = CornerRadius(corner - strokeWidth, corner - strokeWidth)
                    )
                )
                
                drawPath(innerPath, color = Color(0xFF020617))
                drawPath(innerPath, brush = MonitTheme.GlassBrush)

                // ✨ 5. INNER GLOW
                clipPath(innerPath) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
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
    val transition = rememberInfiniteTransition(label = "nebula")
    
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(30000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    // Кэшируем списки цветов для градиентов
    val cyanColors = remember { listOf(Color(0xFF00F5FF).copy(alpha = 0.07f), Color.Transparent) }
    val purpleColors = remember { listOf(Color(0xFF9B5CFF).copy(alpha = 0.06f), Color.Transparent) }
    val blueColors = remember { listOf(Color(0xFF38BDF8).copy(alpha = 0.08f), Color.Transparent) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
            .drawBehind {
                val w = size.width
                val h = size.height
                
                // Spot 1: Cyan (ОПТИМИЗАЦИЯ: Оставили 2 пятна вместо 3 для разгрузки GPU)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = cyanColors,
                        center = Offset(
                            w * (0.2f + 0.3f * kotlin.math.sin(time * 2 * Math.PI.toFloat())),
                            h * (0.3f + 0.2f * kotlin.math.cos(time * 2 * Math.PI.toFloat()))
                        ),
                        radius = w * 0.8f
                    ),
                    radius = w * 0.8f
                )

                // Spot 2: Purple
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = purpleColors,
                        center = Offset(
                            w * (0.8f + 0.2f * kotlin.math.cos(time * 2 * Math.PI.toFloat() + 1f)),
                            h * (0.7f + 0.3f * kotlin.math.sin(time * 2 * Math.PI.toFloat() + 1f))
                        ),
                        radius = w * 0.9f
                    ),
                    radius = w * 0.9f
                )
            }
    )
}