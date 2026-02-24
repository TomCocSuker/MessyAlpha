package com.example.messenger

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.navigation.compose.*
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.messenger.ui.*
import com.walletconnect.wcmodal.ui.walletConnectModalGraph
import java.security.SecureRandom
import android.util.Log
import java.net.URLEncoder
import java.net.URLDecoder

import androidx.activity.compose.setContent
import com.google.accompanist.navigation.material.*

class MainActivity : AppCompatActivity() {
    private lateinit var web3AuthManager: Web3AuthManager

    @OptIn(ExperimentalMaterialNavigationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.wtf("MainActivity", "onCreate started")

        // Initialize Web3AuthManager globally for the Activity so onNewIntent can access it
        web3AuthManager = Web3AuthManager(this)

        // Handle Web3Auth intent if app was launched directly from it
        web3AuthManager.web3Auth.setResultUrl(intent?.data)

        // Request MANAGE_EXTERNAL_STORAGE for auto-backup restore after reinstall
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Cannot request file access: ${e.message}")
                }
            }
        }

        try {
            setContent {
                val context = androidx.compose.ui.platform.LocalContext.current
                val bottomSheetNavigator = rememberBottomSheetNavigator()
                val navController = rememberNavController(bottomSheetNavigator)
                val clientState by ClientManager.clientState.collectAsState()
                
                LaunchedEffect(clientState) {
                    if (clientState is ClientManager.ClientState.Ready ||
                        clientState is ClientManager.ClientState.Loading) {
                        navController.navigate("conversation_list") {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    }
                }

                ModalBottomSheetLayout(bottomSheetNavigator) {
                    NavHost(navController = navController, startDestination = "onboarding") {
                        composable("onboarding") {
                            OnboardingScreen(
                                navController = navController,
                                web3AuthManager = web3AuthManager, // Passed explicitly
                                onSignerGenerated = { signer ->
                                    val dbKey = KeyManager.getOrCreateDbEncryptionKey(context.applicationContext, signer.publicIdentity.identifier)
                                    ClientManager.createClient(signer, context.applicationContext, dbKey)
                                }
                            )
                        }
                    composable("conversation_list") {
                        val mainViewModel: MainViewModel = viewModel()
                        ConversationListScreen(
                            viewModel = mainViewModel, 
                            onConversationClick = { topic -> 
                                val encodedTopic = URLEncoder.encode(topic, "UTF-8")
                                navController.navigate("message/${encodedTopic}")
                            },
                            onBackupClick = {
                                navController.navigate("backup")
                            }
                        )
                    }
                    composable(
                        route = "message/{topic}",
                        arguments = listOf(navArgument("topic") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val encodedTopic = backStackEntry.arguments?.getString("topic") ?: return@composable
                        val topic = URLDecoder.decode(encodedTopic, "UTF-8")
                        val convViewModel: ConversationViewModel = viewModel()
                        
                        LaunchedEffect(topic) {
                            convViewModel.setTopic(topic)
                        }
                        
                        MessageScreen(
                            viewModel = convViewModel, 
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("backup") {
                        BackupScreen(onBack = { navController.popBackStack() })
                    }
                    walletConnectModalGraph(navController)
                }
            }
        }
    } catch (t: Throwable) {
            val msg = "MainActivity: Error in setContent: ${t.message}"
            println(msg)
            Log.wtf("MainActivity", msg, t)
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        // Pass the deeply linked URL to Web3Auth to resume the login flow
        if (this::web3AuthManager.isInitialized) {
            web3AuthManager.web3Auth.setResultUrl(intent?.data)
        }
    }
}
