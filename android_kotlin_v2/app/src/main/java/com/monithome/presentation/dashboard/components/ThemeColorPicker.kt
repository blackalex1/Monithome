package com.monithome.presentation.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ThemeColorPicker(
    selectedColor: Long,
    onColorSelect: (Long) -> Unit,
    onClose: () -> Unit,
    suggestedColor: Long? = null
) {
    val standardColors = listOf(
        0xFF22C55E, // Green
        0xFF3B82F6, // Blue
        0xFFEF4444, // Red
        0xFFF59E0B, // Amber
        0xFFA855F7, // Purple
        0xFFEC4899  // Pink
    )

    // Если есть цвет от сервера, добавляем его в начало, если его там нет
    val finalColors = if (suggestedColor != null && !standardColors.contains(suggestedColor)) {
        listOf(suggestedColor) + standardColors
    } else {
        standardColors
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Выберите акцентный цвет",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            finalColors.forEach { colorVal ->
                val isSelected = selectedColor == colorVal
                val isSuggested = colorVal == suggestedColor
                
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 40.dp else 32.dp)
                        .background(Color(colorVal), CircleShape)
                        .border(
                            width = if (isSelected) 3.dp else if (isSuggested) 1.dp else 0.dp,
                            color = if (isSelected) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                        .clickable { onColorSelect(colorVal) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSuggested) {
                        Icon(
                            imageVector = Icons.Default.Monitor,
                            contentDescription = "PC Theme",
                            modifier = Modifier.size(if (isSelected) 18.dp else 14.dp),
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(24.dp))

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Done",
                    tint = Color.White
                )
            }
        }
    }
}
