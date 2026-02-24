package com.example.messenger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.messenger.ClientManager
import com.example.messenger.ContactManager
import com.example.messenger.SyncManager
import com.example.messenger.data.XmtpRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xmtp.android.library.libxmtp.DecodedMessage

class ConversationViewModel() : ViewModel() {
    private val repository = XmtpRepository()

    private val SYSTEM_MSG_PREFIX = "__MESSENGER_PROFILE_UPDATE:"

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
            val msgs = repository.fetchMessagesLocal(_topic.value)
            _messages.value = processAndFilterMessages(msgs)
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
                 val processedList = processAndFilterMessages(listOf(newMessage))
                 if (processedList.isNotEmpty()) {
                     _messages.value = _messages.value + processedList.first()
                 }
            }
        }
    }

    fun sendMessage(body: String) {
        viewModelScope.launch {
            try {
                // Check if we need to share profile name first
                val context = ClientManager.appContext
                if (context != null && ContactManager.isProfileSharingEnabled(context)) {
                    val myName = ContactManager.getMyProfileName(context)
                    if (!myName.isNullOrBlank()) {
                        val sharedName = ContactManager.getSharedNameForTopic(context, _topic.value)
                        // If we haven't shared our current name in this topic yet, send the hidden message
                        if (sharedName != myName) {
                            val systemMsg = "$SYSTEM_MSG_PREFIX${myName}__"
                            repository.sendMessage(_topic.value, systemMsg)
                            ContactManager.setSharedNameForTopic(context, _topic.value, myName)
                        }
                    }
                }

                repository.sendMessage(_topic.value, body)
                // Sending successful, the stream will catch the local echo or remote response
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    /**
     * Processes a list of messages:
     * 1. Detects and extracts system profile update messages.
     * 2. Saves the alias locally.
     * 3. Filters out the system messages so they don't appear in the UI.
     */
    private fun processAndFilterMessages(msgs: List<DecodedMessage>): List<DecodedMessage> {
        val filtered = mutableListOf<DecodedMessage>()
        val context = ClientManager.appContext ?: return msgs

        for (msg in msgs) {
            val body = msg.body as? String ?: continue
            if (body.startsWith(SYSTEM_MSG_PREFIX) && body.endsWith("__")) {
                // It's a system message, process it
                val name = body.removePrefix(SYSTEM_MSG_PREFIX).removeSuffix("__")
                if (name.isNotBlank()) {
                    // Only save if it's from a peer, or even if from us, to sync across our devices
                    ContactManager.saveAlias(context, msg.senderInboxId, name)
                }
            } else {
                // Regular message
                filtered.add(msg)
            }
        }
        return filtered
    }
}
