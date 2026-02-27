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
import android.content.Context
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.messenger.ConversationPrefsManager

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = XmtpRepository()
    private val context: Context get() = getApplication()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentAddress = MutableStateFlow("")
    val currentAddress: StateFlow<String> = _currentAddress.asStateFlow()

    private val _pinnedTopics = MutableStateFlow<Set<String>>(emptySet())
    val pinnedTopics: StateFlow<Set<String>> = _pinnedTopics.asStateFlow()

    private val _deletedTopics = MutableStateFlow<Set<String>>(emptySet())
    val deletedTopics: StateFlow<Set<String>> = _deletedTopics.asStateFlow()

    private val _savedMessagesTopic = MutableStateFlow<String?>(null)
    val savedMessagesTopic: StateFlow<String?> = _savedMessagesTopic.asStateFlow()

    private val _mutedTopics = MutableStateFlow<Set<String>>(emptySet())
    val mutedTopics: StateFlow<Set<String>> = _mutedTopics.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    // Expose sync state from SyncManager for UI progress banner
    val syncState: StateFlow<SyncManager.SyncState> = SyncManager.syncState

    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex: StateFlow<Int> = _selectedTabIndex.asStateFlow()

    fun setSelectedTabIndex(index: Int) {
        _selectedTabIndex.value = index
    }

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
            val address = ClientManager.client.publicIdentity.identifier
            _currentAddress.value = address
            
            val pinned = ConversationPrefsManager.getPinnedTopics(context, address)
            var deleted = ConversationPrefsManager.getDeletedTopics(context, address)
            val muted = ConversationPrefsManager.getMutedTopics(context, address)
            val savedTopic = ConversationPrefsManager.getSavedMessagesTopic(context, address)
            
            // Protection: if Saved Messages topic was somehow marked as deleted, undelete it automatically
            if (savedTopic != null && deleted.contains(savedTopic)) {
                ConversationPrefsManager.removeDeletedTopic(context, address, savedTopic)
                deleted = ConversationPrefsManager.getDeletedTopics(context, address)
            }

            _pinnedTopics.value = pinned
            _deletedTopics.value = deleted
            _mutedTopics.value = muted
            _savedMessagesTopic.value = savedTopic
            
            // Fetch and filter
        val allConvs = repository.fetchConversationsLocal()
        val filteredNewConvs = allConvs.filter { !deleted.contains(it.topic) }

        // Workaround for XMTP SDK bug where syncAllConversations() temporarily 
        // drops groups from conversations.list() until they are fully synced:
        // We preserve any existing (in-memory) groups that suddenly went missing.
        val newTopics = filteredNewConvs.map { it.topic }.toSet()
        val missingConvs = _conversations.value.filter { 
            !newTopics.contains(it.topic) && !deleted.contains(it.topic)
        }
        
        var finalConvs = missingConvs + filteredNewConvs
        
        // Ensure "Saved Messages" self-DM is consistently tracked.
        // If we found a group with name="Saved Messages" but no cached topic, cache it.
        if (savedTopic == null) {
            val detectedTopic = finalConvs.firstOrNull { it is Conversation.Group && it.group.name == "Saved Messages" }?.topic
            if (detectedTopic != null) {
                ConversationPrefsManager.setSavedMessagesTopic(context, address, detectedTopic)
                _savedMessagesTopic.value = detectedTopic
            }
        }
        
        _conversations.value = finalConvs
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
                // Debug log before sync
                val groupsBefore = repository.fetchConversationsLocal().filterIsInstance<Conversation.Group>().size
                android.util.Log.d("MainViewModel", "Groups before sync: $groupsBefore")
                
                // Use SyncManager so the progress banner appears
                SyncManager.startInitialSync(forceResync = true)
                
                // Note: SyncManager is async, so this might not capture the final count immediately, 
                // but we will observe its effect in loaded conversations.
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
                if (!_deletedTopics.value.contains(newConversation.topic)) {
                    _conversations.value = listOf(newConversation) + _conversations.value
                }
            }
        }
    }

    fun togglePin(topic: String) {
        val address = _currentAddress.value
        if (address.isEmpty()) return
        
        val currentPinned = _pinnedTopics.value
        if (currentPinned.contains(topic)) {
            ConversationPrefsManager.removePinnedTopic(context, address, topic)
        } else {
            ConversationPrefsManager.addPinnedTopic(context, address, topic)
        }
        _pinnedTopics.value = ConversationPrefsManager.getPinnedTopics(context, address)
    }

    fun toggleMute(topic: String) {
        val address = _currentAddress.value
        if (address.isEmpty()) return
        
        val currentMuted = _mutedTopics.value
        if (currentMuted.contains(topic)) {
            ConversationPrefsManager.removeMutedTopic(context, address, topic)
        } else {
            ConversationPrefsManager.addMutedTopic(context, address, topic)
        }
        _mutedTopics.value = ConversationPrefsManager.getMutedTopics(context, address)
    }

    fun deleteConversation(topic: String) {
        val address = _currentAddress.value
        if (address.isEmpty()) return
        
        // Prevent accidental deletion of "Saved Messages"
        if (topic == _savedMessagesTopic.value) {
            android.util.Log.w("MainViewModel", "Refusing to delete Saved Messages chat")
            return
        }
        
        ConversationPrefsManager.addDeletedTopic(context, address, topic)
        _deletedTopics.value = ConversationPrefsManager.getDeletedTopics(context, address)
        _pinnedTopics.value = ConversationPrefsManager.getPinnedTopics(context, address) // Sync pins if it was pinned
        
        // Remove from current list
        _conversations.value = _conversations.value.filter { it.topic != topic }
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
                
                // Restoration: if this chat was previously deleted, remove it from the deleted set
                val address = _currentAddress.value
                ConversationPrefsManager.removeDeletedTopic(context, address, newConv.topic)
                _deletedTopics.value = ConversationPrefsManager.getDeletedTopics(context, address)
                
                _conversations.value = listOf(newConv) + _conversations.value
                
                // Hard-cache 'Saved Messages' topic if self-address was typed, 
                // ensuring it is never lost during strict filtering after manual syncs.
                if (peerAddress.equals(ClientManager.client.publicIdentity.identifier, ignoreCase = true)) {
                    ConversationPrefsManager.setSavedMessagesTopic(context, address, newConv.topic)
                    _savedMessagesTopic.value = newConv.topic
                }
                
                onCreated(newConv.topic)
            } catch (e: Exception) {
                onError("Error starting chat: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createGroup(name: String, members: List<String>, onCreated: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val newGroup = repository.createGroup(name, members)
                
                // Restoration: if this group was previously deleted, remove it from the deleted set
                val address = _currentAddress.value
                ConversationPrefsManager.removeDeletedTopic(context, address, newGroup.topic)
                _deletedTopics.value = ConversationPrefsManager.getDeletedTopics(context, address)
                
                _conversations.value = listOf(newGroup) + _conversations.value
                onCreated(newGroup.topic)
            } catch (e: Exception) {
                onError("Error creating group: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }
}
