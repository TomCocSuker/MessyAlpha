package com.example.messenger.data

import com.example.messenger.ClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.xmtp.android.library.Conversation
import org.xmtp.android.library.SendOptions
import org.xmtp.android.library.codecs.Attachment
import org.xmtp.android.library.codecs.ContentTypeAttachment
import org.xmtp.android.library.libxmtp.DecodedMessage

class XmtpRepository {
    private val client get() = ClientManager.client

    /**
     * Reads conversations from the local XMTP SQLite DB only — instant, no network.
     * Use this for UI loading; SyncManager handles network sync separately.
     */
    suspend fun fetchConversationsLocal(): List<Conversation> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        client.conversations.list()
    }

    /**
     * Full network sync + local read. Used for manual pull-to-refresh.
     */
    suspend fun fetchConversationsWithSync(): List<Conversation> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        client.conversations.syncAllConversations()
        client.conversations.list()
    }

    fun streamConversations(): Flow<Conversation> = flow {
         client.conversations.stream().collect { conversation ->
            emit(conversation)
         }
    }.flowOn(Dispatchers.IO)

    suspend fun startConversation(peerAddress: String): Conversation = kotlinx.coroutines.withContext(Dispatchers.IO) {
        // Fallback for self-chat: XMTP V4 strictly rejects creating a 1:1 DM where recipient == sender.
        // We simulate a 'Saved Messages' self-chat by creating a Group with just the current user.
        if (peerAddress.equals(client.publicIdentity.identifier, ignoreCase = true)) {
            val existing = client.conversations.list().firstOrNull {
                it is Conversation.Group && it.group.name == "Saved Messages"
            }
            if (existing != null) return@withContext existing

            val group = client.conversations.newGroup(
                inboxIds = emptyList(),
                groupName = "Saved Messages",
                groupDescription = "Self chat"
            )
            return@withContext Conversation.Group(group)
        }

        // For regular 1:1 chats, newConversationWithIdentity natively acts as a find-or-create operation
        val identity = org.xmtp.android.library.libxmtp.PublicIdentity(
            org.xmtp.android.library.libxmtp.IdentityKind.ETHEREUM,
            peerAddress
        )
        try {
            client.conversations.newConversationWithIdentity(identity)
        } catch (e: Exception) {
            println("Error starting conversation: ${e.message}")
            throw e
        }
    }

    suspend fun createGroup(groupName: String, members: List<String>): Conversation.Group = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val identities = members.map { address ->
            org.xmtp.android.library.libxmtp.PublicIdentity(
                org.xmtp.android.library.libxmtp.IdentityKind.ETHEREUM,
                address
            )
        }

        // 1. Create the group with identities directly
        val group = client.conversations.newGroupWithIdentities(
            identities = identities,
            groupName = groupName,
            groupDescription = ""
        )
        
        Conversation.Group(group)
    }

    suspend fun addMember(topic: String, address: String) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val conversation = client.conversations.findConversationByTopic(topic)
        if (conversation is org.xmtp.android.library.Conversation.Group) {
            val identity = org.xmtp.android.library.libxmtp.PublicIdentity(
                org.xmtp.android.library.libxmtp.IdentityKind.ETHEREUM,
                address
            )
            conversation.group.addMembersByIdentity(listOf(identity))
        } else {
            throw Exception("Conversation not found or not a group")
        }
    }

    /**
     * Reads messages from the local XMTP SQLite DB only — instant, no network.
     */
    suspend fun fetchMessagesLocal(topic: String): List<DecodedMessage> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val conversation = client.conversations.findConversationByTopic(topic)
        (conversation?.messages() ?: emptyList()).reversed()
    }

    /**
     * Sync a specific conversation from network, then read messages.
     * Used for manual refresh within a chat.
     */
    suspend fun fetchMessagesWithSync(topic: String): List<DecodedMessage> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        // Full sync to ensure all participants and conversations are up-to-date
        try {
            client.conversations.sync()
        } catch (e: Exception) {
            android.util.Log.e("XmtpRepository", "Full sync failed: ${e.message}")
        }

        val conversation = client.conversations.findConversationByTopic(topic)
        when (conversation) {
            is Conversation.Group -> {
                try {
                    conversation.group.sync()
                } catch (e: Exception) {
                    android.util.Log.e("XmtpRepository", "Group sync failed for $topic: ${e.message}")
                }
            }
            is Conversation.Dm -> {
                try {
                    conversation.dm.sync()
                } catch (e: Exception) {
                    android.util.Log.e("XmtpRepository", "DM sync failed for $topic: ${e.message}")
                }
            }
            else -> {}
        }
        (conversation?.messages() ?: emptyList()).reversed()
    }

    fun streamMessages(topic: String): Flow<DecodedMessage> = flow {
         val conversation = client.conversations.findConversationByTopic(topic)
         conversation?.streamMessages()?.collect { message ->
             emit(message)
         }
    }.flowOn(Dispatchers.IO)
    
    suspend fun sendMessage(topic: String, body: String) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val conversation = client.conversations.findConversationByTopic(topic)
        conversation?.send(body)
    }

    suspend fun sendAttachment(topic: String, attachment: Attachment) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val conversation = client.conversations.findConversationByTopic(topic)
        conversation?.send(
            content = attachment,
            options = SendOptions(contentType = ContentTypeAttachment)
        )
    }

    suspend fun fetchGroupMembers(topic: String): List<Any> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val conversation = client.conversations.findConversationByTopic(topic)
        if (conversation is org.xmtp.android.library.Conversation.Group) {
            try {
                conversation.group.sync()
            } catch (e: Exception) {
                android.util.Log.e("XmtpRepository", "Failed to sync group state: ${e.message}")
            }
            conversation.group.members()
        } else {
            emptyList()
        }
    }
}
