package com.example.messenger

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages the global preference for DPI bypass (fragmentation).
 * This is off by default as requested.
 */
object DpiBypassPrefsManager {
    private const val PREFS_NAME = "dpi_bypass_prefs"
    private const val KEY_ENABLED = "is_enabled"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
