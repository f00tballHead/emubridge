package com.emutools.emubridge // Or your actual package

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppPickerAdapter(
    private val appList: List<AppInfo>,
    private val onItemClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.AppViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_picker, parent, false) // Your existing item layout
        return AppViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val currentApp = appList[position]
        holder.bind(currentApp)
    }

    override fun getItemCount() = appList.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val appIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val appNameTextView: TextView = itemView.findViewById(R.id.tvAppName)

        fun bind(appInfo: AppInfo) {
            appNameTextView.text = appInfo.appName
            appIcon.setImageDrawable(appInfo.icon)

            itemView.setOnClickListener {
                onItemClick(appInfo)
            }
        }
    }
}

