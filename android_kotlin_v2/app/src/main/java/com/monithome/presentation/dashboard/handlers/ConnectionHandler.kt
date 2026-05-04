package com.monithome.presentation.dashboard.handlers

import com.monithome.data.network.socket.DiscoveredServer
import com.monithome.data.network.socket.PcDiscovery
import com.monithome.data.network.socket.PcSocketClient
import com.monithome.data.network.socket.SocketConnectionState
import com.monithome.presentation.dashboard.DashboardState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConnectionHandler(
    private val socketClient: PcSocketClient,
    private val pcDiscovery: PcDiscovery
) {
    fun observeConnectionState(): Flow<SocketConnectionState> = socketClient.connectionState

    fun observeDiscovery(): Flow<DiscoveredServer> = pcDiscovery.discoverServers()

    fun updateWithConnectionState(s: DashboardState, connState: SocketConnectionState): DashboardState {
        return s.copy(
            isConnected = connState is SocketConnectionState.Connected,
            pcError = (connState as? SocketConnectionState.Error)?.message,
            isLoading = connState is SocketConnectionState.Connecting
        )
    }
}
