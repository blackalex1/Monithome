package com.monithome.presentation.dashboard.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.presentation.dashboard.DashboardIntent
import com.monithome.presentation.dashboard.DashboardState
import com.monithome.presentation.dashboard.DashboardViewModel

@Composable
fun ConnectionScreen(state: DashboardState, viewModel: DashboardViewModel) {
    val manualIp = remember { mutableStateOf("192.168.1.100") }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.pcError != null) {
                Text("Ошибка: ${state.pcError}", color = Color.Red, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text("Выберите сервер или введите IP:", color = Color.White, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(16.dp))
            state.discoveredServers.forEach { server ->
                Button(
                    onClick = { viewModel.processIntent(DashboardIntent.Connect(server.url)) },
                    modifier = Modifier.fillMaxWidth(0.7f).padding(vertical = 4.dp)
                ) {
                    Text("${server.name} (${server.url})")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = manualIp.value,
                    onValueChange = { manualIp.value = it },
                    modifier = Modifier.width(200.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { viewModel.processIntent(DashboardIntent.Connect(manualIp.value)) }) {
                    Text("ОК")
                }
            }
        }
    }
}
