package com.universalrouter.mode.cycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CycleStatus(
    val running: Boolean = false,
    val phase: String = "Detenido",
    val nextCycleAtMillis: Long = 0,
    val lastResult: String = "",
    val trafficKbps: Int = 0,
    val cyclesDone: Int = 0,
)

/** Estado compartido entre [NetworkCycleService] y la UI (mismo proceso). */
object NetworkCycleState {
    private val _status = MutableStateFlow(CycleStatus())
    val status: StateFlow<CycleStatus> = _status.asStateFlow()

    fun update(block: (CycleStatus) -> CycleStatus) = _status.update(block)
}
