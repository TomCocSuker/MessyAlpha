package com.example.messenger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.example.messenger.R
import com.example.messenger.ClientManager
import com.example.messenger.DpiBypassPrefsManager
import org.xmtp.android.library.SigningKey
import androidx.navigation.NavController
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.example.messenger.WalletConnectManager
import com.example.messenger.WalletConnectSigner
import com.walletconnect.wcmodal.ui.openWalletConnectModal
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun OnboardingScreen(
    navController: NavController,
    web3AuthManager: com.example.messenger.Web3AuthManager,
    onSignerGenerated: (SigningKey) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Store the active session ID when the user clicks the Telegram button
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var isPolling by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, activeSessionId) {
        val observer = LifecycleEventObserver { _, event ->
            // When the user switches back from Telegram to our app, we check the backend
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && activeSessionId != null && !isPolling) {
                isPolling = true
                coroutineScope.launch {
                    try {
                        val (responseCode, resBody, isSuccessful) = withContext(Dispatchers.IO) { 
                            val client = okhttp3.OkHttpClient()
                            val request = okhttp3.Request.Builder()
                                .url("https://myblog2026.xyz/api/auth/status?session_id=${activeSessionId}")
                                .build()
                            val response = client.newCall(request).execute()
                            val bodyText = response.body?.string()
                            Triple(response.code, bodyText, response.isSuccessful)
                        }
                        
                        android.util.Log.d("Auth", "Lifecycle Poll Backend: Code=${responseCode}, Body=$resBody")
                        
                        if (isSuccessful && !resBody.isNullOrEmpty()) {
                            val json = org.json.JSONObject(resBody)
                            if (json.has("token")) {
                                val extractedToken = json.getString("token")
                                android.util.Log.d("Auth", "Token found! Logging in with Web3Auth...")
                                
                                // Reset session so we don't trigger the auth flow twice
                                activeSessionId = null
                                
                                web3AuthManager.loginWithTelegram(extractedToken).whenComplete { privKey, error ->
                                    if (error == null && privKey != null) {
                                        val scwManager = com.example.messenger.ScwManager(privKey)
                                        coroutineScope.launch {
                                            try {
                                                scwManager.deploySmartContractWallet()
                                            } catch (e: Exception) {
                                                android.util.Log.e("SCW", "Deployment warning", e)
                                            }
                                            onSignerGenerated(scwManager.ScwSigningKey())
                                        }
                                    } else {
                                        android.widget.Toast.makeText(context, context.getString(R.string.login_failed, error?.message), android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Auth", "Lifecycle polling HTTP error", e)
                    } finally {
                        isPolling = false
                    }
                }
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.welcome_to_messy),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            // Dynamic Button based on polling state
            if (activeSessionId != null) {
                CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
                Text(
                    stringResource(R.string.waiting_for_telegram),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                OutlinedButton(
                    onClick = { activeSessionId = null },
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 16.dp)
                ) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                // Primary SCW Actions
                Button(
                    onClick = {
                        val sessionId = java.util.UUID.randomUUID().toString()
                        activeSessionId = sessionId
                        
                        // Launch Telegram App via Deep Link Configured in the Bot
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW, 
                            Uri.parse("tg://resolve?domain=MySecureAuth_bot&start=$sessionId")
                        )
                        try {
                            context.startActivity(intent)
                        } catch (e: android.content.ActivityNotFoundException) {
                            android.widget.Toast.makeText(context, context.getString(R.string.telegram_app_not_installed), android.widget.Toast.LENGTH_LONG).show()
                            activeSessionId = null
                        }
                    },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(bottom = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(stringResource(R.string.continue_with_telegram), fontSize = 16.sp)
            }
            } // Close else block

            /* 
            OutlinedButton(
                onClick = {
                    // Placeholder for actual Google/Apple OAuth flow implementation
                    val mockAccount = org.xmtp.android.library.messages.PrivateKeyBuilder()
                    onSignerGenerated(mockAccount)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp)
                    .height(56.dp)
            ) {
                Text(
                    stringResource(R.string.continue_with_google_apple), 
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            */

            // DPI Bypass Toggle
            var dpiEnabled by remember { mutableStateOf(DpiBypassPrefsManager.isEnabled(context)) }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dpi_bypass_toggle),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.dpi_bypass_desc),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = dpiEnabled,
                    onCheckedChange = { 
                        dpiEnabled = it
                        DpiBypassPrefsManager.setEnabled(context, it)
                        ClientManager.refreshDpiBypassState(context)
                    }
                )
            }

            // Secondary EOA Action
            TextButton(
                onClick = {
                    WalletConnectManager.connect { address ->
                        onSignerGenerated(WalletConnectSigner(address))
                    }
                    navController.openWalletConnectModal()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.or_connect_crypto_wallet),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }
    }
}


