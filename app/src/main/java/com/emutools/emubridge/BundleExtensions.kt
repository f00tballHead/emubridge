package com.emutools.emubridge // Or your project's appropriate package

import android.os.Build
import android.os.Bundle
import android.os.Parcelable

// Extension function to convert a Bundle to a Map<String, Any?>
fun Bundle.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    val keySet = this.keySet() // Get all keys

    for (key in keySet) {
        // map[key] = this.get(key) // This was in your original code
        // A more robust way to handle different types, especially for JSON serialization:
        when (val value = this.get(key)) {
            is Bundle -> map[key] = value.toMap() // Recursively convert nested Bundles
            is Byte -> map[key] = value
            is Char -> map[key] = value
            is Short -> map[key] = value
            is Int -> map[key] = value
            is Long -> map[key] = value
            is Float -> map[key] = value
            is Double -> map[key] = value
            is String -> map[key] = value
            is Boolean -> map[key] = value
            is Parcelable -> map[key] = value.toString() // Or handle specific Parcelables differently
            is ByteArray -> map[key] = value
            is ShortArray -> map[key] = value
            is CharArray -> map[key] = value
            is IntArray -> map[key] = value
            is LongArray -> map[key] = value
            is FloatArray -> map[key] = value
            is DoubleArray -> map[key] = value
            is BooleanArray -> map[key] = value
            // For arrays of Parcelables or other complex types, you might need specific handling
            // if you want them in the map in a particular format for JSON.
            // Example for String array:
            is Array<*> -> {
                if (value.isArrayOf<String>()) {
                    map[key] = value.filterIsInstance<String>() // Store as List<String>
                } else {
                    map[key] = value.map { it.toString() } // Fallback to list of strings
                }
            }
            null -> map[key] = null // Explicitly handle null
            else -> map[key] = value.toString() // Fallback for other types
        }
    }
    return map
}

// If you also need to go from a Map to a Bundle (useful for recreating from JSON):
fun Map<String, Any?>.toBundle(gson: com.google.gson.Gson? = null): Bundle {
    val bundle = Bundle()
    for ((key, value) in this) {
        when (value) {
            null -> bundle.putString(key, null) // Explicitly put null for keys with null values
            is Boolean -> bundle.putBoolean(key, value)
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Double -> bundle.putDouble(key, value)
            is String -> bundle.putString(key, value)
            is List<*> -> {
                // This is a simplification. Gson would be better for complex lists.
                // Assuming a list of strings for simplicity if no Gson provided.
                if (gson != null) {
                    bundle.putString(key, gson.toJson(value)) // Store as JSON string
                } else if (value.all { it is String }) {
                    @Suppress("UNCHECKED_CAST")
                    bundle.putStringArrayList(key, ArrayList(value as List<String>))
                }
            }
            is Map<*, *> -> {
                // For nested maps, convert them to Bundles recursively
                @Suppress("UNCHECKED_CAST")
                bundle.putBundle(key, (value as Map<String, Any?>).toBundle(gson))
            }
            // Add other types as needed
            // else -> bundle.putString(key, value.toString()) // Fallback, might not be ideal for all types
        }
    }
    return bundle
}

