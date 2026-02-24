package com.example.messenger

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages local user aliases for XMTP conversations.
 * Since XMTP is completely decentralized, there is no global server storing
 * your contact list's names. This manager allows the user to locally "rename"
 * a 0x address to a human-readable name like "Alice" or "Bob".
 */
object ContactManager {

    private const val PREFS_NAME = "xmtp_contact_aliases"
    private const val MY_PROFILE_NAME_KEY = "my_profile_name"
    private const val PROFILE_SHARING_ENABLED_KEY = "profile_sharing_enabled"
    private const val SHARED_TOPICS_PREFS_NAME = "xmtp_shared_topics"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Saves a human-readable alias for a given 0x address.
     */
    fun saveAlias(context: Context, address: String, alias: String) {
        val cleanAddress = address.trim().lowercase()
        // If the alias is blank, we can interpret it as deleting the alias
        if (alias.isBlank()) {
            getPrefs(context).edit().remove(cleanAddress).apply()
        } else {
            getPrefs(context).edit().putString(cleanAddress, alias.trim()).apply()
        }
    }

    /**
     * Retrieves the saved alias for a given 0x address, or null if none is set.
     */
    fun getAlias(context: Context, address: String): String? {
        val cleanAddress = address.trim().lowercase()
        return getPrefs(context).getString(cleanAddress, null)
    }

    /**
     * Saves a custom display name for the current user's own profile.
     */
    fun saveMyProfileName(context: Context, name: String) {
        if (name.isBlank()) {
            getPrefs(context).edit().remove(MY_PROFILE_NAME_KEY).apply()
        } else {
            getPrefs(context).edit().putString(MY_PROFILE_NAME_KEY, name.trim()).apply()
        }
    }

    /**
     * Retrieves the current user's custom display name, or null if none is set.
     */
    fun getMyProfileName(context: Context): String? {
        return getPrefs(context).getString(MY_PROFILE_NAME_KEY, null)
    }

    /**
     * Checks if profile sharing is enabled (default is false).
     */
    fun isProfileSharingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PROFILE_SHARING_ENABLED_KEY, false)
    }

    /**
     * Sets whether profile sharing is enabled.
     */
    fun setProfileSharingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PROFILE_SHARING_ENABLED_KEY, enabled).apply()
    }

    /**
     * Checks if the current profile name was already shared in the given topic.
     * We store the shared name itself for the topic, so if the user changes their name,
     * it will be re-shared (since the stored name won't match the new name).
     */
    fun getSharedNameForTopic(context: Context, topic: String): String? {
        val prefs = context.getSharedPreferences(SHARED_TOPICS_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(topic, null)
    }

    /**
     * Records that a specific profile name was shared in the given topic.
     */
    fun setSharedNameForTopic(context: Context, topic: String, name: String) {
        val prefs = context.getSharedPreferences(SHARED_TOPICS_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(topic, name).apply()
    }
}
