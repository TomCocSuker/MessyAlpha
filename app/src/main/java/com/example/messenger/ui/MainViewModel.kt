package com.example.messenger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.messenger.ClientManager
import com.example.messenger.SyncManager
import com.example.messenger.data.XmtpRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xmtp.android.library.Conversation

class MainViewModel : ViewModel() {
    private val repository = XmtpRepository()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentAddress = MutableStateFlow("")
    val currentAddress: StateFlow<String> = _currentAddress.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    // Expose sync state from SyncManager for UI progress banner
    val syncState: StateFlow<SyncManager.SyncState> = SyncManager.syncState

    private var isListening = false

    init {
        // Observe clientState — when it transitions to Ready, auto-load conversations
        viewModelScope.launch {
            ClientManager.clientState.collect { state ->
                if (state is ClientManager.ClientState.Ready) {
                    loadConversationsLocal()
                    if (!isListening) {
                        isListening = true
                        listenForNewConversations()
                        observeSyncProgress()
                    }
                }
            }
        }
    }

    /**
     * Load conversations from local XMTP DB (instant, no network).
     */
    private suspend fun loadConversationsLocal() {
        _isLoading.value = true
        try {
            _currentAddress.value = ClientManager.client.publicIdentity.identifier
            _conversations.value = repository.fetchConversationsLocal()
        } catch (e: Exception) {
            // Handle error
        } finally {
            _isLoading.value = false
        }
    }

    fun loadConversations() {
        if (ClientManager.clientState.value !is ClientManager.ClientState.Ready) return
        viewModelScope.launch { loadConversationsLocal() }
    }

    /**
     * Manual full sync with XMTP network — pulls ALL conversations and messages.
     * Triggered by the ↻ button.
     */
    fun syncHistory() {
        if (ClientManager.clientState.value !is ClientManager.ClientState.Ready) return
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                // Use SyncManager so the progress banner appears
                SyncManager.startInitialSync(forceResync = true)
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * Observe sync state: when sync makes progress or completes, refresh the conversation list.
     */
    private fun observeSyncProgress() {
        viewModelScope.launch {
            SyncManager.syncState.collect { state ->
                when (state) {
                    is SyncManager.SyncState.SyncResult -> {
                        // Sync finished — reload conversations from local DB
                        loadConversationsLocal()
                    }
                    is SyncManager.SyncState.Syncing -> {
                        // Periodically refresh while syncing so new conversations appear
                        if (state.syncedCount > 0 && state.syncedCount % 5 == 0) {
                            loadConversationsLocal()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun listenForNewConversations() {
        viewModelScope.launch {
            repository.streamConversations().collect { newConversation ->
                _conversations.value = listOf(newConversation) + _conversations.value
            }
        }
    }

    fun startConversation(peerInput: String, onCreated: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                var peerAddress = peerInput.trim()

                // Resolve ENS if needed
                if (peerAddress.lowercase().endsWith(".eth")) {
                    val resolved = com.example.messenger.EnsResolverManager.resolveName(peerAddress)
                    if (resolved != null) {
                        peerAddress = resolved
                    } else {
                        onError("Could not resolve ENS name: $peerAddress")
                        return@launch
                    }
                }

                val newConv = repository.startConversation(peerAddress)
                _conversations.value = listOf(newConv) + _conversations.value
                onCreated(newConv.topic)
            } catch (e: Exception) {
                onError("Error starting chat: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }
}
