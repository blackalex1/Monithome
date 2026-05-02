package com.monithome

import android.os.Bundle
import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.edit
import androidx.compose.runtime.*
import com.monithome.ui.*
import com.monithome.network.SocketManager
import com.monithome.network.YandexStationManager
import com.monithome.data.LanguageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.graphics.Color
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        
        // Инициализируем менеджеры
        LanguageManager.init(this)
        YandexStationManager.init(this)
        
        // Включаем полноэкранный режим (Immersive Mode) и прозрачность
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        
        // Убираем ограничения выреза (notch)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        // Держим экран включенным
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("monithome_prefs", Context.MODE_PRIVATE)
        
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
                        prefs.edit {
                            putString("auth_token", token)
                            putString("server_ip", serverIp)
                        }
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
                            prefs.edit { putString("server_ip", serverIp) }
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
