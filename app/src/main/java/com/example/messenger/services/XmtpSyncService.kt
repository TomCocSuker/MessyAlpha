package com.example.messenger.services

import android.app.*
import android.content.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.messenger.ClientManager
import com.example.messenger.MainActivity
import com.example.messenger.R
import com.example.messenger.SyncManager
import com.example.messenger.receivers.PingReceiver
import kotlinx.coroutines.*
import org.xmtp.android.library.Conversation
import org.xmtp.android.library.libxmtp.DecodedMessage

class XmtpSyncService : Service() {
    companion object {
        private const val TAG = "XmtpSyncService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "xmtp_sync_channel"
        private const val MESSAGE_CHANNEL_ID = "xmtp_messages_channel"
        
        const val ACTION_STOP_SERVICE = "com.example.messenger.STOP_SYNC_SERVICE"
        const val ACTION_REFRESH_STREAMS = "com.example.messenger.REFRESH_STREAMS"

        fun showNewMessageNotification(context: Context, message: DecodedMessage) {
            // Don't show if it's our own message
            try {
                if (message.senderInboxId == ClientManager.client.inboxId) return
            } catch (e: Exception) {
                return
            }
            
            // Suppress notification if this chat is currently open in UI
            if (message.topic == ClientManager.activeConversationTopic) {
                Log.d(TAG, "Suppressed notification for active chat: ${message.topic}")
                return
            }
            
            // Suppress if the chat is muted by the user
            try {
                val address = ClientManager.client.publicIdentity.identifier
                val mutedTopics = com.example.messenger.ConversationPrefsManager.getMutedTopics(context, address)
                if (mutedTopics.contains(message.topic)) {
                    Log.d(TAG, "Suppressed notification for muted chat: ${message.topic}")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check muted topics: ${e.message}")
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("conversation_topic", message.topic)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.new_message_notification_title))
                .setContentText(message.body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(Notification.DEFAULT_ALL)
                .build()

            notificationManager.notify(message.id.hashCode(), notification)
            Log.d(TAG, "Notification shown for message ID: ${message.id}")
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null
    private var isScreenOn = true
    private var isNetworkAvailable = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen ON: Switching to Stream mode")
                    isScreenOn = true
                    updateConnectionState()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF: Switching to Polling mode")
                    isScreenOn = false
                    updateConnectionState()
                }
            }
        }
    }

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available")
            isNetworkAvailable = true
            updateConnectionState()
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost")
            isNetworkAvailable = false
            updateConnectionState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        // Register screen state receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        // Register network callback
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        
        // Initial state check
        isNetworkAvailable = isCurrentlyOnline()
        updateConnectionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Always update state to ensure we are using the latest client/network status
        if (intent?.action == ACTION_REFRESH_STREAMS) {
            stopStreams() // Force-stop old streams that might be using a stale client instance
        }
        updateConnectionState()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        unregisterReceiver(screenReceiver)
        connectivityManager.unregisterNetworkCallback(networkCallback)
        stopStreams()
        cancelAllScheduling()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateConnectionState() {
        if (!isNetworkAvailable) {
            Log.d(TAG, "No network: Stopping everything")
            stopStreams()
            cancelAllScheduling()
            return
        }

        if (isScreenOn) {
            // High priority: Streams + Smart Pings
            cancelPolling()
            startStreams()
            scheduleSmartPings()
        } else {
            // Low priority: Polling
            stopStreams()
            cancelSmartPings()
            schedulePolling()
        }
    }

    private fun startStreams() {
        if (streamJob?.isActive == true) return
        
        Log.d(TAG, "Starting XMTP streams")
        streamJob = serviceScope.launch {
            try {
                val client = ClientManager.client
                
                // Stream messages
                launch {
                    client.conversations.streamAllMessages().collect { message ->
                        Log.d(TAG, "New message received in stream! Topic: ${message.topic}, Body: ${message.body}")
                        showNewMessageNotification(this@XmtpSyncService, message)
                    }
                }
                
                // Stream conversations
                launch {
                    client.conversations.stream().collect { conversation ->
                        Log.d(TAG, "New conversation received in stream: ${conversation.topic}")
                        // Maybe show notification about new chat?
                    }
                }
                Log.d(TAG, "XMTP stream collectors are active")
            } catch (e: Exception) {
                Log.e(TAG, "Stream error: ${e.message}")
                // In case of error, we might want to retry after a delay if network is still up
                delay(5000)
                if (isNetworkAvailable && isScreenOn) {
                    startStreams()
                }
            }
        }
    }

    private fun stopStreams() {
        Log.d(TAG, "Stopping XMTP streams")
        streamJob?.cancel()
        streamJob = null
    }

    private fun scheduleSmartPings() {
        Log.d(TAG, "Scheduling smart pings (4 min)")
        PingReceiver.schedulePing(this, PingReceiver.PING_INTERVAL_MS)
    }

    private fun cancelSmartPings() {
        Log.d(TAG, "Canceling smart pings")
        // No-op if we just let the next one fail or reschedule itself based on state
    }

    private fun schedulePolling() {
        Log.d(TAG, "Scheduling polling (15 min)")
        PingReceiver.schedulePolling(this, PingReceiver.POLLING_INTERVAL_MS)
    }

    private fun cancelPolling() {
        Log.d(TAG, "Canceling polling")
    }

    private fun cancelAllScheduling() {
        PingReceiver.cancelAll(this)
    }

    private fun isCurrentlyOnline(): Boolean {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.xmtp_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.xmtp_service_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            
            val messageChannel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "XMTP Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new XMTP messages"
                enableLights(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(messageChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.xmtp_service_listening))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

}
