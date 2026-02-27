package com.example.messenger

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xmtp.android.library.Conversation

/**
 * Manages background sync of XMTP message history.
 *
 * After login (Client.create) or restore (Client.build), this manager:
 * 1. Pulls ALL conversation metadata + messages from the XMTP network
 *    into the SDK's internal encrypted SQLite database.
 * 2. Starts real-time streams for new conversations and messages.
 *
 * After reinstall: the user signs again → Client.create() registers a new
 * installation → XMTP device sync sends a MessageHistory request to other
 * installations and the history server. SyncManager waits for this async
 * process, then pulls the discovered conversations.
 */
object SyncManager {
    private const val TAG = "SyncManager"
    private const val PREFS_NAME = "sync_prefs"
    private const val KEY_INITIAL_SYNC_DONE = "initial_sync_done"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    sealed class SyncState {
        data object Idle : SyncState()
        data class Syncing(
            val phase: String,
            val detail: String = "",
            val syncedCount: Int = 0,
            val totalCount: Int = 0
        ) : SyncState()
        data class SyncResult(
            val success: Boolean,
            val conversationsFound: Int,
            val messagesPhase: String = ""
        ) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    /**
     * Performs a full history sync from the XMTP network.
     *
     * On a fresh install, XMTP's device sync sends a MessageHistory request
     * to the history server and other active installations. This is async,
     * so we poll for new conversations with increasing delays.
     */
    fun startInitialSync(forceResync: Boolean = false) {
        if (syncJob?.isActive == true) {
            Log.d(TAG, "Sync already in progress, skipping")
            return
        }

        syncJob = scope.launch {
            try {
                val client = ClientManager.client
                val inboxId = client.inboxId
                Log.d(TAG, "Starting sync for inboxId=$inboxId")

                // ── Step 1: Initial sync + syncAllConversations ──────────
                _syncState.value = SyncState.Syncing(
                    phase = "Синхронизация…",
                    detail = "conversations.sync()"
                )
                client.conversations.sync()
                val afterFirstSync = client.conversations.list().size
                Log.d(TAG, "Step 1: conversations.sync() → $afterFirstSync convos")

                _syncState.value = SyncState.Syncing(
                    phase = "Синхронизация…",
                    detail = "syncAllConversations()"
                )
                val syncedGroups = client.conversations.syncAllConversations()
                val afterSyncAll = client.conversations.list().size
                Log.d(TAG, "Step 1: syncAllConversations() → synced=$syncedGroups, total=$afterSyncAll convos")

                // ── Step 2: Wait for device sync (MessageHistory) ────────
                // After Client.create(), XMTP sends a MessageHistory request
                // to the history server. The response is async and may take
                // several seconds. We poll with short intervals.
                var totalFound = afterSyncAll
                if (totalFound == 0) {
                    Log.d(TAG, "Step 2: No conversations found yet, waiting for device sync / MessageHistory response...")
                    for (attempt in 1..3) {
                        _syncState.value = SyncState.Syncing(
                            phase = "Ожидание истории… ($attempt/3)",
                            detail = "Запрос к серверу истории XMTP"
                        )
                        delay(3000)
                        client.conversations.sync()
                        client.conversations.syncAllConversations()
                        totalFound = client.conversations.list().size
                        Log.d(TAG, "Step 2 attempt $attempt: found $totalFound conversations")
                        if (totalFound > 0) break
                    }
                }

                // ── Step 3: Per-conversation message sync ────────────────
                val address = KeyManager.getSavedWalletAddress(
                    ClientManager.appContext ?: return@launch
                )
                val deletedTopics = if (address != null) {
                    ConversationPrefsManager.getDeletedTopics(ClientManager.appContext!!, address)
                } else {
                    emptySet()
                }

                val allConversations = client.conversations.list()
                val conversations = allConversations.filter { !deletedTopics.contains(it.topic) }
                
                val totalAll = allConversations.size
                val totalToSync = conversations.size
                
                if (totalAll > totalToSync) {
                    Log.d(TAG, "Step 3: skipping ${totalAll - totalToSync} deleted conversations. Syncing $totalToSync total.")
                } else {
                    Log.d(TAG, "Step 3: syncing messages for $totalToSync conversations")
                }

                if (totalToSync > 0) {
                    conversations.forEachIndexed { index, conversation ->
                        try {
                            _syncState.value = SyncState.Syncing(
                                phase = "Загрузка сообщений ${index + 1}/$totalToSync",
                                syncedCount = index + 1,
                                totalCount = totalToSync
                            )
                            when (conversation) {
                                is Conversation.Group -> conversation.group.sync()
                                is Conversation.Dm -> conversation.dm.sync()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to sync ${conversation.topic}: ${e.message}")
                        }
                    }
                }

                // ── Result ───────────────────────────────────────────────
                _syncState.value = SyncState.SyncResult(
                    success = totalAll > 0,
                    conversationsFound = totalAll,
                    messagesPhase = if (totalAll > 0) "✅ Синхронизировано $totalToSync диалогов"
                                    else "⚠️ Диалоги не найдены (0 на сервере)"
                )
                Log.d(TAG, "Sync finished: $totalAll conversations found, $totalToSync synced")

                // Auto-backup to Documents/ so data survives reinstall
                if (totalAll > 0) {
                    if (address != null) {
                        val backed = BackupManager.autoBackup(
                            ClientManager.appContext!!, address
                        )
                        Log.d(TAG, "Auto-backup after sync: $backed")
                    }
                }
                delay(5000)
                _syncState.value = SyncState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed: ${e.message}", e)
                _syncState.value = SyncState.Error("❌ ${e.message ?: "Ошибка синхронизации"}")
            }
        }
    }

    /**
     * Clear sync state (used on logout/clean start)
     */
    fun isInitialSyncDone(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_INITIAL_SYNC_DONE, false)
    }

    fun markInitialSyncDone(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INITIAL_SYNC_DONE, true)
            .apply()
    }

    fun clearSyncState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_INITIAL_SYNC_DONE)
            .apply()
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
        _syncState.value = SyncState.Idle
        Log.d(TAG, "All sync jobs stopped")
    }
}
