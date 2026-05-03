package com.monithome.presentation.components.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.presentation.dashboard.DashboardIntent

@Composable
fun SystemControlWidget(
    onIntent: (DashboardIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    // Состояние для диалога подтверждения
    var showConfirmDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<Pair<String, String>?>(null) } // actionId to label

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "Управление ПК",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SystemButton(
                    label = "Сон",
                    icon = Icons.Filled.NightsStay,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    onClick = { 
                        pendingAction = "sleep" to "Перевести ПК в режим сна?"
                        showConfirmDialog = true
                    }
                )
                SystemButton(
                    label = "Выкл",
                    icon = Icons.Filled.PowerSettingsNew,
                    color = MaterialTheme.colorScheme.primary, // Using primary accent
                    onClick = { 
                        pendingAction = "shutdown" to "Выключить компьютер?"
                        showConfirmDialog = true
                    }
                )
                SystemButton(
                    label = "Блок",
                    icon = Icons.Filled.Lock,
                    color = MaterialTheme.colorScheme.secondary,
                    onClick = { 
                        onIntent(DashboardIntent.PluginCommand("pc_system", "lock"))
                    }
                )
                SystemButton(
                    label = "Рестарт",
                    icon = Icons.Filled.Refresh,
                    color = MaterialTheme.colorScheme.tertiary,
                    onClick = { 
                        pendingAction = "restart" to "Перезагрузить компьютер?"
                        showConfirmDialog = true
                    }
                )
            }
        }
    }

    // Диалог подтверждения
    if (showConfirmDialog && pendingAction != null) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            title = { Text("Подтверждение") },
            text = { Text(pendingAction!!.second) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onIntent(DashboardIntent.PluginCommand("pc_system", pendingAction!!.first))
                        showConfirmDialog = false
                    }
                ) {
                    Text("Да", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Отмена", color = Color.White)
                }
            }
        )
    }
}

@Composable
fun SystemButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(4.dp)
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .background(color.copy(alpha = 0.1f), CircleShape)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = Color.Gray,
            fontSize = 12.sp
        )
    }
}
