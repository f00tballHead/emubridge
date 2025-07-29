package com.emutools.emubridge // Or your actual package

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppInfo(
    val appName: CharSequence,
    val packageName: String,
    val activityName: String?, // Default launchable activity for the package
    val icon: Drawable?,
    val isRetroArch: Boolean = packageName.startsWith("com.retroarch") // Convenience flag
)

class AppPickerAdapter(
    private val appList: List<AppInfo>,
    private val onItemClick: (AppInfo) -> Unit // Callback function when an item is clicked
) : RecyclerView.Adapter<AppPickerAdapter.AppViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        // Inflate your custom layout for each item
        // Ensure you have a layout file named 'item_app_picker.xml' (or similar)
        // in your res/layout directory.
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_picker, parent, false)
        return AppViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val currentApp = appList[position]
        holder.bind(currentApp)
    }

    override fun getItemCount() = appList.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Get references to the views in your item_app_picker.xml
        private val appIconImageView: ImageView = itemView.findViewById(R.id.ivAppIcon) // Ensure this ID exists
        private val appNameTextView: TextView = itemView.findViewById(R.id.tvAppName)   // Ensure this ID exists
        // You could also add packageNameTextView if you want to display it in the list

        fun bind(appInfo: AppInfo) {
            appNameTextView.text = appInfo.appName
            if (appInfo.icon != null) {
                appIconImageView.setImageDrawable(appInfo.icon)
            } else {
                // Set a default icon if none is available
                appIconImageView.setImageResource(R.mipmap.ic_launcher) // Or any other placeholder
            }

            // Set the click listener for the whole item view
            itemView.setOnClickListener {
                onItemClick(appInfo) // Trigger the callback with the selected AppInfo
            }
        }
    }
}

