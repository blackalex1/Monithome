package com.monithome

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.monithome.ui.*
import com.monithome.data.PluginRepository
import com.monithome.network.SocketManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        
        // Инициализируем менеджеры
        com.monithome.data.LanguageManager.init(this)
        com.monithome.network.YandexStationManager.init(this)
        
        // Включаем полноэкранный режим (Immersive Mode) и прозрачность
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        // Убираем ограничения выреза (notch)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        // Держим экран включенным
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("monithome_prefs", android.content.Context.MODE_PRIVATE)
        
        setContent {
            var serverIp by remember { mutableStateOf(prefs.getString("server_ip", "") ?: "") }
            var authToken by remember { mutableStateOf(prefs.getString("auth_token", null)) }
            var currentScreen by remember { mutableStateOf(if (serverIp.isNotEmpty() && authToken != null) "dashboard" else "login") }

            val scope = rememberCoroutineScope()
            
            // Авто-подключение при запуске если есть данные
            LaunchedEffect(Unit) {
                if (serverIp.isNotEmpty()) {
                    SocketManager.connect(serverIp, authToken)
                }
            }

            LaunchedEffect(Unit) {
                SocketManager.onAuthRequired = {
                    scope.launch(Dispatchers.Main) {
                        currentScreen = "pairing"
                    }
                }
                SocketManager.onAuthSuccess = { token: String ->
                    scope.launch(Dispatchers.Main) {
                        prefs.edit().putString("auth_token", token).putString("server_ip", serverIp).apply()
                        authToken = token
                        currentScreen = "dashboard"
                    }
                }
                // Переходим в дашборд если получили данные (значит мы авторизованы)
                SocketManager.onDataReceived = {
                    if (currentScreen != "dashboard") {
                        scope.launch(Dispatchers.Main) {
                            currentScreen = "dashboard"
                            // Сохраняем IP, так как подключение успешно
                            prefs.edit().putString("server_ip", serverIp).apply()
                        }
                    }
                }
            }

            // Навигация и управление состоянием подключения
            when (currentScreen) {
                "login" -> {
                    LoginScreen(onConnect = { ip ->
                        serverIp = ip
                        SocketManager.connect(ip, authToken)
                    })
                }
                "pairing" -> {
                    PairingScreen(onPair = { code ->
                        SocketManager.authAttempt(code)
                    })
                }
                "dashboard" -> {
                    DashboardScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SocketManager.disconnect()
    }
}
