package com.example.messenger

import android.app.Application
import android.util.Log
import com.walletconnect.android.Core
import com.walletconnect.android.CoreClient
import com.walletconnect.android.relay.ConnectionType
import com.walletconnect.wcmodal.client.Modal
import com.walletconnect.wcmodal.client.WalletConnectModal
import kotlinx.coroutines.*

class MessengerApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d("MessengerApp", "onCreate started")

        try {
            initWalletConnect()
        } catch (t: Throwable) {
            Log.e("MessengerApp", "WalletConnect init crashed, clearing corrupted data", t)
            // Auto-recover from corrupted WalletConnect state
            try {
                BackupManager.clearAllData(this)
            } catch (e: Exception) {
                Log.e("MessengerApp", "Failed to clear data", e)
            }
            // Force a clean restart to re-initialize WalletConnect properly
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Runtime.getRuntime().exit(0)
        }
    }

    private fun initWalletConnect() {
        val projectId = "ab5f030c777b8cb780f7bf651d65bd7f"
        val relayUrl = "wss://relay.walletconnect.com?projectId=$projectId"

        val appMetaData = Core.Model.AppMetaData(
            name = "Messy",
            description = "Android Messenger using XMTP",
            url = "https://xmtp.org",
            icons = listOf("https://avatars.githubusercontent.com/u/82580170?s=48&v=4"),
            redirect = "messenger-wc://request",
        )

        CoreClient.initialize(
            relayServerUrl = relayUrl,
            connectionType = ConnectionType.AUTOMATIC,
            application = this,
            metaData = appMetaData,
            onError = { error ->
                Log.e("MessengerApp", "WalletConnect Core Error: ${error.throwable.message}")
            },
        )

        WalletConnectModal.initialize(
            init = Modal.Params.Init(core = CoreClient),
            onSuccess = {
                Log.d("MessengerApp", "WalletConnect Modal SUCCESS")
                WalletConnectManager.init(applicationContext)
                ClientManager.tryRestoreClient(applicationContext)
            },
            onError = { error ->
                Log.e("MessengerApp", "WalletConnect Modal Error: ${error.throwable.message}")
            },
        )
    }
}
