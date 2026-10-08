package com.universalrouter.mode.helpers

import android.content.Context
import android.provider.Settings

class BrightnessHelper(private val context: Context) {

    private val resolver get() = context.contentResolver

    val canWrite: Boolean get() = Settings.System.canWrite(context)

    /** Brillo en la escala pública 0-255 de Settings.System. */
    val current: Int
        get() = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, MAX)

    val mode: Int
        get() = Settings.System.getInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )

    val screenOffTimeout: Int
        get() = Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 30_000)

    val isLow: Boolean
        get() = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL && current <= TARGET + 1

    val percent: Int get() = (current * 100f / MAX).toInt().coerceIn(0, 100)

    /** Pone el brillo en manual al 10%. Requiere WRITE_SETTINGS. */
    fun setLow(): Boolean = set(TARGET, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)

    fun set(value: Int, mode: Int): Boolean {
        if (!canWrite) return false
        return try {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(1, MAX))
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    companion object {
        const val MAX = 255
        /** 10% de 255. */
        const val TARGET = 26
    }
}
