package io.github.sw1313.backgroundmediaguard.xposed

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

class MediaProtectionRegistry(
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val sessions = ConcurrentHashMap<Any, Session>()
    private val lastActiveByPackage = ConcurrentHashMap<String, Long>()

    fun update(
        session: Any,
        packageName: String,
        uid: Int,
        active: Boolean,
    ) {
        val now = nowMillis()
        val wasActive = sessions[session]?.active == true
        sessions.compute(session) { _, old ->
            Session(
                packageName = packageName,
                uid = uid,
                active = active,
                lastActiveAt = when {
                    active -> now
                    old?.active == true -> now
                    else -> old?.lastActiveAt ?: now
                },
            )
        }
        if (packageName.isNotEmpty() && (active || wasActive)) {
            lastActiveByPackage[packageName] = now
        }
    }

    fun retainsActivity(packageName: String, recentMillis: Long): Boolean {
        if (packageName.isEmpty()) return false
        val now = nowMillis()
        if (sessions.values.any { session ->
                session.packageName == packageName &&
                    (session.active || now - session.lastActiveAt <= recentMillis)
            }
        ) {
            return true
        }
        val lastActiveAt = lastActiveByPackage[packageName] ?: return false
        return now - lastActiveAt <= recentMillis
    }

    fun remove(session: Any) {
        sessions.remove(session)
    }

    fun isProtected(
        packageNames: Set<String>,
        uid: Int?,
        graceMillis: Long,
    ): Boolean {
        if (packageNames.isEmpty() && uid == null) return false
        val now = nowMillis()
        var protected = false
        sessions.entries.removeIf { (_, value) ->
            val expired = !value.active && now - value.lastActiveAt > graceMillis
            if (!expired &&
                (value.packageName in packageNames || (uid != null && value.uid == uid))
            ) {
                protected = true
            }
            expired
        }
        return protected
    }

    internal fun sessionCount(): Int = sessions.size

    private data class Session(
        val packageName: String,
        val uid: Int,
        val active: Boolean,
        val lastActiveAt: Long,
    )
}
