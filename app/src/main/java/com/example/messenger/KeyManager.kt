package com.example.messenger

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * Manages the persistent encryption key for the XMTP local SQLite database.
 * The key is generated once and stored in SharedPreferences.
 * If the key already exists, it is reused to avoid "PRAGMA key" errors.
 */
object KeyManager {
    private const val PREFS_NAME = "xmtp_key_prefs"
    private const val KEY_DB_ENCRYPTION = "db_encryption_key"

    fun getOrCreateDbEncryptionKey(context: Context, address: String): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keyName = "${KEY_DB_ENCRYPTION}_${address.lowercase()}"
        val existing = prefs.getString(keyName, null)
        if (existing != null) {
            return Base64.decode(existing, Base64.DEFAULT)
        }
        // Generate a new 32-byte key and persist it
        val newKey = SecureRandom().generateSeed(32)
        prefs.edit().putString(keyName, Base64.encodeToString(newKey, Base64.DEFAULT)).apply()
        return newKey
    }

    /**
     * Force-overwrites the stored DB encryption key for [address].
     * Use this after restoring a backup so that future autoBackup() calls
     * write the CORRECT key (the one actually used to open the DB),
     * not the stale randomly-generated key in the SharedPreferences cache.
     *
     * Uses commit() (synchronous) to ensure the new value is flushed to disk
     * AND updates the in-memory SharedPreferences instance immediately.
     */
    fun forceSetDbEncryptionKey(context: Context, address: String, key: ByteArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keyName = "${KEY_DB_ENCRYPTION}_${address.lowercase()}"
        prefs.edit()
            .putString(keyName, Base64.encodeToString(key, Base64.DEFAULT))
            .commit()  // synchronous — updates in-memory cache AND disk immediately
    }


    fun saveWalletAddress(context: Context, address: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("wallet_address", address).apply()
    }

    fun getSavedWalletAddress(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("wallet_address", null)
    }

    fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove("wallet_address").commit()
    }
}
