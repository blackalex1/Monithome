package com.monithome.data.network.yandex

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.monithome.domain.models.StationConfig

class YandexDiscovery(
    private val nsdManager: NsdManager,
    private val onResolved: (String, String) -> Unit
) {
    companion object {
        private const val TAG = "YandexDiscovery"
    }

    fun discover(config: StationConfig) {
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Discovery started for ${config.name}")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")
                if (service.serviceName.contains(config.deviceId, ignoreCase = true) || 
                    service.serviceName.contains(config.name, ignoreCase = true)) {
                    
                    val parentListener = this
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "Resolve failed for ${service.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val ip = serviceInfo.host.hostAddress ?: return
                            Log.d(TAG, "Service resolved: $ip")
                            onResolved(config.deviceId, ip)
                            try {
                                nsdManager.stopServiceDiscovery(parentListener)
                            } catch (e: Exception) {
                                // Игнорируем если уже остановлено
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(regType: String) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                try { nsdManager.stopServiceDiscovery(this) } catch (e: Exception) {}
            }

            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices("_yandexio._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate discovery: ${e.message}")
        }
    }
}
