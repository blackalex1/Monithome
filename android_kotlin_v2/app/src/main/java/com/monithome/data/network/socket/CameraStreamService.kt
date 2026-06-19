package com.monithome.data.network.socket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import android.graphics.SurfaceTexture
import com.pedro.common.ConnectChecker
import com.pedro.rtspserver.RtspServerCamera2
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.view.OpenGlView
import com.pedro.encoder.input.video.CameraHelper
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections

class CameraStreamService : Service(), ConnectChecker, KoinComponent {

    private val socketClient: PcSocketClient by inject()
    private var rtspServerCamera2: RtspServerCamera2? = null
    private val port = 8554

    private var useUsb = false
    private var useFrontCamera = false
    private var quality = "HD"

    companion object {
        private const val TAG = "CameraStreamService"
        private const val CHANNEL_ID = "camera_stream_channel"
        private const val NOTIFICATION_ID = 2026
        
        var isRunning = false
            private set
            
        var streamUrl: String? = null
            private set

        var rtspServerCamera2Instance: RtspServerCamera2? = null
            private set

        var serviceContext: Context? = null
            private set

        var activeWidth: Int = 1280
            private set

        var activeHeight: Int = 720
            private set

        var activeUseUsb: Boolean = false
            private set

        var activePreviewView: OpenGlView? = null
            set(value) {
                field = value
                val rtsp = rtspServerCamera2Instance
                if (rtsp != null) {
                    try {
                        if (value != null) {
                            rtsp.replaceView(value)
                        } else {
                            val ctx = serviceContext
                            if (ctx != null) {
                                rtsp.replaceView(ctx)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error replacing view", e)
                    }
                }
            }

        fun start(context: Context, useUsb: Boolean = false, useFrontCamera: Boolean = false, quality: String = "HD") {
            val intent = Intent(context, CameraStreamService::class.java).apply {
                putExtra("use_usb", useUsb)
                putExtra("use_front_camera", useFrontCamera)
                putExtra("quality", quality)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CameraStreamService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceContext = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service onStartCommand")
        val newUseUsb = intent?.getBooleanExtra("use_usb", false) ?: false
        val newUseFront = intent?.getBooleanExtra("use_front_camera", false) ?: false
        val newQuality = intent?.getStringExtra("quality") ?: "HD"

        if (isRunning) {
            if (newUseUsb == useUsb && newUseFront == useFrontCamera && newQuality == quality) {
                Log.i(TAG, "Service already running with same parameters. Resending PC start event.")
                sendStartNotificationToPc()
                return START_STICKY
            } else {
                Log.i(TAG, "Parameters changed. Stopping old stream first.")
                stopRtspStream()
            }
        }

        useUsb = newUseUsb
        useFrontCamera = newUseFront
        quality = newQuality

        // Start as foreground service
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        startRtspStream()

        return START_STICKY
    }

    private fun getSupportedResolutions(useFrontCamera: Boolean): List<Size> {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val facingLvl = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == facingLvl) {
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
                    if (sizes != null) {
                        return sizes.toList()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query supported resolutions", e)
        }
        return emptyList()
    }

    private fun getBestSupportedResolution(
        supportedSizes: List<Size>,
        targetWidth: Int,
        targetHeight: Int
    ): Size {
        if (supportedSizes.isEmpty()) {
            return Size(targetWidth, targetHeight)
        }
        
        // Exact match
        val exactMatch = supportedSizes.find { it.width == targetWidth && it.height == targetHeight }
        if (exactMatch != null) {
            return exactMatch
        }
        
        // Match aspect ratio or closest
        val targetAspectRatio = targetWidth.toFloat() / targetHeight.toFloat()
        val sortedSizes = supportedSizes.sortedWith(compareByDescending { it.width * it.height })
        
        val aspectMatch = sortedSizes.find { size ->
            val aspect = size.width.toFloat() / size.height.toFloat()
            Math.abs(aspect - targetAspectRatio) < 0.1 && (size.width <= targetWidth && size.height <= targetHeight)
        }
        if (aspectMatch != null) {
            return aspectMatch
        }
        
        val smallerSize = sortedSizes.find { it.width <= targetWidth && it.height <= targetHeight }
        if (smallerSize != null) {
            return smallerSize
        }
        
        return sortedSizes.first()
    }

    private fun startRtspStream() {
        try {
            val localIp = getLocalIpAddress() ?: "127.0.0.1"
            streamUrl = "rtsp://$localIp:$port/live"
            Log.i(TAG, "Starting RTSP server stream at: $streamUrl (useUsb: $useUsb, useFrontCamera: $useFrontCamera)")

            // Initialize camera using the preview View constructor if available, otherwise background Context constructor
            val view = activePreviewView
            val rtsp = if (view != null) {
                RtspServerCamera2(view, this, port)
            } else {
                RtspServerCamera2(this, this, port)
            }
            rtspServerCamera2 = rtsp
            rtsp.apply {
                // Force hardware video codec for fast, zero-latency encoding
                forceCodecType(CodecUtil.CodecType.HARDWARE, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)

                var requestedWidth = 1280
                var requestedHeight = 720
                var bitrate = 2000000

                when (quality) {
                    "SD" -> {
                        requestedWidth = 640
                        requestedHeight = 480
                        bitrate = 800000
                    }
                    "FHD" -> {
                        requestedWidth = 1920
                        requestedHeight = 1080
                        bitrate = 4000000
                    }
                    else -> { // HD
                        requestedWidth = 1280
                        requestedHeight = 720
                        bitrate = 2000000
                    }
                }

                // Query and validate camera capabilities to prevent HAL crash on unsupported resolutions
                val supportedSizes = getSupportedResolutions(useFrontCamera)
                val bestSize = getBestSupportedResolution(supportedSizes, requestedWidth, requestedHeight)
                val width = bestSize.width
                val height = bestSize.height

                // Adjust bitrate dynamically if resolution had to be scaled down
                if (width != requestedWidth || height != requestedHeight) {
                    Log.w(TAG, "Requested resolution ${requestedWidth}x${requestedHeight} is not supported by camera. Falling back to supported size: ${width}x${height}")
                    val pixels = width * height
                    bitrate = when {
                        pixels >= 1920 * 1080 -> 4000000
                        pixels >= 1280 * 720  -> 2000000
                        else                  -> 800000
                    }
                }

                // Configure video dynamically based on chosen/fallback quality
                val videoPrepared = prepareVideo(width, height, 30, bitrate, 1, 0)
                
                // Set small internal cache (default is 400) to avoid frame buffering latency
                getStreamClient().resizeCache(20)
                
                if (videoPrepared) {
                    val facing = if (useFrontCamera) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
                    startPreview(facing)
                    startStream()
                    Log.i(TAG, "RTSP stream started successfully (Video Only, Quality: $quality, ${width}x${height})")
                    
                    activeWidth = width
                    activeHeight = height
                    activeUseUsb = useUsb
                    sendStartNotificationToPc()
                } else {
                    Log.e(TAG, "Failed to prepare RTSP stream. Video: $videoPrepared")
                    stopSelf()
                }
            }
            rtspServerCamera2Instance = rtspServerCamera2
            if (view == null && activePreviewView != null) {
                activePreviewView?.let { rtspServerCamera2?.replaceView(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting RTSP stream", e)
            stopSelf()
        }
    }

    fun sendStartNotificationToPc() {
        val url = streamUrl
        if (url != null) {
            val dataJson = JSONObject().apply {
                put("rtsp_url", url)
                put("use_usb", activeUseUsb)
                put("width", activeWidth)
                put("height", activeHeight)
            }
            socketClient.sendCommand("virtual_camera", "start_camera", null, dataJson)
        }
    }

    private fun stopRtspStream() {
        Log.i(TAG, "Stopping RTSP stream")
        try {
            rtspServerCamera2?.let {
                if (it.isStreaming) {
                    it.stopStream()
                }
            }

            // Notify the PC plugin that the camera has stopped so it can terminate FFmpeg cleanly
            socketClient.sendCommand("virtual_camera", "stop_camera", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping stream", e)
        } finally {
            rtspServerCamera2Instance = null
            rtspServerCamera2 = null
            streamUrl = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy")
        serviceContext = null
        stopRtspStream()
        isRunning = false
        stopForeground(true)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // ConnectChecker Implementation
    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "RTSP connection started: $url")
    }

    override fun onConnectionSuccess() {
        Log.i(TAG, "RTSP client connected successfully")
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "RTSP connection failed: $reason")
    }

    override fun onNewBitrate(bitrate: Long) {
        Log.d(TAG, "RTSP new bitrate: $bitrate")
    }

    override fun onDisconnect() {
        Log.i(TAG, "RTSP client disconnected")
    }

    override fun onAuthError() {
        Log.e(TAG, "RTSP auth error")
    }

    override fun onAuthSuccess() {
        Log.i(TAG, "RTSP auth success")
    }

    // Helper functions
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "MonitHome Camera Stream"
            val descriptionText = "Notifications for active background camera streaming"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val title = "MonitHome Camera Active"
        val content = "Streaming video to MonitHome PC application"
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress) {
                        val sAddr = address.hostAddress ?: continue
                        val isIPv4 = sAddr.indexOf(':') < 0
                        if (isIPv4) {
                            return sAddr
                        }
                    }
                }
            }
        } catch (e: SocketException) {
            Log.e(TAG, "Error getting local IP address", e)
        }
        return null
    }
}
