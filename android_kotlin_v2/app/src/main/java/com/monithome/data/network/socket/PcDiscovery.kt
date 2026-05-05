package com.monithome.data.network.socket

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredServer(
    val name: String,
    val url: String,
    val uuid: String? = null
)

class PcDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_monithome._tcp."

    fun discoverServers(): Flow<DiscoveredServer> = callbackFlow {
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("PcDiscovery", "Discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("PcDiscovery", "Service found: ${service.serviceName}")
                @Suppress("DEPRECATION")
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e("PcDiscovery", "Resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = serviceInfo.host?.hostAddress
                        val port = serviceInfo.port
                        if (host != null) {
                            val url = "http://$host:$port"
                            
                            // Извлекаем UUID из TXT записей
                            val attributes = serviceInfo.attributes
                            val uuid = attributes["server_uuid"]?.let { String(it) }
                            val serverName = attributes["hostname"]?.let { String(it) } ?: serviceInfo.serviceName.removePrefix("MonitHome-")
                            
                            Log.d("PcDiscovery", "PC found: $serverName at $url (UUID: $uuid)")
                            trySend(DiscoveredServer(serverName, url, uuid))
                        }
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d("PcDiscovery", "Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(regType: String) {
                Log.d("PcDiscovery", "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e("PcDiscovery", "Start discovery failed: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e("PcDiscovery", "Stop discovery failed: $errorCode")
            }
        }

        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        
        awaitClose {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
