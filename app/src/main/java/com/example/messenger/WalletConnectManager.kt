package com.example.messenger

import com.walletconnect.wcmodal.client.Modal
import com.walletconnect.wcmodal.client.WalletConnectModal
import kotlinx.coroutines.CompletableDeferred
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.Context

object WalletConnectManager : WalletConnectModal.ModalDelegate {
    private var onConnected: ((String) -> Unit)? = null
    private var sessionTopic: String? = null
    private var connectedAddress: String? = null
    private var appContext: Context? = null
    
    // For signing requests
    private var signDeferred: CompletableDeferred<ByteArray>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        println("WalletConnectManager: init called")
        WalletConnectModal.setDelegate(this)
        
        // Using optionalNamespaces instead of requiredNamespaces is more robust 
        // as it doesn't fail if the wallet doesn't support exactly what is requested.
        val sessionParams = Modal.Params.SessionParams(
            requiredNamespaces = emptyMap(),
            optionalNamespaces = mapOf(
                "eip155" to Modal.Model.Namespace.Proposal(
                    chains = listOf("eip155:1"), // Ethereum Mainnet
                    methods = listOf("personal_sign", "eth_sendTransaction"),
                    events = listOf("accountsChanged", "chainChanged")
                )
            ),
            properties = null
        )
        
        try {
            WalletConnectModal.setSessionParams(sessionParams)
            println("WalletConnectManager: setSessionParams success")
        } catch (e: Exception) {
            println("WalletConnectManager: setSessionParams failure: ${e.message}")
        }
    }

    fun connect(callback: (String) -> Unit) {
        onConnected = callback
    }

    override fun onSessionApproved(approvedSession: Modal.Model.ApprovedSession) {
        sessionTopic = approvedSession.topic
        val fullAccount = approvedSession.accounts.firstOrNull()
        connectedAddress = fullAccount?.split(":")?.lastOrNull()
        
        Log.d("WalletConnectManager", "Session approved! Address: $connectedAddress")
        
        connectedAddress?.let { address ->
            // Persist address for auto-restore on next app launch
            appContext?.let { ctx -> KeyManager.saveWalletAddress(ctx, address) }

            // Dispatch to main thread
            Handler(Looper.getMainLooper()).post {
                Log.d("WalletConnectManager", "Invoking onConnected on main thread")
                onConnected?.invoke(address)
            }
        }
    }

    override fun onSessionRejected(rejectedSession: Modal.Model.RejectedSession) {
        sessionTopic = null
        connectedAddress = null
    }

    override fun onSessionUpdate(updatedSession: Modal.Model.UpdatedSession) {}
    override fun onSessionEvent(sessionEvent: Modal.Model.SessionEvent) {}
    override fun onSessionExtend(session: Modal.Model.Session) {}
    
    override fun onSessionDelete(deletedSession: Modal.Model.DeletedSession) {
        sessionTopic = null
        connectedAddress = null
    }

    override fun onSessionRequestResponse(response: Modal.Model.SessionRequestResponse) {
        val deferred = signDeferred ?: return
        if (response.result is Modal.Model.JsonRpcResponse.JsonRpcResult) {
            val signatureHex = (response.result as Modal.Model.JsonRpcResponse.JsonRpcResult).result
            try {
                deferred.complete(hexToToByteArray(signatureHex))
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        } else if (response.result is Modal.Model.JsonRpcResponse.JsonRpcError) {
            deferred.completeExceptionally(Exception((response.result as Modal.Model.JsonRpcResponse.JsonRpcError).message))
        }
        signDeferred = null
    }

    override fun onProposalExpired(proposal: Modal.Model.ExpiredProposal) {}
    override fun onRequestExpired(request: Modal.Model.ExpiredRequest) {}

    override fun onConnectionStateChange(state: Modal.Model.ConnectionState) {}
    override fun onError(error: Modal.Model.Error) {
        println("WalletConnect Error: ${error.throwable.message}")
    }

    suspend fun signMessage(message: String): ByteArray {
        val topic = sessionTopic ?: throw Exception("No active session")
        val address = connectedAddress ?: throw Exception("No connected address")
        
        val currentDeferred = CompletableDeferred<ByteArray>()
        signDeferred = currentDeferred
        
        // personal_sign params: [message_hex, address]
        val messageHex = "0x" + message.toByteArray().joinToString("") { "%02x".format(it) }
        val params = "[\"$messageHex\", \"$address\"]"
        
        val request = Modal.Params.Request(
            sessionTopic = topic,
            chainId = "eip155:1",
            method = "personal_sign",
            params = params
        )
        
        WalletConnectModal.request(request) { error ->
            currentDeferred.completeExceptionally(error.throwable)
        }
        
        return currentDeferred.await()
    }

    private fun hexToToByteArray(hex: String): ByteArray {
        val cleanHex = hex.removePrefix("0x")
        return ByteArray(cleanHex.length / 2) {
            cleanHex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}
