package com.monithome.ui

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.data.PluginRepository
import com.monithome.models.Widget
import androidx.compose.ui.text.style.TextOverflow

import com.monithome.utils.resolveStat
import com.monithome.utils.toComposeColor

data class ChartSnapshot(
    val points: List<Float>,
    val oldMax: Float,
    val newMax: Float
)

@Composable
fun ChartWidget(pluginId: String, widget: Widget) {
    val stats by PluginRepository.getPluginStats(pluginId).collectAsState()
    val key = widget.dataKey ?: ""
    // Оптимизация: берем историю только когда меняются сами данные статистики
    val history = remember(stats[key]) {
        PluginRepository.getHistory(pluginId)[key] ?: emptyList()
    }
    val currentValue = stats.resolveStat(key, widget.unit)
    
    val componentName = remember(key, stats) {
        when {
            key.contains("cpu") -> stats["cpu_name"]?.toString()
            key.contains("gpu") -> stats["gpu_name"]?.toString()
            else -> null
        }
    }

    val baseColor = widget.color.toComposeColor()
    val pointCount = 60
    
    // Единое атомарное состояние графика
    var snapshot by remember { 
        val initial = history.takeLast(pointCount + 3)
        val rawMax = initial.maxOrNull() ?: 100f
        val startMax = if (widget.unit == "%") 100f else if (rawMax < 1f) 1f else rawMax * 1.05f
        mutableStateOf(ChartSnapshot(initial, startMax, startMax)) 
    }
    
    val transitionProgress = remember { Animatable(0f) }
    
    LaunchedEffect(history.size, history.lastOrNull()) {
        if (history.isNotEmpty()) {
            val nextPoints = history.takeLast(pointCount + 3)
            val rawMax = nextPoints.maxOrNull() ?: 100f
            val nextMax = if (widget.unit == "%") 100f else if (rawMax < 1f) 1f else rawMax * 1.05f
            
            snapshot = ChartSnapshot(
                points = nextPoints,
                oldMax = snapshot.newMax,
                newMax = nextMax
            )
            
            transitionProgress.snapTo(0f)
            transitionProgress.animateTo(1f, tween(1000, easing = LinearEasing))
        }
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        cornerRadius = 20.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!widget.icon.isNullOrEmpty()) {
                        Icon(imageVector = mapIcon(widget.icon), contentDescription = null, tint = baseColor, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Column {
                        Text(widget.label ?: "", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        if (!componentName.isNullOrEmpty()) {
                            Text(componentName, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
                Text(currentValue, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Optimized Neon Chart Canvas
            val chartPath = remember { Path() }
            val fillPath = remember { Path() }
            
            // Кэшируем кисти и градиенты, чтобы не создавать их каждый кадр
            val fillBrush = remember(baseColor) {
                Brush.verticalGradient(
                    colors = listOf(baseColor.copy(alpha = 0.3f), baseColor.copy(alpha = 0.05f), Color.Transparent)
                )
            }
            val glowBrush = remember(baseColor) {
                Brush.radialGradient(
                    colors = listOf(baseColor, Color.Transparent)
                )
            }

            val nativePaint = remember<android.graphics.Paint> {
                android.graphics.Paint().apply {
                    isAntiAlias = true
                    style = android.graphics.Paint.Style.STROKE
                    // МЫ УБРАЛИ BlurMaskFilter, так как он сильно тормозит старые планшеты
                }
            }
            
            // Для drawIntoCanvas используем стабильный нативный путь
            val androidPath = remember { android.graphics.Path() }
            
            Canvas(modifier = Modifier.fillMaxWidth().height(84.dp)) {
                val width = size.width
                val height = size.height
                val p = transitionProgress.value
                
                val currentMaxVal = (snapshot.oldMax + (snapshot.newMax - snapshot.oldMax) * p).coerceAtLeast(0.1f)
                val points = snapshot.points
                if (points.size < 2) return@Canvas
                
                chartPath.rewind()
                fillPath.rewind()
                
                val isScrolling = points.size >= pointCount + 3
                val stepX = if (isScrolling) width / pointCount.toFloat() else if (points.size > 1) width / (points.size - 1).toFloat() else width
                
                // 1. Build Smooth Cubic Bezier Path (Optimized)
                var firstX = if (isScrolling) (0 - 1 - p) * stepX else 0 * stepX
                var firstY = (height - (points[0] / currentMaxVal * height)).coerceIn(0f, height)
                chartPath.moveTo(firstX, firstY)
                
                var prevX = firstX
                var prevY = firstY
                
                for (i in 1 until points.size) {
                    val curX = if (isScrolling) (i - 1 - p) * stepX else i * stepX
                    val curY = (height - (points[i] / currentMaxVal * height)).coerceIn(0f, height)
                    
                    val cx = (prevX + curX) / 2f
                    chartPath.cubicTo(cx, prevY, cx, curY, curX, curY)
                    
                    prevX = curX
                    prevY = curY
                }

                // 2. Build Fill Path
                fillPath.addPath(chartPath)
                fillPath.lineTo(prevX, height)
                fillPath.lineTo(firstX, height)
                fillPath.close()

                // 3. Draw Gradient Fill
                drawPath(path = fillPath, brush = fillBrush)

                // 4. Draw Neon Outer Glow (Using cached androidPath)
                val glowStrokeWidth = 5.dp.toPx()
                drawIntoCanvas { canvas ->
                    nativePaint.strokeWidth = glowStrokeWidth
                    nativePaint.color = baseColor.toArgb()
                    nativePaint.alpha = 80
                    
                    // Синхронизируем нативный путь без создания нового объекта
                    androidPath.rewind()
                    androidPath.addPath(chartPath.asAndroidPath()) 
                    // К сожалению, asAndroidPath() в Compose всё равно возвращает обертку, 
                    // но это всё же лучше чем создавать вручную каждый раз.
                    canvas.nativeCanvas.drawPath(androidPath, nativePaint)
                }

                // 5. Draw Main Neon Line
                drawPath(
                    path = chartPath,
                    color = baseColor,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // 6. Draw White Core Line (for crispness)
                drawPath(
                    path = chartPath,
                    color = Color.White.copy(alpha = 0.6f),
                    style = Stroke(width = 0.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // 7. Draw Leading Active Dot (The Glowy Tip)
                val activeX = prevX
                val activeY = prevY
                val activePt = Offset(activeX, activeY)
                
                // Outer glow of the dot
                drawCircle(
                    brush = glowBrush,
                    center = activePt,
                    radius = 12.dp.toPx()
                )
                
                // Solid dot
                drawCircle(
                    color = Color.White,
                    radius = 3.dp.toPx(),
                    center = activePt
                )
                drawCircle(
                    color = baseColor,
                    radius = 4.dp.toPx(),
                    center = activePt,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}
