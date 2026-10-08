package com.universalrouter.mode.ui

import com.universalrouter.mode.admin.PermissionLevel
import com.universalrouter.mode.data.DeviceOwnerOptions
import com.universalrouter.mode.helpers.SettingsTarget

enum class Indicator { ACTIVE, INCOMPLETE, INACTIVE }

enum class RowState { OK, FAIL, INFO }

data class StatusRow(
    val emoji: String,
    val title: String,
    val state: RowState,
    val detail: String,
    /** Pantalla a abrir si el usuario toca la fila (null = no accionable). */
    val fixTarget: SettingsTarget? = null,
)

data class RouterUiState(
    val deviceSummary: String = "",
    val permissionLevel: PermissionLevel = PermissionLevel.STANDARD,
    val indicator: Indicator = Indicator.INACTIVE,
    val routerActive: Boolean = false,
    val usbConnected: Boolean = false,
    val hotspotTitle: String = "",
    val hotspotDetail: String = "",
    val usbTetheringActive: Boolean = false,
    val rows: List<StatusRow> = emptyList(),
    val selectedAppsCount: Int = 0,
    val deviceOwnerOptions: DeviceOwnerOptions = DeviceOwnerOptions(),
    val busy: Boolean = false,
    val log: List<String> = emptyList(),
) {
    val isDeviceOwner get() = permissionLevel == PermissionLevel.DEVICE_OWNER
}
