package io.github.sw1313.backgroundmediaguard.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.libxposed.service.XposedService
import io.github.sw1313.backgroundmediaguard.App
import io.github.sw1313.backgroundmediaguard.R
import io.github.sw1313.backgroundmediaguard.Settings

class AppSettingsActivity : AppCompatActivity() {
    private lateinit var targetPackage: String
    private lateinit var appEnabled: MaterialSwitch
    private lateinit var audioHardening: MaterialSwitch
    private lateinit var hyperOsZeroData: MaterialSwitch
    private lateinit var freezer: MaterialSwitch
    private lateinit var alwaysProtect: MaterialSwitch
    private lateinit var controlKeepAlive: MaterialSwitch
    private lateinit var jsBridge: MaterialSwitch
    private lateinit var plexSection: LinearLayout
    private lateinit var plexPipKeepPlaying: MaterialSwitch
    private lateinit var plexMediaNotification: MaterialSwitch
    private lateinit var plexSurfaceRestore: MaterialSwitch
    private lateinit var plexPreventRestart: MaterialSwitch
    private lateinit var plexCodecErrorGuard: MaterialSwitch
    private lateinit var jellyfinSection: LinearLayout
    private lateinit var jellyfinPlayerRestore: MaterialSwitch
    private lateinit var grace: AutoCompleteTextView

    private var loading = false
    private var service: XposedService? = null
    private val graceValues = intArrayOf(30, 60, 120, 300)
    private val graceLabels = listOf("30 秒", "60 秒", "120 秒（推荐）", "5 分钟")
    private val serviceListener: (XposedService?) -> Unit = { value ->
        runOnUiThread { bindService(value) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: run {
            finish()
            return
        }
        setContentView(R.layout.activity_app_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<TextView>(R.id.app_label).text =
            intent.getStringExtra(EXTRA_APP_LABEL) ?: targetPackage
        findViewById<TextView>(R.id.app_package).text = targetPackage
        runCatching { packageManager.getApplicationIcon(targetPackage) }
            .onSuccess { findViewById<ImageView>(R.id.app_icon).setImageDrawable(it) }

        appEnabled = findViewById(R.id.app_enabled)
        audioHardening = findViewById(R.id.audio_hardening)
        hyperOsZeroData = findViewById(R.id.hyperos_zero_data)
        freezer = findViewById(R.id.freezer)
        alwaysProtect = findViewById(R.id.always_protect)
        controlKeepAlive = findViewById(R.id.control_keepalive)
        jsBridge = findViewById(R.id.emby_js_bridge)
        plexSection = findViewById(R.id.plex_compat_section)
        plexPipKeepPlaying = findViewById(R.id.plex_pip_keep_playing)
        plexMediaNotification = findViewById(R.id.plex_media_notification)
        plexSurfaceRestore = findViewById(R.id.plex_surface_restore)
        plexPreventRestart = findViewById(R.id.plex_prevent_restart)
        plexCodecErrorGuard = findViewById(R.id.plex_codec_error_guard)
        jellyfinSection = findViewById(R.id.jellyfin_compat_section)
        jellyfinPlayerRestore = findViewById(R.id.jellyfin_player_restore)
        grace = findViewById(R.id.grace)
        grace.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, graceLabels),
        )
        plexSection.visibility =
            if (Settings.isPlexPackage(targetPackage)) View.VISIBLE else View.GONE
        jellyfinSection.visibility =
            if (Settings.isJellyfinPackage(targetPackage)) View.VISIBLE else View.GONE
        wireUi()
    }

    override fun onStart() {
        super.onStart()
        App.addServiceListener(serviceListener)
    }

    override fun onStop() {
        App.removeServiceListener(serviceListener)
        super.onStop()
    }

    private fun wireUi() {
        appEnabled.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            editPrefs {
                val targets = service?.getRemotePreferences(Settings.GROUP)
                    ?.getStringSet(Settings.KEY_TARGETS, emptySet())
                    ?.toMutableSet()
                    ?: mutableSetOf()
                if (checked) targets += targetPackage else targets -= targetPackage
                putStringSet(Settings.KEY_TARGETS, targets)
            }
        }
        bindSwitch(
            audioHardening,
            Settings.KEY_AUDIO_HARDENING,
        )
        bindSwitch(
            hyperOsZeroData,
            Settings.KEY_HYPEROS_ZERO_DATA,
        )
        bindSwitch(freezer, Settings.KEY_FREEZER)
        bindSwitch(alwaysProtect, Settings.KEY_ALWAYS_PROTECT)
        controlKeepAlive.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            editPrefs {
                putBoolean(Settings.appKey(Settings.KEY_CONTROL_KEEPALIVE, targetPackage), checked)
            }
            if (checked) requestAppScope()
        }
        jsBridge.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            editPrefs {
                putBoolean(Settings.appKey(Settings.KEY_JS_BRIDGE, targetPackage), checked)
            }
            if (checked) requestAppScope()
        }
        bindPlexSwitch(plexPipKeepPlaying, Settings.KEY_PLEX_PIP_KEEP_PLAYING)
        bindPlexSwitch(plexMediaNotification, Settings.KEY_PLEX_MEDIA_NOTIFICATION)
        bindPlexSwitch(plexSurfaceRestore, Settings.KEY_PLEX_SURFACE_RESTORE)
        bindPlexSwitch(plexPreventRestart, Settings.KEY_PLEX_PREVENT_RESTART)
        bindPlexSwitch(plexCodecErrorGuard, Settings.KEY_PLEX_CODEC_ERROR_GUARD)
        bindPlexSwitch(jellyfinPlayerRestore, Settings.KEY_JELLYFIN_PLAYER_RESTORE)
        grace.setOnItemClickListener { _, _, position, _ ->
            if (!loading) {
                editPrefs {
                    putInt(
                        Settings.appKey(Settings.KEY_GRACE_SECONDS, targetPackage),
                        graceValues[position],
                    )
                }
            }
        }
    }

    private fun bindSwitch(view: MaterialSwitch, key: String) {
        view.setOnCheckedChangeListener { _, checked ->
            if (!loading) {
                editPrefs { putBoolean(Settings.appKey(key, targetPackage), checked) }
            }
        }
    }

    private fun bindPlexSwitch(view: MaterialSwitch, key: String) {
        view.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            editPrefs { putBoolean(Settings.appKey(key, targetPackage), checked) }
            if (checked) requestAppScope()
        }
    }

    private fun requestAppScope() {
        val xp = service ?: return
        val already = runCatching { xp.scope }.getOrNull().orEmpty()
        if (targetPackage in already) return
        runCatching {
            xp.requestScope(
                listOf(targetPackage),
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(packages: List<String>) {
                        runOnUiThread {
                            Toast.makeText(
                                this@AppSettingsActivity,
                                R.string.scope_request_approved,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }

                    override fun onScopeRequestFailed(message: String) {
                        runOnUiThread {
                            Toast.makeText(
                                this@AppSettingsActivity,
                                getString(R.string.scope_request_failed, message),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
            )
            Toast.makeText(this, R.string.scope_request_prompted, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(
                this,
                getString(R.string.scope_request_failed, it.message ?: "unknown"),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun bindService(value: XposedService?) {
        service = value
        val controls = mutableListOf(
            appEnabled,
            audioHardening,
            hyperOsZeroData,
            freezer,
            alwaysProtect,
            controlKeepAlive,
            jsBridge,
        )
        if (Settings.isPlexPackage(targetPackage)) {
            controls += listOf(
                plexPipKeepPlaying,
                plexMediaNotification,
                plexSurfaceRestore,
                plexPreventRestart,
                plexCodecErrorGuard,
            )
        }
        if (Settings.isJellyfinPackage(targetPackage)) {
            controls += jellyfinPlayerRestore
        }
        controls.forEach { it.isEnabled = value != null }
        grace.isEnabled = value != null
        if (value == null) return

        val prefs = value.getRemotePreferences(Settings.GROUP)
        loading = true
        appEnabled.isChecked =
            targetPackage in prefs.getStringSet(Settings.KEY_TARGETS, emptySet()).orEmpty()
        audioHardening.isChecked = appBoolean(
            prefs,
            Settings.KEY_AUDIO_HARDENING,
            Settings.DEFAULT_AUDIO_HARDENING,
        )
        hyperOsZeroData.isChecked = appBoolean(
            prefs,
            Settings.KEY_HYPEROS_ZERO_DATA,
            Settings.DEFAULT_HYPEROS_ZERO_DATA,
        )
        freezer.isChecked = appBoolean(
            prefs,
            Settings.KEY_FREEZER,
            Settings.DEFAULT_FREEZER,
        )
        alwaysProtect.isChecked = appBoolean(
            prefs,
            Settings.KEY_ALWAYS_PROTECT,
            Settings.DEFAULT_ALWAYS_PROTECT,
        )
        controlKeepAlive.isChecked = appBoolean(
            prefs,
            Settings.KEY_CONTROL_KEEPALIVE,
            false,
        )
        jsBridge.isChecked = appBoolean(
            prefs,
            Settings.KEY_JS_BRIDGE,
            Settings.defaultJsBridge(targetPackage),
        )
        if (Settings.isPlexPackage(targetPackage)) {
            plexPipKeepPlaying.isChecked = appBoolean(
                prefs,
                Settings.KEY_PLEX_PIP_KEEP_PLAYING,
                Settings.DEFAULT_PLEX_PIP_KEEP_PLAYING,
            )
            plexMediaNotification.isChecked = appBoolean(
                prefs,
                Settings.KEY_PLEX_MEDIA_NOTIFICATION,
                Settings.DEFAULT_PLEX_MEDIA_NOTIFICATION,
            )
            plexSurfaceRestore.isChecked = appBoolean(
                prefs,
                Settings.KEY_PLEX_SURFACE_RESTORE,
                Settings.DEFAULT_PLEX_SURFACE_RESTORE,
            )
            plexPreventRestart.isChecked = appBoolean(
                prefs,
                Settings.KEY_PLEX_PREVENT_RESTART,
                Settings.DEFAULT_PLEX_PREVENT_RESTART,
            )
            plexCodecErrorGuard.isChecked = appBoolean(
                prefs,
                Settings.KEY_PLEX_CODEC_ERROR_GUARD,
                Settings.DEFAULT_PLEX_CODEC_ERROR_GUARD,
            )
        }
        if (Settings.isJellyfinPackage(targetPackage)) {
            jellyfinPlayerRestore.isChecked = appBoolean(
                prefs,
                Settings.KEY_JELLYFIN_PLAYER_RESTORE,
                Settings.DEFAULT_JELLYFIN_PLAYER_RESTORE,
            )
            if (jellyfinPlayerRestore.isChecked) requestAppScope()
        }
        val graceSeconds = appInt(
            prefs,
            Settings.KEY_GRACE_SECONDS,
            Settings.DEFAULT_GRACE_SECONDS,
        )
        val graceIndex = graceValues.indexOf(graceSeconds).takeIf { it >= 0 } ?: 2
        grace.setText(graceLabels[graceIndex], false)
        loading = false
    }

    private fun appBoolean(
        prefs: android.content.SharedPreferences,
        key: String,
        defaultValue: Boolean,
    ): Boolean {
        val appKey = Settings.appKey(key, targetPackage)
        return if (prefs.contains(appKey)) {
            prefs.getBoolean(appKey, defaultValue)
        } else {
            prefs.getBoolean(key, defaultValue)
        }
    }

    private fun appInt(
        prefs: android.content.SharedPreferences,
        key: String,
        defaultValue: Int,
    ): Int {
        val appKey = Settings.appKey(key, targetPackage)
        return if (prefs.contains(appKey)) {
            prefs.getInt(appKey, defaultValue)
        } else {
            prefs.getInt(key, defaultValue)
        }
    }

    private fun editPrefs(block: android.content.SharedPreferences.Editor.() -> Unit) {
        service?.getRemotePreferences(Settings.GROUP)?.edit()?.apply {
            block()
            apply()
        }
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_APP_LABEL = "app_label"
    }
}
