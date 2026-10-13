package com.universalrouter.mode.cycle

import android.content.Context
import androidx.core.content.edit

data class CycleConfig(
    /** Minutos en 5G entre cada refresco. */
    val intervalMinutes: Int = 30,
    /** Segundos que se mantiene en 4G. */
    val durationSeconds: Int = 5,
    /** No cambiar mientras haya tráfico (p. ej. jugando en el PC por hotspot). */
    val protectGaming: Boolean = true,
    /** Tráfico por encima del cual se considera que se está jugando (KB/s). */
    val busyThresholdKbps: Int = 15,
)

class CycleSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("network_cycle", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit { putBoolean("enabled", v) }

    var config: CycleConfig
        get() {
            val d = CycleConfig()
            return CycleConfig(
                intervalMinutes = prefs.getInt("interval", d.intervalMinutes),
                durationSeconds = prefs.getInt("duration", d.durationSeconds),
                protectGaming = prefs.getBoolean("protect", d.protectGaming),
                busyThresholdKbps = prefs.getInt("threshold", d.busyThresholdKbps),
            )
        }
        set(v) = prefs.edit {
            putInt("interval", v.intervalMinutes)
            putInt("duration", v.durationSeconds)
            putBoolean("protect", v.protectGaming)
            putInt("threshold", v.busyThresholdKbps)
        }

    /**
     * Tipos de red originales mientras el teléfono está en 4G. Si la app se
     * cierra a mitad de ciclo, al volver se restauran (nunca se queda en 4G).
     */
    var pendingRestore: Long?
        get() = if (prefs.contains("restore")) prefs.getLong("restore", 0) else null
        set(v) = prefs.edit { if (v == null) remove("restore") else putLong("restore", v) }

    var pendingRestoreSubId: Int
        get() = prefs.getInt("restore_sub", -1)
        set(v) = prefs.edit { putInt("restore_sub", v) }
}
