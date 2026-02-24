package com.example.messenger

import android.content.Context
import android.net.Uri
import com.web3auth.core.Web3Auth
import com.web3auth.core.types.LoginParams
import com.web3auth.core.types.Network
import com.web3auth.core.types.Provider
import com.web3auth.core.types.Web3AuthOptions
import java.util.concurrent.CompletableFuture

class Web3AuthManager(private val context: Context) {
    val web3Auth: Web3Auth

    init {
        // Configure Custom JWT Verifier for Telegram
        val loginConfigItem = com.web3auth.core.types.LoginConfigItem(
            verifier = "messy-telegram-id",
            typeOfLogin = com.web3auth.core.types.TypeOfLogin.JWT,
            clientId = "BIKEeUHEULrcNymnEzVquqDuWWROeIoWgR2YeXphRDupH2NCSVOKbRIoCKw_IMIEcLDPo21WmfM3XKn1OfSeye0"
        )
        val loginConfig = hashMapOf("jwt" to loginConfigItem)

        // Initialize Web3Auth with the provided Client ID and Custom Config
        val web3AuthOptions = Web3AuthOptions(
            clientId = "BIKEeUHEULrcNymnEzVquqDuWWROeIoWgR2YeXphRDupH2NCSVOKbRIoCKw_IMIEcLDPo21WmfM3XKn1OfSeye0",
            network = Network.SAPPHIRE_DEVNET, // Network must match the dashboard project type
            redirectUrl = Uri.parse("web3auth://com.example.messenger"),
            loginConfig = loginConfig
        )
        web3Auth = Web3Auth(web3AuthOptions, context)
    }

    /**
     * Initiates the Web3Auth login flow using a pre-fetched JWT from the backend.
     * @param idToken The JWT fetched from the developer's Telegram backend.
     */
    fun loginWithTelegram(idToken: String): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        
        // In v9, we check if the privkey is already available if a session exists
        try {
            val key = web3Auth.getPrivkey()
            if (!key.isNullOrEmpty()) {
                future.complete(key)
                return future
            }
        } catch (e: Exception) {
            // Ignore if key is not available
        }

        // Pass the backend JWT into Web3Auth via ExtraLoginOptions
        val extraLoginOptions = com.web3auth.core.types.ExtraLoginOptions(
            id_token = idToken,
            verifierIdField = "sub", // Standard claim for user ID in JWT, adjust if your backend uses a different claim for Telegram ID
            domain = "https://lance-judicial-scrutinizingly.ngrok-free.dev" // Matches your backend domain
        )
        
        val loginParams = LoginParams(
            loginProvider = Provider.JWT,
            extraLoginOptions = extraLoginOptions
        )

        web3Auth.login(loginParams)
            .whenComplete { response, error ->
                if (error == null) {
                    val privKey = web3Auth.getPrivkey()
                    if (!privKey.isNullOrEmpty()) {
                        future.complete(privKey)
                    } else {
                        future.completeExceptionally(Exception("Private key is empty after Web3Auth login"))
                    }
                } else {
                    future.completeExceptionally(error)
                }
            }

        return future
    }

    fun logout(): CompletableFuture<Void> {
        return web3Auth.logout()
    }
}
