package com.universalrouter.mode.reverse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ReverseTetherStatus(
    val running: Boolean = false,
    val phase: String = "Detenido",
    val networkName: String = "",
    val passphrase: String = "",
    val proxyAddress: String = "",
    val wifiClients: Int = 0,
    val tcpConnections: Int = 0,
    val udpSessions: Int = 0,
    val downKbps: Int = 0,
    val upKbps: Int = 0,
    val totalMb: Long = 0,
    val error: String = "",
)

object ReverseTetherState {
    private val _status = MutableStateFlow(ReverseTetherStatus())
    val status: StateFlow<ReverseTetherStatus> = _status.asStateFlow()

    val isRunning: Boolean get() = _status.value.running

    fun update(block: (ReverseTetherStatus) -> ReverseTetherStatus) = _status.update(block)
}
