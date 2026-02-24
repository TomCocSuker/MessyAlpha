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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.SupervisorJob

object ClientManager {
    private val _internalClientState = MutableStateFlow<ClientState>(ClientState.Unknown)
    private val _isNetworkAvailable = MutableStateFlow(true) // Default to true, update immediately

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

    /**
     * Called on app startup to restore an existing XMTP session without wallet interaction.
     * If a wallet address is saved and a local DB exists, restores silently.
     */
    fun tryRestoreClient(appContext: Context) {
        this.appContext = appContext
        startNetworkMonitoring(appContext)
        val address = KeyManager.getSavedWalletAddress(appContext) ?: return
        val dbKey = KeyManager.getOrCreateDbEncryptionKey(appContext, address)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val clientOptions = ClientOptions(
                    api = ClientOptions.Api(env = XMTPEnvironment.DEV, isSecure = true),
                    appContext = appContext,
                    dbEncryptionKey = dbKey
                )
                val identity = org.xmtp.android.library.libxmtp.PublicIdentity(
                    org.xmtp.android.library.libxmtp.IdentityKind.ETHEREUM,
                    address
                )
                _client = Client.build(publicIdentity = identity, options = clientOptions)
                _internalClientState.value = ClientState.Ready
                // Auto-sync: pull history if needed, and start live streams
                if (!SyncManager.isInitialSyncDone(appContext)) {
                    SyncManager.startInitialSync()
                }
                SyncManager.startRealtimeStreams()
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
        if (_internalClientState.value is ClientState.Ready || _internalClientState.value is ClientState.Loading) return

        // Immediately set Loading so UI can navigate away from onboarding
        _internalClientState.value = ClientState.Loading

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val clientOptions = ClientOptions(
                    api = ClientOptions.Api(
                        env = XMTPEnvironment.DEV,
                        isSecure = true
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
                        api = ClientOptions.Api(env = XMTPEnvironment.DEV, isSecure = true),
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
                            android.util.Log.d("ClientManager", "Existing DB found, trying Client.build()")
                            Client.build(publicIdentity = signer.publicIdentity, options = effectiveOptions)
                        } catch (t: Throwable) {
                            android.util.Log.d("ClientManager", "Client.build() failed: ${t.message}, falling back to create")
                            BackupManager.clearXmtpDataFiles(appContext)
                            Client.create(signer, effectiveOptions)
                        }
                    }
                    else -> {
                        android.util.Log.d("ClientManager", "Creating new client (restoredFromBackup=${restoredDbKey != null})")
                        Client.create(signer, effectiveOptions)
                    }
                }

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
                SyncManager.startRealtimeStreams()
            } catch (t: Throwable) {
                _internalClientState.value = ClientState.Error(t.message ?: "Unknown Error")
            }
        }
    }

    fun clearClient(context: Context? = null) {
        SyncManager.stop()
        if (context != null) {
            SyncManager.clearSyncState(context)
        }
        _internalClientState.value = ClientState.Unknown
        _client = null
    }

    sealed class ClientState {
        data object Unknown : ClientState()
        data object Loading : ClientState()
        data object Ready : ClientState()
        data object Disconnected : ClientState()
        data class Error(val message: String) : ClientState()
    }
}
