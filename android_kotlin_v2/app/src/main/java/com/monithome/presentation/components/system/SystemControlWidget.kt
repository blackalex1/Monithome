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

import com.monithome.presentation.dashboard.DashboardState
import com.monithome.presentation.dashboard.util.t
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.luminance

@Composable
fun SystemControlWidget(
    state: DashboardState,
    onIntent: (DashboardIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    // Состояние для диалога подтверждения
    var showConfirmDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<Pair<String, String>?>(null) } // actionId to translationKey

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = state.t("pc_control_title", "Управление ПК"),
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
                    label = state.t("Сон", "Сон"),
                    icon = Icons.Filled.NightsStay,
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { 
                        pendingAction = "sleep" to "confirm_sleep"
                        showConfirmDialog = true
                    }
                )
                SystemButton(
                    label = state.t("Выкл.", "Выкл"),
                    icon = Icons.Filled.PowerSettingsNew,
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { 
                        pendingAction = "shutdown" to "confirm_shutdown"
                        showConfirmDialog = true
                    }
                )
                SystemButton(
                    label = state.t("Блок.", "Блок"),
                    icon = Icons.Filled.Lock,
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { 
                        onIntent(DashboardIntent.PluginCommand("pc_system", "lock"))
                    }
                )
                SystemButton(
                    label = state.t("Рестарт", "Рестарт"),
                    icon = Icons.Filled.Refresh,
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { 
                        pendingAction = "restart" to "confirm_restart"
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
            title = { Text(state.t("confirm_title", "Подтверждение")) },
            text = { Text(state.t(pendingAction!!.second, pendingAction!!.second)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onIntent(DashboardIntent.PluginCommand("pc_system", pendingAction!!.first))
                        showConfirmDialog = false
                    }
                ) {
                    Text(state.t("btn_yes", "Да"), color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(state.t("btn_cancel", "Отмена"), color = Color.White)
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
        // Если акцентный цвет слишком темный, используем onSurface для текста/иконки
        val iconTint = if (color.luminance() < 0.2f) MaterialTheme.colorScheme.onSurface else color
        
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), CircleShape)
                .border(1.dp, color.copy(alpha = 0.4f), CircleShape)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = if (color.luminance() < 0.2f) Color.LightGray else color.copy(alpha = 0.8f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
