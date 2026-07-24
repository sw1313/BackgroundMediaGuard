package io.github.sw1313.backgroundmediaguard.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.sw1313.backgroundmediaguard.R

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val isSystem: Boolean,
)

class AppListAdapter(
    private val onProtectionChanged: (String, Boolean) -> Unit,
    private val onConfigure: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.Holder>() {
    private var entries: List<AppEntry> = emptyList()
    private var selected: Set<String> = emptySet()

    fun submit(entries: List<AppEntry>, selected: Set<String>) {
        this.entries = entries
        this.selected = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return Holder(view as ViewGroup)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        holder.icon.setImageDrawable(entry.icon)
        holder.label.text = entry.label
        holder.packageName.text = entry.packageName
        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = entry.packageName in selected
        holder.mainRow.setOnClickListener { holder.toggle.performClick() }
        holder.settings.setOnClickListener { onConfigure(entry) }
        holder.toggle.setOnCheckedChangeListener { _, checked ->
            selected = if (checked) selected + entry.packageName else selected - entry.packageName
            onProtectionChanged(entry.packageName, checked)
        }
    }

    override fun getItemCount(): Int = entries.size

    class Holder(view: ViewGroup) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val label: TextView = view.findViewById(R.id.app_label)
        val packageName: TextView = view.findViewById(R.id.app_package)
        val mainRow: ViewGroup = view.findViewById(R.id.app_main_row)
        val settings: MaterialButton = view.findViewById(R.id.app_settings)
        val toggle: MaterialSwitch = view.findViewById(R.id.app_switch)
    }
}
