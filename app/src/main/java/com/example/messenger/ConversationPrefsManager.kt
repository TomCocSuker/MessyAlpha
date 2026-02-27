package com.example.messenger

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages local user preferences for pinned and deleted XMTP conversations.
 * Data is scoped per wallet address so that multi-account feature works correctly.
 */
object ConversationPrefsManager {

    private const val PREFS_NAME = "xmtp_conversation_prefs"
    
    // Keys use the format: {walletAddress}_pinned or {walletAddress}_deleted
    private const val SUFFIX_PINNED = "_pinned"
    private const val SUFFIX_DELETED = "_deleted"
    private const val SUFFIX_MUTED = "_muted"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getTopicSet(context: Context, key: String): Set<String> {
        return getPrefs(context).getStringSet(key, emptySet()) ?: emptySet()
    }

    private fun saveTopicSet(context: Context, key: String, set: Set<String>) {
        getPrefs(context).edit().putStringSet(key, set).apply()
    }

    // --- Pinned Topics ---

    fun getPinnedTopics(context: Context, walletAddress: String): Set<String> {
        val key = "${walletAddress.lowercase()}$SUFFIX_PINNED"
        return getTopicSet(context, key)
    }

    fun addPinnedTopic(context: Context, walletAddress: String, topic: String) {
        val key = "${walletAddress.lowercase()}$SUFFIX_PINNED"
        val currentSet = getTopicSet(context, key).toMutableSet()
        currentSet.add(topic)
        saveTopicSet(context, key, currentSet)
    }

    fun removePinnedTopic(context: Context, walletAddress: String, topic: String) {
        val key = "${walletAddress.lowercase()}$SUFFIX_PINNED"
        val currentSet = getTopicSet(context, key).toMutableSet()
        currentSet.remove(topic)
        saveTopicSet(context, key, currentSet)
    }

    // --- Deleted Topics ---

    fun getDeletedTopics(context: Context, walletAddress: String): Set<String> {
        val key = "${walletAddress.lowercase()}$SUFFIX_DELETED"
        return getTopicSet(context, key)
    }

    fun addDeletedTopic(context: Context, walletAddress: String, topic: String) {
        val key = "${walletAddress.lowercase()}$SUFFIX_DELETED"
        val currentSet = getTopicSet(context, key).toMutableSet()
        currentSet.add(topic)
        saveTopicSet(context, key, currentSet)
        
        // If it's deleted, also remove it from pinned if it's there
        removePinnedTopic(context, walletAddress, topic)
    }

    fun removeDeletedTopic(context: Context, walletAddress: String, topic: String) {
        val key = "${walletAddress.lowercase()}$SUFFIX_DELETED"
        val currentSet = getTopicSet(context, key).toMutableSet()
        currentSet.remove(topic)
        saveTopicSet(context, key, currentSet)
    }

    // --- Muted Topics ---

    fun getMutedTopics(context: Context, walletAddress: String): Set<String> {
        val key = "${walletAddress.lowercase()}$SUFFIX_MUTED"
        return getTopicSet(context, key)
    }

    fun addMutedTopic(context: Context, walletAddress: String, topic: String) {
        val key = "${walletAddress.lowercase()}$SUFFIX_MUTED"
        val currentSet = getTopicSet(context, key).toMutableSet()
        currentSet.add(topic)
        saveTopicSet(context, key, currentSet)
    }

    fun removeMutedTopic(context: Context, walletAddress: String, topic: String) {
        val key = "${walletAddress.lowercase()}$SUFFIX_MUTED"
        val currentSet = getTopicSet(context, key).toMutableSet()
        currentSet.remove(topic)
        saveTopicSet(context, key, currentSet)
    }
    // --- Saved Messages Topic ---
    
    private const val SUFFIX_SAVED_MSG = "_saved_msg"

    fun getSavedMessagesTopic(context: Context, walletAddress: String): String? {
        val key = "${walletAddress.lowercase()}$SUFFIX_SAVED_MSG"
        return getPrefs(context).getString(key, null)
    }

    fun setSavedMessagesTopic(context: Context, walletAddress: String, topic: String) {
        val key = "${walletAddress.lowercase()}$SUFFIX_SAVED_MSG"
        getPrefs(context).edit().putString(key, topic).apply()
    }
}
