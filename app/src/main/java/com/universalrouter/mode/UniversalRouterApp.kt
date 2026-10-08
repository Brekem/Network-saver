package com.universalrouter.mode

import android.app.Application
import com.google.android.material.color.DynamicColors

class UniversalRouterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You (Android 12+). En Android 11 se usa la paleta propia.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
