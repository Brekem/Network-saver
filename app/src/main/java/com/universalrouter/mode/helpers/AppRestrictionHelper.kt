package com.universalrouter.mode.helpers

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings

data class InstalledApp(val packageName: String, val label: String, val isSystem: Boolean)

class AppRestrictionHelper(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    /**
     * Apps con icono en el launcher que se pueden restringir. Excluye esta app,
     * Ajustes y el launcher (restringirlos dejaría el teléfono inutilizable).
     */
    fun launchableApps(alsoInclude: Set<String> = emptySet()): List<InstalledApp> {
        val protected = protectedPackages()
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .toSet()
        // Las apps ya ocultas por Device Owner no aparecen en el launcher: se añaden aparte.
        return (launchable + alsoInclude)
            .filterNot { it in protected }
            .mapNotNull { pkg -> appInfo(pkg) }
            .sortedWith(compareBy<InstalledApp> { it.isSystem }.thenBy { it.label.lowercase() })
    }

    fun labelFor(pkg: String): String = appInfo(pkg)?.label ?: pkg

    private fun appInfo(pkg: String): InstalledApp? = try {
        val info = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        InstalledApp(
            packageName = pkg,
            label = pm.getApplicationLabel(info).toString(),
            isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        )
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun protectedPackages(): Set<String> {
        val set = mutableSetOf(context.packageName, "com.android.settings", "com.android.systemui")
        pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName?.let { set += it }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.queryIntentActivities(home, 0).forEach { set += it.activityInfo.packageName }
        return set
    }

    /**
     * Modo estándar: pide al sistema que finalice los procesos en segundo
     * plano de las apps seleccionadas (API pública, permiso normal).
     */
    fun killBackground(packages: Collection<String>): Int {
        val am = requireNotNull(context.getSystemService(ActivityManager::class.java))
        var count = 0
        packages.forEach { pkg ->
            try {
                am.killBackgroundProcesses(pkg)
                count++
            } catch (e: SecurityException) {
                // Ignorado: algunas ROMs protegen ciertos paquetes.
            }
        }
        return count
    }
}
