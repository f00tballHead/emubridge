package com.emutools.emubridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox // Or use a MaterialSwitch
import android.widget.TextView
import androidx.compose.ui.layout.layout
import androidx.recyclerview.widget.RecyclerView

class IntentFlagPickerAdapter(
    private val flags: MutableList<IntentFlagItem>
) : RecyclerView.Adapter<IntentFlagPickerAdapter.FlagViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FlagViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_intent_flag_selectable, parent, false) // Create this layout
        return FlagViewHolder(view)
    }

    override fun onBindViewHolder(holder: FlagViewHolder, position: Int) {
        val flagItem = flags[position]
        holder.bind(flagItem)
    }

    override fun getItemCount(): Int = flags.size

    fun getSelectedFlags(): List<IntentFlagItem> {
        return flags.filter { it.isSelected }
    }

    fun getAllFlags(): List<IntentFlagItem> {
        return flags
    }


    inner class FlagViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvFlagName: TextView = itemView.findViewById(R.id.tvFlagName)
        private val cbFlagSelected: CheckBox = itemView.findViewById(R.id.cbFlagSelected)

        fun bind(flagItem: IntentFlagItem) {
            tvFlagName.text = flagItem.name
            cbFlagSelected.isChecked = flagItem.isSelected

            // Update the isSelected state in the list when checkbox changes
            cbFlagSelected.setOnCheckedChangeListener { _, isChecked ->
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    flags[adapterPosition].isSelected = isChecked
                }
            }
            // Also handle clicks on the whole item row to toggle the checkbox
            itemView.setOnClickListener {
                cbFlagSelected.isChecked = !cbFlagSelected.isChecked
                // The listener on cbFlagSelected will update the model
            }
        }
    }
}
