package com.example.messenger

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xmtp.android.library.Client
import org.xmtp.android.library.ClientOptions
import org.xmtp.android.library.XMTPEnvironment
import org.xmtp.android.library.SigningKey
import org.xmtp.android.library.VpnManager
import org.xmtp.android.library.ProxyManager
import org.xmtp.android.library.push.StrategyRegistry
import org.xmtp.android.library.push.BypassStrategyId
import org.xmtp.android.library.codecs.AttachmentCodec
import org.xmtp.android.library.codecs.ContentTypeAttachment
import org.xmtp.android.library.codecs.RemoteAttachmentCodec
import org.xmtp.android.library.codecs.ContentTypeRemoteAttachment
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.SupervisorJob
import com.example.messenger.services.XmtpSyncService
import android.content.Intent
import android.os.Build

object ClientManager {
    private val _internalClientState = MutableStateFlow<ClientState>(ClientState.Unknown)
    private val _isNetworkAvailable = MutableStateFlow(true) // Default to true, update immediately
    private val _vpnPermissionIntent = MutableStateFlow<Intent?>(null)
    val vpnPermissionIntent: StateFlow<Intent?> = _vpnPermissionIntent.asStateFlow()

    val currentStrategies: StateFlow<Map<String, BypassStrategyId>> = StrategyRegistry.currentStrategies
    var activeConversationTopic: String? = null

    // Expose a combined state that reflects both initialization and connectivity
    val clientState: StateFlow<ClientState> = combine(_internalClientState, _isNetworkAvailable) { state, online ->
        if (state is ClientState.Ready && !online) {
            ClientState.Disconnected
        } else {
            state
        }
    }.stateIn(
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
        started = SharingStarted.Eagerly,
        initialValue = ClientState.Unknown
    )

    private var _client: Client? = null
    val client: Client
        get() = _client ?: throw IllegalStateException("Client not initialized")

    var appContext: Context? = null
        private set

    fun isClientInitialized(): Boolean = _client != null

    fun clearVpnIntent() {
        _vpnPermissionIntent.value = null
    }

    /**
     * Checks the current preference and either requests permission or stops/starts the VPN.
     * Called when the toggle is changed or on app startup.
     */
    fun refreshDpiBypassState(context: Context) {
        val useFragmentation = DpiBypassPrefsManager.isEnabled(context)
        android.util.Log.i("ClientManager", "Refreshing DPI Bypass state: enabled=$useFragmentation")
        
        if (useFragmentation) {
            val intent = VpnManager.prepareVpn(context)
            if (intent != null) {
                android.util.Log.w("ClientManager", "VPN permission required")
                _vpnPermissionIntent.value = intent
            } else {
                android.util.Log.i("ClientManager", "VPN permission already granted, starting VPN immediately")
                // Start VPN immediately with correct port
                val host = XMTPEnvironment.DEV.getValue() // Default to DEV for now, as in tryRestoreClient
                val proxy = ProxyManager.getProxy(host, 443)
                VpnManager.startVpn(context, proxy.localPort)

                if (_client != null) {
                    android.util.Log.i("ClientManager", "Re-initializing client to use new DPI bypass setting")
                    stopSyncService(context)
                    tryRestoreClient(context)
                }
            }
        } else {
            android.util.Log.i("ClientManager", "Stopping DPI Bypass VPN")
            VpnManager.stopVpn(context)
            if (_client != null) {
                android.util.Log.i("ClientManager", "Re-initializing client to disable DPI bypass")
                stopSyncService(context)
                tryRestoreClient(context)
            }
        }
    }

    /**
     * Manually cycles the DPI bypass strategy for the current environment.
     */
    fun cycleDpiStrategy(context: Context) {
        val host = XMTPEnvironment.DEV.getValue()
        try {
            android.util.Log.i("ClientManager", "Manual DPI strategy cycle for host: $host")
            org.xmtp.android.library.push.StrategyRegistry.cycleStrategy(host)
            android.widget.Toast.makeText(context, "DPI Strategy Cycled", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("ClientManager", "Failed to manually cycle strategy: ${e.message}")
        }
    }

    /**
     * Executes a block of code with exponential backoff retries for transport errors.
     * This helps handle transient network issues where the underlying DPI bypass layer
     * is still "probing" strategies.
     */
    private suspend fun <T> withRetry(
        maxRetries: Int = 5,
        initialDelay: Long = 500,
        factor: Double = 1.5,
        host: String = "grpc.xmtp.org",
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        repeat(maxRetries - 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                val msg = e.message ?: ""
                val isTransportError = msg.contains("transport error", ignoreCase = true) ||
                                       msg.contains("get_nodes", ignoreCase = true) ||
                                       msg.contains("PayerApi", ignoreCase = true)

                if (!isTransportError) {
                    throw e // Rethrow database/encryption errors immediately
                }

                // Cycle DPI bypass strategy if fragmentation is enabled
                try {
                    android.util.Log.i("ClientManager", "Transport error on attempt ${attempt + 1}. Cycling DPI bypass strategy for host: $host")
                    org.xmtp.android.library.push.StrategyRegistry.cycleStrategy(host)
                } catch (ex: Exception) {
                    android.util.Log.e("ClientManager", "Failed to cycle strategy for $host: ${ex.message}")
                }

                android.util.Log.w("ClientManager", "Transport error (attempt ${attempt + 1}/${maxRetries}), retrying in ${currentDelay}ms: $msg")
                kotlinx.coroutines.delay(currentDelay)
                // Add jitter to avoid thundering herd
                currentDelay = (currentDelay * factor).toLong() + (0..200).random()
            }
        }
        return block() // Last attempt
    }

    /**
     * Called on app startup to restore an existing XMTP session without wallet interaction.
     * If a wallet address is saved and a local DB exists, restores silently.
     */
    fun tryRestoreClient(appContext: Context) {
        this.appContext = appContext
        startNetworkMonitoring(appContext)

        // Iteration 9: Enable TRACE logging for native layer
        try {
            android.util.Log.i("ClientManager", "Enabling TRACE logging for Rust core")
            org.xmtp.android.library.Client.activatePersistentLibXMTPLogWriter(
                appContext = appContext,
                logLevel = uniffi.xmtpv3.FfiLogLevel.TRACE,
                rotationSchedule = uniffi.xmtpv3.FfiLogRotation.HOURLY,
                maxFiles = 6
            )
        } catch (e: Exception) {
            android.util.Log.e("ClientManager", "Failed to enable Rust logs: ${e.message}")
        }

        val address = KeyManager.getSavedWalletAddress(appContext) ?: return
        val dbKey = KeyManager.getOrCreateDbEncryptionKey(appContext, address)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val useFragmentation = DpiBypassPrefsManager.isEnabled(appContext)
                
                if (useFragmentation) {
                    val intent = VpnManager.prepareVpn(appContext)
                    if (intent != null) {
                        android.util.Log.w("ClientManager", "VPN permission required for DPI bypass")
                        _vpnPermissionIntent.value = intent
                        // We don't abort, but the VPN won't start until permission is granted.
                    } else {
                        android.util.Log.i("ClientManager", "Starting VPN for restored client")
                        val host = XMTPEnvironment.DEV.getValue()
                        val proxy = ProxyManager.getProxy(host, 443)
                        VpnManager.startVpn(appContext, proxy.localPort)
                    }
                }

                val clientOptions = ClientOptions(
                    api = ClientOptions.Api(
                        env = XMTPEnvironment.DEV,
                        isSecure = true,
                        useFragmentation = useFragmentation
                    ),
                    appContext = appContext,
                    dbEncryptionKey = dbKey
                )
                val identity = org.xmtp.android.library.libxmtp.PublicIdentity(
                    org.xmtp.android.library.libxmtp.IdentityKind.ETHEREUM,
                    address
                )
                val host = clientOptions.api.env.getValue()
                _client = withRetry(host = host) {
                    Client.build(publicIdentity = identity, options = clientOptions)
                }
                Client.register(AttachmentCodec())
                Client.register(RemoteAttachmentCodec())
                _internalClientState.value = ClientState.Ready
                // Auto-sync: pull history if needed, and start live streams
                if (!SyncManager.isInitialSyncDone(appContext)) {
                    SyncManager.startInitialSync()
                }
                startSyncService(appContext)
            } catch (t: Throwable) {
                // No local installation — show onboarding normally
                android.util.Log.d("ClientManager", "No existing session: ${t.message}")
            }
        }
    }

    private fun startNetworkMonitoring(context: Context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // Initial check
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        _isNetworkAvailable.value = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isNetworkAvailable.value = true
            }

            override fun onLost(network: Network) {
                _isNetworkAvailable.value = false
            }
            
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                _isNetworkAvailable.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        })
    }

    /**
     * Initializes the client using a real WalletSigner.
     * This registers the user on the XMTP network using their wallet's signature.
     */
    fun createClient(signer: SigningKey, appContext: Context, dbEncryptionKey: ByteArray) {
        this.appContext = appContext
        startNetworkMonitoring(appContext)

        // Iteration 9: Enable TRACE logging for native layer
        try {
            android.util.Log.i("ClientManager", "Enabling TRACE logging for Rust core")
            org.xmtp.android.library.Client.activatePersistentLibXMTPLogWriter(
                appContext = appContext,
                logLevel = uniffi.xmtpv3.FfiLogLevel.TRACE,
                rotationSchedule = uniffi.xmtpv3.FfiLogRotation.HOURLY,
                maxFiles = 6
            )
        } catch (e: Exception) {
            android.util.Log.e("ClientManager", "Failed to enable Rust logs: ${e.message}")
        }

        if (_internalClientState.value is ClientState.Ready || _internalClientState.value is ClientState.Loading) return

        // Immediately set Loading so UI can navigate away from onboarding
        _internalClientState.value = ClientState.Loading

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val useFragmentation = DpiBypassPrefsManager.isEnabled(appContext)

                if (useFragmentation) {
                    val intent = VpnManager.prepareVpn(appContext)
                    if (intent != null) {
                        android.util.Log.w("ClientManager", "VPN permission required for DPI bypass")
                        _vpnPermissionIntent.value = intent
                    }
                }

                val clientOptions = ClientOptions(
                    api = ClientOptions.Api(
                        env = XMTPEnvironment.DEV,
                        isSecure = true,
                        useFragmentation = useFragmentation
                    ),
                    appContext = appContext,
                    dbEncryptionKey = dbEncryptionKey
                )

                // Check if an XMTP local DB already exists for this identity (not a reinstall).
                val walletAddress = signer.publicIdentity.identifier
                val xmtpDbDir = java.io.File(appContext.filesDir, "xmtp_db")
                val hadExistingDb = xmtpDbDir.exists() && xmtpDbDir.listFiles()?.any {
                    it.name.endsWith(".db3")
                } == true

                // If no local DB, try to restore from auto-backup.
                // autoRestore() returns the raw DB encryption key from db_key.bin in the ZIP,
                // or null if no backup found (or old backup format without db_key.bin).
                var restoredDbKey: ByteArray? = null
                if (!hadExistingDb) {
                    android.util.Log.d("ClientManager", "No local XMTP DB, checking for auto-backup...")
                    restoredDbKey = BackupManager.autoRestore(appContext, walletAddress)
                    if (restoredDbKey != null) {
                        android.util.Log.d("ClientManager", "Auto-backup restored with key from db_key.bin!")
                    } else {
                        // Old backup format (no db_key.bin) — DB files were restored but we can't
                        // open them without the old key. Clear them so Client.create() starts clean.
                        android.util.Log.d("ClientManager", "No db_key.bin in backup — clearing any restored DB files to avoid PRAGMA key mismatch")
                        BackupManager.clearXmtpDataFiles(appContext)
                    }
                }

                // Use restored key from db_key.bin — NOT KeyManager (stale in-memory SharedPrefs cache).
                val effectiveOptions = if (restoredDbKey != null) {
                    android.util.Log.d("ClientManager", "Using key from backup db_key.bin for DB open")
                    ClientOptions(
                        api = ClientOptions.Api(
                            env = XMTPEnvironment.DEV,
                            isSecure = true,
                            useFragmentation = useFragmentation
                        ),
                        appContext = appContext,
                        dbEncryptionKey = restoredDbKey
                    )
                } else {
                    clientOptions
                }

                _client = when {
                    hadExistingDb -> {
                        // Normal case: same installation still exists → Client.build()
                        try {
                            android.util.Log.d("ClientManager", "Existing DB found, trying Client.build() with retries")
                            val host = effectiveOptions.api.env.getValue()
                            withRetry(host = host) {
                                Client.build(publicIdentity = signer.publicIdentity, options = effectiveOptions)
                            }
                        } catch (t: Throwable) {
                            val msg = t.message ?: ""
                            val isTransportError = msg.contains("transport error", ignoreCase = true) ||
                                                   msg.contains("get_nodes", ignoreCase = true)

                            if (isTransportError) {
                                // If it's still a transport error after retries, don't clear data yet.
                                // It just means we can't connect right now.
                                throw t
                            }

                            android.util.Log.d("ClientManager", "Client.build() failed with persistent error: $msg. Clearing and falling back to create")
                            BackupManager.clearXmtpDataFiles(appContext)
                            val host = effectiveOptions.api.env.getValue()
                            withRetry(host = host) { Client.create(signer, effectiveOptions) }
                        }
                    }
                    else -> {
                        android.util.Log.d("ClientManager", "Creating new client (restoredFromBackup=${restoredDbKey != null})")
                        val host = effectiveOptions.api.env.getValue()
                        withRetry(host = host) { Client.create(signer, effectiveOptions) }
                    }
                }
                Client.register(AttachmentCodec())
                Client.register(RemoteAttachmentCodec())

                // CRITICAL: if we restored a backup and used restoredDbKey to open the DB,
                // the SharedPrefs cache still holds the randomly-generated key that was created
                // at session startup (before restore). Force-overwrite it with the actual key
                // now so that autoBackup() stores the CORRECT key in db_key.bin.
                if (restoredDbKey != null) {
                    android.util.Log.d("ClientManager", "Force-setting DB key in SharedPrefs to match restored key")
                    KeyManager.forceSetDbEncryptionKey(appContext, walletAddress, restoredDbKey)
                }

                // Save the wallet address to SharedPreferences so tryRestoreClient works on next launch
                KeyManager.saveWalletAddress(appContext, walletAddress)

                _internalClientState.value = ClientState.Ready
                // After first login or reinstall: pull full history from XMTP network
                SyncManager.startInitialSync()
                startSyncService(appContext)
            } catch (t: Throwable) {
                _internalClientState.value = ClientState.Error(t.message ?: "Unknown Error")
            }
        }
    }

    fun clearClient(context: Context? = null) {
        SyncManager.stop()
        if (context != null) {
            SyncManager.clearSyncState(context)
            stopSyncService(context)
            VpnManager.stopVpn(context)
        }
        _vpnPermissionIntent.value = null
        _internalClientState.value = ClientState.Unknown
        _client = null
    }

    private fun startSyncService(context: Context) {
        val intent = Intent(context, XmtpSyncService::class.java).apply {
            action = XmtpSyncService.ACTION_REFRESH_STREAMS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopSyncService(context: Context) {
        val intent = Intent(context, XmtpSyncService::class.java)
        context.stopService(intent)
    }

    sealed class ClientState {
        data object Unknown : ClientState()
        data object Loading : ClientState()
        data object Ready : ClientState()
        data object Disconnected : ClientState()
        data class Error(val message: String) : ClientState()
    }
}
