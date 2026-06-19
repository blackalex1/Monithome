package com.monithome.presentation.components.system

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clip
import com.monithome.data.network.socket.CameraStreamService
import com.monithome.presentation.dashboard.util.t
import com.monithome.domain.repository.SettingsRepository
import org.koin.compose.koinInject

@Composable
fun CameraWidget(
    translations: Map<String, String>,
    themeColor: Long,
    modifier: Modifier = Modifier
) {
    val settingsRepository: SettingsRepository = koinInject()
    val context = LocalContext.current
    var isStreaming by remember { mutableStateOf(CameraStreamService.isRunning) }
    var streamUrl by remember { mutableStateOf(CameraStreamService.streamUrl) }
    var isUsbMode by remember { mutableStateOf(settingsRepository.getString("camera_use_usb", "false") == "true") }
    var useFrontCamera by remember { mutableStateOf(settingsRepository.getString("camera_use_front", "false") == "true") }
    var cameraQuality by remember { mutableStateOf(settingsRepository.getString("camera_quality", "HD") ?: "HD") }
    var isQualityExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(isUsbMode) {
        settingsRepository.saveString("camera_use_usb", if (isUsbMode) "true" else "false")
    }

    LaunchedEffect(useFrontCamera) {
        settingsRepository.saveString("camera_use_front", if (useFrontCamera) "true" else "false")
    }

    // Query supported resolutions dynamically for the currently selected camera facing
    val supportedResolutions = remember(useFrontCamera) {
        val list = mutableListOf<String>()
        try {
            val cameraManager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val facingLvl = if (useFrontCamera) android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            var foundSizes: Array<android.util.Size>? = null
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                if (facing == facingLvl) {
                    val map = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    foundSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                    break
                }
            }
            if (foundSizes != null) {
                val sizesList = foundSizes.toList()
                if (sizesList.any { it.width == 640 && it.height == 480 }) list.add("SD")
                if (sizesList.any { it.width == 1280 && it.height == 720 }) list.add("HD")
                if (sizesList.any { it.width == 1920 && it.height == 1080 }) list.add("FHD")
            }
        } catch (e: Exception) {
            // Ignore
        }
        if (list.isEmpty()) {
            list.addAll(listOf("SD", "HD", "FHD"))
        }
        list
    }

    // Automatically fall back if the saved/selected resolution is not supported by the current camera
    LaunchedEffect(supportedResolutions) {
        if (cameraQuality !in supportedResolutions) {
            cameraQuality = when {
                supportedResolutions.contains("HD") -> "HD"
                supportedResolutions.contains("FHD") -> "FHD"
                else -> supportedResolutions.firstOrNull() ?: "HD"
            }
        }
    }

    LaunchedEffect(cameraQuality) {
        settingsRepository.saveString("camera_quality", cameraQuality)
    }

    // Periodically sync the service state
    LaunchedEffect(Unit) {
        while (true) {
            isStreaming = CameraStreamService.isRunning
            streamUrl = CameraStreamService.streamUrl
            
            // Sync camera facing on the fly
            val rtsp = CameraStreamService.rtspServerCamera2Instance
            if (rtsp != null) {
                try {
                    useFrontCamera = (rtsp.getCameraFacing() == com.pedro.encoder.input.video.CameraHelper.Facing.FRONT)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            
            kotlinx.coroutines.delay(1000)
        }
    }

    val permissionsToRequest = remember {
        mutableListOf(
            Manifest.permission.CAMERA
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            CameraStreamService.start(context, isUsbMode, useFrontCamera, cameraQuality)
            isStreaming = true
        } else {
            Toast.makeText(
                context,
                translations.t("camera_permission_denied", "Доступ к камере отклонен"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = translations.t("virtual_camera_title", "MonitHome Camera"),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isStreaming) {
                            translations.t("camera_streaming_active", "Трансляция активна")
                        } else {
                            translations.t("camera_streaming_inactive", "Камера выключена")
                        },
                        color = if (isStreaming) Color(themeColor) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (isStreaming && streamUrl != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = streamUrl ?: "",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            fontSize = 11.sp
                        )
                    }
                    
                    // Connection Mode Selector (Wi-Fi or USB Cable)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        RadioButton(
                            selected = !isUsbMode,
                            onClick = { if (!isStreaming) isUsbMode = false },
                            enabled = !isStreaming,
                            colors = RadioButtonDefaults.colors(selectedColor = Color(themeColor))
                        )
                        Text(
                            text = "Wi-Fi",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable(enabled = !isStreaming) { isUsbMode = false }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        RadioButton(
                            selected = isUsbMode,
                            onClick = { if (!isStreaming) isUsbMode = true },
                            enabled = !isStreaming,
                            colors = RadioButtonDefaults.colors(selectedColor = Color(themeColor))
                        )
                        Text(
                            text = "USB Кабель",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable(enabled = !isStreaming) { isUsbMode = true }
                        )
                    }
                    
                    // Camera Selector (Front vs Back)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        RadioButton(
                            selected = !useFrontCamera,
                            onClick = { if (!isStreaming) useFrontCamera = false },
                            enabled = !isStreaming,
                            colors = RadioButtonDefaults.colors(selectedColor = Color(themeColor))
                        )
                        Text(
                            text = translations.t("camera_back", "Задняя камера"),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable(enabled = !isStreaming) { useFrontCamera = false }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        RadioButton(
                            selected = useFrontCamera,
                            onClick = { if (!isStreaming) useFrontCamera = true },
                            enabled = !isStreaming,
                            colors = RadioButtonDefaults.colors(selectedColor = Color(themeColor))
                        )
                        Text(
                            text = translations.t("camera_front", "Фронтальная"),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable(enabled = !isStreaming) { useFrontCamera = true }
                        )
                    }

                    // Quality Selector (Dropdown)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            text = translations.t("camera_quality_label", "Качество: "),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Box {
                            Text(
                                text = when (cameraQuality) {
                                    "SD" -> "SD (480p)"
                                    "FHD" -> "Full HD (1080p)"
                                    else -> "HD (720p)"
                                },
                                color = Color(themeColor),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable(enabled = !isStreaming) { isQualityExpanded = true }
                                    .padding(vertical = 4.dp, horizontal = 8.dp)
                            )
                            DropdownMenu(
                                expanded = isQualityExpanded,
                                onDismissRequest = { isQualityExpanded = false }
                            ) {
                                if (supportedResolutions.contains("SD")) {
                                    DropdownMenuItem(
                                        text = { Text("SD (480p)") },
                                        onClick = {
                                            cameraQuality = "SD"
                                            isQualityExpanded = false
                                        }
                                    )
                                }
                                if (supportedResolutions.contains("HD")) {
                                    DropdownMenuItem(
                                        text = { Text("HD (720p)") },
                                        onClick = {
                                            cameraQuality = "HD"
                                            isQualityExpanded = false
                                        }
                                    )
                                }
                                if (supportedResolutions.contains("FHD")) {
                                    DropdownMenuItem(
                                        text = { Text("Full HD (1080p)") },
                                        onClick = {
                                            cameraQuality = "FHD"
                                            isQualityExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (isStreaming) {
                                CameraStreamService.stop(context)
                                isStreaming = false
                            } else {
                                val hasCameraPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
         
                                if (hasCameraPermission) {
                                    CameraStreamService.start(context, isUsbMode, useFrontCamera, cameraQuality)
                                    isStreaming = true
                                } else {
                                    permissionLauncher.launch(permissionsToRequest)
                                }
                            }
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                if (isStreaming) Color(themeColor).copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                CircleShape
                            )
                            .border(
                                1.dp,
                                if (isStreaming) Color(themeColor)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isStreaming) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                            contentDescription = "Toggle Camera",
                            tint = if (isStreaming) Color(themeColor) else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    if (isStreaming) {
                        Spacer(modifier = Modifier.width(12.dp))
                        IconButton(
                            onClick = {
                                try {
                                    CameraStreamService.rtspServerCamera2Instance?.switchCamera()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    CircleShape
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Switch Camera",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        com.pedro.library.view.OpenGlView(ctx).apply {
                            if (holder.surface.isValid) {
                                CameraStreamService.activePreviewView = this
                            }
                            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                    if (CameraStreamService.activePreviewView != this@apply) {
                                        CameraStreamService.activePreviewView = this@apply
                                    }
                                }
                                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
                                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                                    if (CameraStreamService.activePreviewView == this@apply) {
                                        CameraStreamService.activePreviewView = null
                                    }
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = {
                        CameraStreamService.activePreviewView = null
                    }
                )
                
                if (!isStreaming) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = translations.t("camera_preview_placeholder", "Превью камеры отключено"),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}
