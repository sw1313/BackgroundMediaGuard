package io.github.sw1313.backgroundmediaguard.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.color.MaterialColors
import io.github.libxposed.service.XposedService
import io.github.sw1313.backgroundmediaguard.App
import io.github.sw1313.backgroundmediaguard.R
import io.github.sw1313.backgroundmediaguard.Settings
import java.util.Locale
import kotlin.concurrent.thread

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var settingsCard: MaterialCardView
    private lateinit var enabled: MaterialSwitch
    private lateinit var showSystem: MaterialSwitch
    private lateinit var search: TextInputEditText
    private lateinit var count: TextView
    private lateinit var emptyState: TextView
    private lateinit var adapter: AppListAdapter

    private var allApps: List<AppEntry> = emptyList()
    private var applicationsLoaded = false
    private var applicationsLoadFailed = false
    private var selected = emptySet<String>()
    private var loadingSettings = false
    private var service: XposedService? = null
    private val serviceListener: (XposedService?) -> Unit = { value ->
        runOnUiThread { bindService(value) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        wireUi()
        loadApplications()
    }

    override fun onStart() {
        super.onStart()
        App.addServiceListener(serviceListener)
    }

    override fun onStop() {
        App.removeServiceListener(serviceListener)
        super.onStop()
    }

    private fun bindViews() {
        statusCard = findViewById(R.id.status_card)
        statusDot = findViewById(R.id.status_dot)
        statusTitle = findViewById(R.id.status_title)
        statusDetail = findViewById(R.id.status_detail)
        settingsCard = findViewById(R.id.settings_card)
        enabled = findViewById(R.id.enabled)
        showSystem = findViewById(R.id.show_system)
        search = findViewById(R.id.search)
        count = findViewById(R.id.count)
        emptyState = findViewById(R.id.empty_state)

        val recycler = findViewById<RecyclerView>(R.id.app_list)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = AppListAdapter(
            onProtectionChanged = { packageName, checked ->
                selected = if (checked) selected + packageName else selected - packageName
                persistTargets()
                updateCount()
                filterApps()
            },
            onConfigure =(::openAppSettings),
        )
        recycler.adapter = adapter
    }

    private fun wireUi() {
        enabled.setOnCheckedChangeListener { _, value ->
            if (!loadingSettings) editPrefs { putBoolean(Settings.KEY_ENABLED, value) }
        }
        findViewById<TextView>(R.id.settings_toggle).setOnClickListener { toggle ->
            val expanding = settingsCard.visibility != View.VISIBLE
            settingsCard.visibility = if (expanding) View.VISIBLE else View.GONE
            (toggle as TextView).setText(
                if (expanding) R.string.hide_protection_settings
                else R.string.show_protection_settings,
            )
        }
        findViewById<View>(R.id.restart_scope).setOnClickListener {
            confirmRestartScope()
        }
        showSystem.setOnCheckedChangeListener { _, _ -> filterApps() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filterApps()
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun bindService(value: XposedService?) {
        service = value
        val connected = value != null
        val containerAttr: Int
        val contentAttr: Int
        val dotAttr: Int
        if (connected) {
            statusTitle.setText(R.string.status_connected)
            statusDetail.text =
                "${value!!.frameworkName} ${value.frameworkVersion} · API ${value.apiVersion}"
            containerAttr = MaterialR.attr.colorPrimaryContainer
            contentAttr = MaterialR.attr.colorOnPrimaryContainer
            dotAttr = MaterialR.attr.colorPrimary
        } else {
            statusTitle.setText(R.string.status_disconnected)
            statusDetail.setText(R.string.status_disconnected_detail)
            containerAttr = MaterialR.attr.colorErrorContainer
            contentAttr = MaterialR.attr.colorOnErrorContainer
            dotAttr = MaterialR.attr.colorError
        }
        statusCard.setCardBackgroundColor(MaterialColors.getColor(statusCard, containerAttr))
        val contentColor = MaterialColors.getColor(statusCard, contentAttr)
        statusTitle.setTextColor(contentColor)
        statusDetail.setTextColor(contentColor)
        statusDot.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.getColor(statusDot, dotAttr),
        )
        enabled.isEnabled = connected
        if (!connected) return

        val prefs = value!!.getRemotePreferences(Settings.GROUP)
        loadingSettings = true
        enabled.isChecked = prefs.getBoolean(Settings.KEY_ENABLED, Settings.DEFAULT_ENABLED)
        selected = prefs.getStringSet(Settings.KEY_TARGETS, emptySet())?.toSet().orEmpty()
        loadingSettings = false
        filterApps()
        updateCount()
    }

    private fun loadApplications() {
        thread(name = "package-loader") {
            val result = runCatching {
                queryInstalledApplications().asSequence()
                    .filter { it.packageName != packageName }
                    .mapNotNull { info ->
                        runCatching {
                            AppEntry(
                                label = packageManager.getApplicationLabel(info).toString(),
                                packageName = info.packageName,
                                icon = packageManager.getApplicationIcon(info),
                                isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                            )
                        }.getOrNull()
                    }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                    .toList()
            }
            runOnUiThread {
                applicationsLoaded = true
                applicationsLoadFailed = result.isFailure
                allApps = result.getOrDefault(emptyList())
                filterApps()
            }
        }
    }

    private fun queryInstalledApplications(): List<ApplicationInfo> {
        val direct = runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 33) {
                getInstalledApplications33()
            } else {
                packageManager.getInstalledApplications(applicationQueryFlags())
            }
        }.getOrDefault(emptyList())
        val launcher = runCatching {
            getSystemService(LauncherApps::class.java)
                .getActivityList(null, Process.myUserHandle())
                .map { it.applicationInfo }
        }.getOrDefault(emptyList())
        return (direct + launcher).associateBy { it.packageName }.values.toList()
    }

    @Suppress("DEPRECATION")
    private fun applicationQueryFlags(): Int =
        PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_ALL

    @RequiresApi(33)
    private fun getInstalledApplications33(): List<ApplicationInfo> =
        packageManager.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(
                applicationQueryFlags().toLong(),
            ),
        )

    private fun filterApps() {
        if (!::adapter.isInitialized) return
        val query = search.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val visible = allApps
            .filter {
                (showSystem.isChecked || !it.isSystem) &&
                    (query.isEmpty() ||
                        it.label.lowercase(Locale.ROOT).contains(query) ||
                        it.packageName.lowercase(Locale.ROOT).contains(query))
            }
            .sortedWith(
                compareByDescending<AppEntry> { it.packageName in selected }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
            )
        adapter.submit(visible, selected)
        emptyState.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        emptyState.setText(
            when {
                !applicationsLoaded -> R.string.apps_loading
                applicationsLoadFailed -> R.string.apps_load_failed
                else -> R.string.apps_empty
            },
        )
    }

    private fun persistTargets() {
        editPrefs { putStringSet(Settings.KEY_TARGETS, selected) }
    }

    private fun openAppSettings(entry: AppEntry) {
        startActivity(
            Intent(this, AppSettingsActivity::class.java)
                .putExtra(AppSettingsActivity.EXTRA_PACKAGE_NAME, entry.packageName)
                .putExtra(AppSettingsActivity.EXTRA_APP_LABEL, entry.label),
        )
    }

    private fun editPrefs(block: android.content.SharedPreferences.Editor.() -> Unit) {
        service?.getRemotePreferences(Settings.GROUP)?.edit()?.apply {
            block()
            apply()
        }
    }

    private fun updateCount() {
        count.text = getString(R.string.selected_count, selected.size)
    }

    private fun confirmRestartScope() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restart_scope_title)
            .setMessage(R.string.restart_scope_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.restart_scope_confirm) { _, _ ->
                restartSystemServer()
            }
            .show()
    }

    private fun restartSystemServer() {
        Toast.makeText(this, R.string.restart_scope_started, Toast.LENGTH_SHORT).show()
        thread(name = "restart-system-scope") {
            val succeeded = runCatching {
                val command =
                    "pid=\$(pidof system_server); [ -n \"\$pid\" ] && kill -9 \$pid"
                ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)
            if (!succeeded) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        R.string.restart_scope_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
}
