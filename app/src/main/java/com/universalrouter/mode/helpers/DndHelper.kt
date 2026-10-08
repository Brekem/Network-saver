package com.universalrouter.mode.helpers

import android.app.NotificationManager
import android.content.Context

class DndHelper(context: Context) {

    private val nm = requireNotNull(context.getSystemService(NotificationManager::class.java))

    val hasAccess: Boolean get() = nm.isNotificationPolicyAccessGranted

    val currentFilter: Int get() = nm.currentInterruptionFilter

    val isActive: Boolean
        get() = currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            currentFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN

    /** Activa No Molestar (modo prioridad). Requiere acceso concedido por el usuario. */
    fun enable(): Boolean = setFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

    fun setFilter(filter: Int): Boolean {
        if (!hasAccess) return false
        val safe = if (filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            NotificationManager.INTERRUPTION_FILTER_ALL
        } else filter
        return try {
            nm.setInterruptionFilter(safe)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
