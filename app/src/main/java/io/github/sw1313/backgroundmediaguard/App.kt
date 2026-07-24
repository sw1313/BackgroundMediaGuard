package io.github.sw1313.backgroundmediaguard

import android.app.Application
import com.google.android.material.color.DynamicColors
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class App : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        Companion.service = service
        listeners.forEach { it(service) }
    }

    override fun onServiceDied(service: XposedService) {
        if (Companion.service === service) {
            Companion.service = null
            listeners.forEach { it(null) }
        }
    }

    companion object {
        @Volatile
        var service: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

        fun addServiceListener(listener: (XposedService?) -> Unit, notifyNow: Boolean = true) {
            listeners += listener
            if (notifyNow) listener(service)
        }

        fun removeServiceListener(listener: (XposedService?) -> Unit) {
            listeners -= listener
        }
    }
}
