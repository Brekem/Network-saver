package com.universalrouter.mode.domain

import com.universalrouter.mode.helpers.SettingsTarget

/**
 * Acción que Android no permite automatizar y que requiere un toque del
 * usuario. La UI las ejecuta de una en una, abriendo la pantalla exacta.
 */
sealed class PendingAction(val id: String) {

    data class OpenSettings(
        val target: SettingsTarget,
        val guidance: String,
    ) : PendingAction("settings:$target")

    data class RequestPermission(val permission: String) : PendingAction("perm:$permission")

    /** Diálogo oficial del sistema para encender Bluetooth (BluetoothAdapter.ACTION_REQUEST_ENABLE). */
    data object RequestBluetoothEnable : PendingAction("bt:enable")
}

data class PassResult(
    /** Acciones que requieren al usuario, en el orden en que deben abrirse. */
    val pending: List<PendingAction>,
    /** Pasos aplicados automáticamente en esta pasada (para el registro de la UI). */
    val applied: List<String>,
    /** true si se debe entrar (o salir) del modo kiosco cuando no queden pendientes. */
    val kiosk: Boolean,
)
