package com.emutools.emubridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EmulatorConfigsAdapter(
    private var configs: MutableList<EmulatorConfig>,
    private val onEdit: (EmulatorConfig) -> Unit,
    private val onDelete: (EmulatorConfig) -> Unit
) : RecyclerView.Adapter<EmulatorConfigsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameTextView: TextView = view.findViewById(R.id.tvConfigName)
        val directoryTextView: TextView = view.findViewById(R.id.tvConfigDirectory)
        val emulatorInfoTextView: TextView = view.findViewById(R.id.tvConfigEmulatorInfo)
        val editButton: Button = view.findViewById(R.id.btnEditConfig)
        val deleteButton: Button = view.findViewById(R.id.btnDeleteConfig)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_emulator_config, parent, false) // Create this layout
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = configs[position]
        holder.nameTextView.text = "Name: ${config.name}"
        // Extract a displayable name from the URI if possible, or just show the URI
        holder.directoryTextView.text = "Source Dir: ${config.sourceRomDirectoryUri.takeLast(30)}" // Show last part
        holder.emulatorInfoTextView.text = "Emulator: ${config.emulatorPackageName.takeLast(25)}"

        holder.editButton.setOnClickListener { onEdit(config) }
        holder.deleteButton.setOnClickListener { onDelete(config) }
    }

    override fun getItemCount() = configs.size

    fun updateData(newConfigs: List<EmulatorConfig>) {
        configs.clear()
        configs.addAll(newConfigs)
        notifyDataSetChanged()
    }
}
    