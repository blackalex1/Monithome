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
                
                path.rewind()
                path.addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(corner, corner)))

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
                innerPath.rewind()
                innerPath.addRoundRect(
                    RoundRect(
                        rect = Rect(strokeWidth, strokeWidth, size.width - strokeWidth, size.height - strokeWidth),
                        cornerRadius = CornerRadius(corner - strokeWidth, corner - strokeWidth)
                    )
                )
                
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
    val transition = rememberInfiniteTransition(label = "nebula")
    
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)) // Deep dark base
            .drawBehind {
                val w = size.width
                val h = size.height
                
                // Spot 1: Cyan
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF00F5FF).copy(alpha = 0.08f), Color.Transparent),
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
                        colors = listOf(Color(0xFF9B5CFF).copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(
                            w * (0.8f + 0.2f * kotlin.math.cos(time * 2 * Math.PI.toFloat() + 1f)),
                            h * (0.7f + 0.3f * kotlin.math.sin(time * 2 * Math.PI.toFloat() + 1f))
                        ),
                        radius = w * 0.9f
                    ),
                    radius = w * 0.9f
                )

                // Spot 3: Deep Blue / Indigo
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF38BDF8).copy(alpha = 0.1f), Color.Transparent),
                        center = Offset(
                            w * (0.5f + 0.4f * kotlin.math.sin(time * 2 * Math.PI.toFloat() * 0.5f)),
                            h * (0.5f + 0.4f * kotlin.math.cos(time * 2 * Math.PI.toFloat() * 0.5f))
                        ),
                        radius = w * 1.2f
                    ),
                    radius = w * 1.2f
                )
            }
    )
}