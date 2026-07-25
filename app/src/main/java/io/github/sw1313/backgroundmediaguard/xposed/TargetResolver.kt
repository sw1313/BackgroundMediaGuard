package io.github.sw1313.backgroundmediaguard.xposed

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import io.github.sw1313.backgroundmediaguard.Settings
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

class TargetResolver(
    context: Context,
    private val preferences: SharedPreferences,
) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val packageManager = context.packageManager
    private val index = TargetIndex(readTargets())
    private val processFields = ConcurrentHashMap<Class<*>, ProcessFields>()

    init {
        preferences.registerOnSharedPreferenceChangeListener(this)
    }

    val enabled: Boolean
        get() = preferences.getBoolean(Settings.KEY_ENABLED, Settings.DEFAULT_ENABLED)

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == Settings.KEY_TARGETS || key == null) {
            index.replaceTargets(readTargets())
        }
    }

    fun isSelectedUid(uid: Int): Boolean {
        return index.matchesUid(uid) {
            packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        }
    }

    fun audioHardeningEnabled(uid: Int): Boolean =
        featureEnabledForUid(
            uid,
            Settings.KEY_AUDIO_HARDENING,
            Settings.DEFAULT_AUDIO_HARDENING,
        )

    fun hyperOsZeroDataEnabled(uid: Int): Boolean =
        featureEnabledForUid(
            uid,
            Settings.KEY_HYPEROS_ZERO_DATA,
            Settings.DEFAULT_HYPEROS_ZERO_DATA,
        )

    fun freezerEnabled(processRecord: Any?): Boolean {
        if (!enabled) return false
        val identity = identityOf(processRecord) ?: return false
        return selectedPackages(identity).any {
            appBoolean(it, Settings.KEY_FREEZER, Settings.DEFAULT_FREEZER)
        }
    }

    fun shouldKeepControlLayer(packageName: String, uid: Int): Boolean =
        enabled &&
            packageName in readTargets() &&
            isSelectedUid(uid) &&
            appBoolean(packageName, Settings.KEY_CONTROL_KEEPALIVE, false)

    fun shouldUseJsBridge(packageName: String, uid: Int): Boolean =
        enabled &&
            packageName in readTargets() &&
            isSelectedUid(uid) &&
            appBoolean(
                packageName,
                Settings.KEY_JS_BRIDGE,
                Settings.defaultJsBridge(packageName),
            )

    fun plexPipKeepPlaying(packageName: String, uid: Int): Boolean =
        plexFeature(packageName, uid, Settings.KEY_PLEX_PIP_KEEP_PLAYING, Settings.DEFAULT_PLEX_PIP_KEEP_PLAYING)

    fun plexMediaNotificationFix(packageName: String, uid: Int): Boolean =
        plexFeature(
            packageName,
            uid,
            Settings.KEY_PLEX_MEDIA_NOTIFICATION,
            Settings.DEFAULT_PLEX_MEDIA_NOTIFICATION,
        )

    fun plexSurfaceRestore(packageName: String, uid: Int): Boolean =
        plexFeature(
            packageName,
            uid,
            Settings.KEY_PLEX_SURFACE_RESTORE,
            Settings.DEFAULT_PLEX_SURFACE_RESTORE,
        )

    fun plexPreventRestart(packageName: String, uid: Int): Boolean =
        plexFeature(
            packageName,
            uid,
            Settings.KEY_PLEX_PREVENT_RESTART,
            Settings.DEFAULT_PLEX_PREVENT_RESTART,
        )

    private fun plexFeature(
        packageName: String,
        uid: Int,
        key: String,
        defaultValue: Boolean,
    ): Boolean =
        enabled &&
            Settings.isPlexPackage(packageName) &&
            packageName in readTargets() &&
            isSelectedUid(uid) &&
            appBoolean(packageName, key, defaultValue)

    fun isSelectedProcess(processRecord: Any?): Boolean {
        val identity = identityOf(processRecord) ?: return false
        return isSelectedIdentity(identity)
    }

    fun identityOf(processRecord: Any?): ProcessIdentity? {
        if (processRecord == null) return null
        val fields = processFields.computeIfAbsent(processRecord.javaClass, ::findProcessFields)
        val uid = fields.uid?.getIntSafely(processRecord) ?: return null
        val appInfo = fields.info?.getSafely(processRecord) as? ApplicationInfo
        val packageName = appInfo?.packageName
            ?: fields.processName?.getSafely(processRecord)?.toString()?.substringBefore(':')

        if (packageName != null) index.remember(uid, packageName)
        return ProcessIdentity(uid, packageName)
    }

    fun rememberProcess(processRecord: Any?) {
        identityOf(processRecord)
    }

    fun shouldProtectProcess(
        processRecord: Any?,
        registry: MediaProtectionRegistry,
    ): Boolean {
        if (!enabled) return false
        val identity = identityOf(processRecord) ?: return false
        if (!isSelectedIdentity(identity)) return false
        val packages = selectedPackages(identity)
        if (packages.any {
                appBoolean(it, Settings.KEY_ALWAYS_PROTECT, Settings.DEFAULT_ALWAYS_PROTECT)
            }
        ) return true
        return registry.isProtected(
            packageNames = packages,
            uid = identity.uid,
            graceMillis = packages.maxOfOrNull(::graceMillis) ?: 0L,
        )
    }

    fun shouldProtectUid(uid: Int, registry: MediaProtectionRegistry): Boolean {
        if (!enabled || !isSelectedUid(uid)) return false
        val packages = selectedPackages(uid)
        if (packages.any {
                appBoolean(it, Settings.KEY_ALWAYS_PROTECT, Settings.DEFAULT_ALWAYS_PROTECT)
            }
        ) return true
        return registry.isProtected(
            packages,
            uid,
            packages.maxOfOrNull(::graceMillis) ?: 0L,
        )
    }

    fun shouldProtectPackage(
        packageName: String,
        uid: Int,
        registry: MediaProtectionRegistry,
    ): Boolean {
        if (!enabled || packageName !in readTargets()) return false
        index.remember(uid, packageName)
        if (appBoolean(packageName, Settings.KEY_ALWAYS_PROTECT, Settings.DEFAULT_ALWAYS_PROTECT)) {
            return true
        }
        return registry.isProtected(
            packageNames = setOf(packageName),
            uid = uid,
            graceMillis = graceMillis(packageName),
        )
    }

    private fun featureEnabledForUid(uid: Int, key: String, defaultValue: Boolean): Boolean {
        if (!enabled) return false
        return selectedPackages(uid).any { appBoolean(it, key, defaultValue) }
    }

    private fun selectedPackages(identity: ProcessIdentity): Set<String> {
        val direct = identity.packageName?.takeIf { it in readTargets() }
        return if (direct != null) setOf(direct) else selectedPackages(identity.uid)
    }

    private fun selectedPackages(uid: Int): Set<String> {
        val targets = readTargets()
        return index.packagesForUid(uid) {
            packageManager.getPackagesForUid(it)?.toSet().orEmpty()
        }.filterTo(mutableSetOf()) { it in targets }
    }

    private fun appBoolean(packageName: String, key: String, defaultValue: Boolean): Boolean {
        val appKey = Settings.appKey(key, packageName)
        return if (preferences.contains(appKey)) {
            preferences.getBoolean(appKey, defaultValue)
        } else {
            preferences.getBoolean(key, defaultValue)
        }
    }

    private fun graceMillis(packageName: String): Long {
        val appKey = Settings.appKey(Settings.KEY_GRACE_SECONDS, packageName)
        val seconds = if (preferences.contains(appKey)) {
            preferences.getInt(appKey, Settings.DEFAULT_GRACE_SECONDS)
        } else {
            preferences.getInt(Settings.KEY_GRACE_SECONDS, Settings.DEFAULT_GRACE_SECONDS)
        }
        return seconds.coerceIn(0, 3600) * 1000L
    }

    private fun isSelectedIdentity(identity: ProcessIdentity): Boolean =
        index.matchesPackage(identity.packageName) || isSelectedUid(identity.uid)

    internal fun replaceTargetsForTest(values: Set<String>) {
        index.replaceTargets(values)
    }

    private fun readTargets(): Set<String> =
        preferences.getStringSet(Settings.KEY_TARGETS, emptySet())?.toSet().orEmpty()

    private fun findProcessFields(clazz: Class<*>): ProcessFields = ProcessFields(
        uid = findField(clazz, "uid", "mUid"),
        info = findField(clazz, "info", "mInfo"),
        processName = findField(clazz, "processName", "mProcessName"),
    )

    private fun findField(clazz: Class<*>, vararg names: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            val type = current
            for (name in names) {
                runCatching { type.getDeclaredField(name) }.getOrNull()?.let {
                    it.isAccessible = true
                    return it
                }
            }
            current = type.superclass
        }
        return null
    }

    private fun Field.getSafely(receiver: Any): Any? = runCatching { get(receiver) }.getOrNull()
    private fun Field.getIntSafely(receiver: Any): Int? = runCatching { getInt(receiver) }.getOrNull()

    private data class ProcessFields(
        val uid: Field?,
        val info: Field?,
        val processName: Field?,
    )

    data class ProcessIdentity(
        val uid: Int,
        val packageName: String?,
    )
}
