package io.github.sw1313.backgroundmediaguard.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
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
    private lateinit var embyJsBridgeGroup: View
    private lateinit var embyJsBridge: MaterialSwitch
    private lateinit var grace: AutoCompleteTextView

    private var loading = false
    private var service: XposedService? = null
    private val isEmby get() = targetPackage == Settings.PACKAGE_EMBY
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
        embyJsBridgeGroup = findViewById(R.id.emby_js_bridge_group)
        embyJsBridge = findViewById(R.id.emby_js_bridge)
        grace = findViewById(R.id.grace)
        embyJsBridgeGroup.visibility = if (isEmby) View.VISIBLE else View.GONE
        grace.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, graceLabels),
        )
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
        embyJsBridge.setOnCheckedChangeListener { _, checked ->
            if (loading || !isEmby) return@setOnCheckedChangeListener
            editPrefs {
                putBoolean(Settings.appKey(Settings.KEY_EMBY_JS_BRIDGE, targetPackage), checked)
            }
            if (checked) requestAppScope()
        }
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
        )
        if (isEmby) controls += embyJsBridge
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
        if (isEmby) {
            embyJsBridge.isChecked = appBoolean(
                prefs,
                Settings.KEY_EMBY_JS_BRIDGE,
                Settings.DEFAULT_EMBY_JS_BRIDGE,
            )
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
