package com.example.messenger.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.messenger.ClientManager
import com.example.messenger.ContactManager
import com.example.messenger.ImageUtils
import com.example.messenger.SyncManager
import com.example.messenger.data.XmtpRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xmtp.android.library.libxmtp.DecodedMessage

data class GroupMemberInfo(
    val id: String,
    val role: String,
    val displayName: String? = null
)

class ConversationViewModel() : ViewModel() {
    private val repository = XmtpRepository()

    private val SYSTEM_MSG_PREFIX = "__MESSENGER_PROFILE_UPDATE:"
    private val HUDDLE_CALL_PREFIX = "__HUDDLE01_CALL:"

    private val _messages = MutableStateFlow<List<DecodedMessage>>(emptyList())
    val messages: StateFlow<List<DecodedMessage>> = _messages.asStateFlow()

    private val _topic = MutableStateFlow<String>("")
    val topic: StateFlow<String> = _topic.asStateFlow()

    private val _addMemberResult = MutableStateFlow<Result<Unit>?>(null)
    val addMemberResult: StateFlow<Result<Unit>?> = _addMemberResult.asStateFlow()

    private val _members = MutableStateFlow<List<GroupMemberInfo>>(emptyList())
    val members: StateFlow<List<GroupMemberInfo>> = _members.asStateFlow()

    fun setTopic(newTopic: String) {
        if (_topic.value == newTopic) return
        _topic.value = newTopic
        ClientManager.activeConversationTopic = newTopic
        loadMessagesLocal()
        loadMembers()
        listenForNewMessages()
        observeSyncUpdates()
    }

    override fun onCleared() {
        super.onCleared()
        // If this VM was tracking the active topic, clear it
        if (ClientManager.activeConversationTopic == _topic.value) {
            ClientManager.activeConversationTopic = null
        }
    }

    /**
     * Load messages from local XMTP SQLite — instant, no network call.
     */
    private fun loadMessagesLocal() {
        if (_topic.value.isEmpty()) return
        viewModelScope.launch {
            val msgs = repository.fetchMessagesWithSync(_topic.value)
            _messages.value = processAndFilterMessages(msgs)
        }
    }

    private fun loadMembers() {
        if (_topic.value.isEmpty()) return
        viewModelScope.launch {
            val rawMembers = repository.fetchGroupMembers(_topic.value)
            _members.value = rawMembers.map { member ->
                // The SDK Member object in 4.0.3 should have an inboxId property
                val id = try {
                    // Try direct access first (if the tool knows the type, it would work)
                    // But rawMembers is List<Any> from repository, so we still might need reflection or cast
                    val inboxIdMethod = member.javaClass.getMethod("getInboxId")
                    inboxIdMethod.invoke(member) as? String ?: member.toString()
                } catch (e: Exception) {
                    try {
                        val inboxIdField = member.javaClass.getDeclaredField("inboxId")
                        inboxIdField.isAccessible = true
                        inboxIdField.get(member) as? String ?: member.toString()
                    } catch (e2: Exception) {
                        member.toString()
                    }
                }
                
                val context = ClientManager.appContext
                val displayName = if (context != null) ContactManager.getAlias(context, id) else null
                
                GroupMemberInfo(id = id, role = "Member", displayName = displayName)
            }
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
                    loadMembers()
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

    fun initiateCall(context: android.content.Context, isVideo: Boolean) {
        viewModelScope.launch {
            try {
                // Show a small Toast immediately so user knows something is happening
                android.widget.Toast.makeText(context, "Initializing call...", android.widget.Toast.LENGTH_SHORT).show()

                // Create a real room on Huddle01
                val roomId = com.example.messenger.CallManager.createRoom()
                if (roomId == null) {
                    android.util.Log.e("ConversationViewModel", "Failed to create Huddle01 room")
                    android.widget.Toast.makeText(context, "Failed to create call room", android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }
                
                val type = if (isVideo) "video" else "audio"
                val signalingMsg = "$HUDDLE_CALL_PREFIX$roomId:$type"
                
                // Send signaling message in background to avoid blocking navigation
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        repository.sendMessage(_topic.value, signalingMsg)
                    } catch (e: Exception) {
                        android.util.Log.e("ConversationViewModel", "Failed to send signaling message: ${e.message}")
                    }
                }
                
                // Join the room ourselves
                val intent = android.content.Intent(context, CallActivity::class.java).apply {
                    putExtra("room_id", roomId)
                    putExtra("is_video", isVideo)
                    putExtra("is_initiator", true)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.e("ConversationViewModel", "Error initiating call: ${e.message}")
                android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
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

    fun sendImages(uris: List<Uri>, context: Context) {
        viewModelScope.launch {
            uris.forEach { uri ->
                try {
                    val attachment = ImageUtils.compressAndTranscodeToJpg(uri, context)
                    if (attachment != null) {
                        repository.sendAttachment(_topic.value, attachment)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ConversationViewModel", "Error sending multi images: ${e.message}")
                }
            }
        }
    }

    fun sendImage(uri: Uri, context: Context) {
        sendImages(listOf(uri), context)
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
            } else if (body.startsWith(HUDDLE_CALL_PREFIX)) {
                // Detect Huddle01 Call Signaling
                val parts = body.removePrefix(HUDDLE_CALL_PREFIX).split(":")
                if (parts.size >= 2) {
                    val roomId = parts[0]
                    val type = parts[1]
                    // If it's not from us, we might want to show a "Join" UI or a notification
                    // In this implementation, we'll keep the message but maybe handle it in the UI
                    filtered.add(msg)
                }
            } else {
                // Regular message
                filtered.add(msg)
            }
        }
        return filtered
    }

    fun addMember(address: String) {
        viewModelScope.launch {
            try {
                repository.addMember(_topic.value, address)
                _addMemberResult.value = Result.success(Unit)
                loadMembers() // Refresh list immediately
            } catch (e: Exception) {
                _addMemberResult.value = Result.failure(e)
            }
        }
    }

    fun resetAddMemberResult() {
        _addMemberResult.value = null
    }
}
