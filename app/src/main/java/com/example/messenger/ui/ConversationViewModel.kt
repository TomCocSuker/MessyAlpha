package com.example.messenger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.messenger.SyncManager
import com.example.messenger.data.XmtpRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xmtp.android.library.libxmtp.DecodedMessage

class ConversationViewModel() : ViewModel() {
    private val repository = XmtpRepository()

    private val _messages = MutableStateFlow<List<DecodedMessage>>(emptyList())
    val messages: StateFlow<List<DecodedMessage>> = _messages.asStateFlow()

    private val _topic = MutableStateFlow<String>("")
    val topic: StateFlow<String> = _topic.asStateFlow()

    fun setTopic(newTopic: String) {
        if (_topic.value == newTopic) return
        _topic.value = newTopic
        loadMessagesLocal()
        listenForNewMessages()
        observeSyncUpdates()
    }

    /**
     * Load messages from local XMTP SQLite — instant, no network call.
     */
    private fun loadMessagesLocal() {
        if (_topic.value.isEmpty()) return
        viewModelScope.launch {
            _messages.value = repository.fetchMessagesLocal(_topic.value)
        }
    }

    /**
     * Observe sync state — when SyncManager finishes syncing,
     * reload messages (new history may have been pulled from network).
     */
    private fun observeSyncUpdates() {
        viewModelScope.launch {
            SyncManager.syncState.collect { state ->
                if (state is SyncManager.SyncState.SyncResult) {
                    loadMessagesLocal()
                }
            }
        }
    }

    private fun listenForNewMessages() {
        if (_topic.value.isEmpty()) return
        viewModelScope.launch {
            repository.streamMessages(_topic.value).collect { newMessage ->
                 _messages.value = _messages.value + newMessage
            }
        }
    }

    fun sendMessage(body: String) {
        viewModelScope.launch {
            try {
                repository.sendMessage(_topic.value, body)
                // Sending successful, the stream will catch the local echo or remote response
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
}
