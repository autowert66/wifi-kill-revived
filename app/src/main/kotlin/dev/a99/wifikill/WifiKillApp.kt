package dev.a99.wifikill

import android.app.Application
import com.google.android.material.color.DynamicColors

class WifiKillApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
