package com.example.messenger.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.messenger.ClientManager
import com.example.messenger.services.XmtpSyncService
import kotlinx.coroutines.*
import org.xmtp.android.library.Conversation

class PingReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PingReceiver"
        
        const val ACTION_PING = "com.example.messenger.ACTION_PING"
        const val ACTION_POLL = "com.example.messenger.ACTION_POLL"
        
        const val PING_INTERVAL_MS = 4 * 60 * 1000L // 4 minutes
        const val POLLING_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        
        private const val REQUEST_CODE_PING = 2001
        private const val REQUEST_CODE_POLL = 2002

        fun schedulePing(context: Context, delayMs: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, PingReceiver::class.java).apply {
                action = ACTION_PING
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE_PING, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val triggerAt = System.currentTimeMillis() + delayMs
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            Log.d(TAG, "Ping scheduled in ${delayMs / 1000}s")
        }

        fun schedulePolling(context: Context, delayMs: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, PingReceiver::class.java).apply {
                action = ACTION_POLL
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE_POLL, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val triggerAt = System.currentTimeMillis() + delayMs
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            Log.d(TAG, "Polling scheduled in ${delayMs / 1000}s")
        }

        fun cancelAll(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            val pingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE_PING, Intent(context, PingReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pingIntent?.let { alarmManager.cancel(it) }
            
            val pollIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE_POLL, Intent(context, PingReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pollIntent?.let { alarmManager.cancel(it) }
            
            Log.d(TAG, "All alarms canceled")
        }
    }

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "onReceive: $action")

        val result = goAsync()
        
        receiverScope.launch {
            try {
                if (!ClientManager.isClientInitialized()) {
                    Log.w(TAG, "Client not initialized, skipping $action")
                    result.finish()
                    return@launch
                }

                when (action) {
                    ACTION_PING -> {
                        handlePing(context)
                        // Only reschedule if the service is still running and screen is on
                        // Actually, the service manages rescheduling in its own lifecycle,
                        // but if we want it to be robust, we can check here too.
                    }
                    ACTION_POLL -> {
                        handlePoll(context)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in PingReceiver: ${e.message}")
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun handlePing(context: Context) {
        Log.d(TAG, "Sending keep-alive ping...")
        try {
            // Lightest way to trigger network traffic
            ClientManager.client.conversations.sync()
            Log.d(TAG, "Ping sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Ping failed: ${e.message}")
        }
        
        // Re-schedule next ping if active
        // Note: XmtpSyncService usually manages this, but we can do it here for redundancy
        // if we detect we are in "stream" mode.
        schedulePing(context, PING_INTERVAL_MS)
    }

    private suspend fun handlePoll(context: Context) {
        Log.d(TAG, "Polling for new messages...")
        try {
            val client = ClientManager.client
            client.conversations.sync()
            val conversations = client.conversations.list()
            
            val fifteenMinsAgo = java.util.Date(System.currentTimeMillis() - POLLING_INTERVAL_MS)
            
            // For each conversation, sync to check for new messages
            conversations.forEach { conv ->
                try {
                    when (conv) {
                        is Conversation.Group -> conv.group.sync()
                        is Conversation.Dm -> conv.dm.sync()
                    }
                    
                    // Fetch recent messages to show notifications
                    val messages = when (conv) {
                        is Conversation.Group -> conv.group.messages(limit = 10)
                        is Conversation.Dm -> conv.dm.messages(limit = 10)
                        else -> emptyList()
                    }
                    
                    messages.filter { it.sentAt.after(fifteenMinsAgo) }.forEach { msg ->
                        XmtpSyncService.showNewMessageNotification(context, msg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing conversation ${conv.topic}: ${e.message}")
                }
            }
            
            Log.d(TAG, "Polling completed")
        } catch (e: Exception) {
            Log.e(TAG, "Polling failed: ${e.message}")
        }
        
        schedulePolling(context, POLLING_INTERVAL_MS)
    }
}
