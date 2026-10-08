package com.universalrouter.mode.helpers

import android.content.Context
import android.os.PowerManager

/**
 * Android no permite a apps normales (ni a Device Owner) activar el ahorro
 * de energía: solo se puede leer el estado y abrir la pantalla correcta.
 */
class PowerSaverHelper(context: Context) {
    private val pm = requireNotNull(context.getSystemService(PowerManager::class.java))

    val isPowerSaveMode: Boolean get() = pm.isPowerSaveMode
}
