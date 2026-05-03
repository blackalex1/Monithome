package com.monithome.presentation.dashboard.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.monithome.presentation.dashboard.DashboardIntent
import com.monithome.presentation.dashboard.DashboardState
import com.monithome.presentation.dashboard.DashboardViewModel

import com.monithome.presentation.dashboard.util.t

@Composable
fun AuthDialog(state: DashboardState, viewModel: DashboardViewModel) {
    val password = remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(state.t("auth_title", "Авторизация")) },
        text = {
            Column {
                Text(state.t("auth_desc", "Введите код доступа для MonitHome:"))
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = password.value,
                    onValueChange = { password.value = it },
                    placeholder = { Text(state.t("auth_placeholder", "Пароль")) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.processIntent(DashboardIntent.Auth(password.value)) }) {
                Text(state.t("btn_login", "Войти"))
            }
        }
    )
}
