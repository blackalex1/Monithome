package com.monithome.presentation.components.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StatsChartsWidget(
    cpuHistory: List<Float>,
    cpuTempHistory: List<Float>,
    gpuHistory: List<Float>,
    gpuTempHistory: List<Float>,
    diskTemps: List<Map<String, Any>>,
    currentStats: Map<String, Any>,
    translations: Map<String, String>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    fun t(key: String, default: String) = translations[key] ?: default

    Card(
        onClick = onClose,
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
    ) {
        Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = t("stats_detailed_title", "Detailed Performance"),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // CPU SECTION
            ChartSection(
                title = "CPU: ${currentStats["cpu_name"] ?: "Processor"}",
                loadData = cpuHistory,
                tempData = cpuTempHistory,
                loadColor = Color(0xFF38BDF8),
                tempColor = Color(0xFFEF4444),
                loadLabel = t("stats_load", "LOAD"),
                tempLabel = t("stats_temp", "TEMP")
            )
            
            // GPU SECTION
            if (currentStats["has_gpu"] == true) {
                Spacer(modifier = Modifier.height(24.dp))
                ChartSection(
                    title = "GPU: ${currentStats["gpu_name"] ?: "Graphics"}",
                    loadData = gpuHistory,
                    tempData = gpuTempHistory,
                    loadColor = Color(0xFFFBBF24),
                    tempColor = Color(0xFFF97316),
                    loadLabel = t("stats_load", "LOAD"),
                    tempLabel = t("stats_temp", "TEMP")
                )
            }

            // DISK TEMPS
            if (diskTemps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(t("stats_storage_title", "Storage Temperatures"), color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                diskTemps.forEach { disk ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(disk["name"] as? String ?: "Disk", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                        Text("${(disk["value"] as? Number)?.toInt() ?: 0}°C", color = Color(0xFF22C55E), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ChartSection(
    title: String, 
    loadData: List<Float>, 
    tempData: List<Float>, 
    loadColor: Color, 
    tempColor: Color,
    loadLabel: String,
    tempLabel: String
) {
    Column {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(6.dp)
        ) {
            // Grid lines
            Canvas(modifier = Modifier.fillMaxSize()) {
                val gridColor = Color.White.copy(alpha = 0.05f)
                drawLine(gridColor, Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f))
                drawLine(gridColor, Offset(0f, size.height * 0.25f), Offset(size.width, size.height * 0.25f))
                drawLine(gridColor, Offset(0f, size.height * 0.75f), Offset(size.width, size.height * 0.75f))
            }
            
            RealTimeChart(data = loadData, color = loadColor, maxValue = 100f)
            RealTimeChart(data = tempData, color = tempColor, maxValue = 100f)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            LegendItem("$loadLabel: ${loadData.lastOrNull()?.toInt() ?: 0}%", loadColor)
            LegendItem("$tempLabel: ${tempData.lastOrNull()?.toInt() ?: 0}°C", tempColor)
        }
    }
}

@Composable
fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RealTimeChart(
    data: List<Float>,
    color: Color,
    maxValue: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (data.size < 2) return@Canvas

        val path = Path()
        val width = size.width
        val height = size.height
        val stepX = width / 49f // Fixed for 50 points

        data.forEachIndexed { index, value ->
            val x = index * stepX
            val y = height - (value / maxValue * height).coerceIn(0f, height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
