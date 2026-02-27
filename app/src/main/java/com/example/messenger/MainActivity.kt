package com.example.messenger

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import android.os.PowerManager
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.net.URLDecoder

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
                
                val vpnIntent by ClientManager.vpnPermissionIntent.collectAsState()
                val vpnLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == RESULT_OK) {
                        Log.i("MainActivity", "VPN permission granted")
                        ClientManager.clearVpnIntent()
                        ClientManager.refreshDpiBypassState(context)
                    } else {
                        Log.w("MainActivity", "VPN permission denied")
                        ClientManager.clearVpnIntent()
                    }
                }

                LaunchedEffect(vpnIntent) {
                    vpnIntent?.let {
                        Log.i("MainActivity", "Launching VPN permission dialog")
                        vpnLauncher.launch(it)
                    }
                }

                LaunchedEffect(clientState) {
                    if (clientState is ClientManager.ClientState.Ready ||
                        clientState is ClientManager.ClientState.Loading) {
                        
                        // Check for conversation in intent (from notification)
                        val extraTopic = intent?.getStringExtra("conversation_topic")
                        if (extraTopic != null) {
                            val encodedTopic = URLEncoder.encode(extraTopic, "UTF-8")
                            navController.navigate("message/${encodedTopic}") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        } else {
                            navController.navigate("conversation_list") {
                                popUpTo("onboarding") { inclusive = true }
                            }
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
                                },
                                onAdvancedDpiClick = {
                                    navController.navigate("advanced_dpi")
                                },
                                onNewGroupClick = {
                                    navController.navigate("create_group")
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
                                onBack = { navController.popBackStack() },
                                onGroupInfoClick = { clickedTopic ->
                                    val encoded = URLEncoder.encode(clickedTopic, "UTF-8")
                                    navController.navigate("group_members/$encoded")
                                }
                            )
                        }
                        composable("backup") {
                            BackupScreen(onBack = { navController.popBackStack() })
                        }
                        composable("advanced_dpi") {
                            AdvancedDpiSettingsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("create_group") {
                            val mainViewModel: MainViewModel = viewModel()
                            CreateGroupScreen(
                                viewModel = mainViewModel,
                                onBack = { navController.popBackStack() },
                                onCreated = { topic ->
                                    val encodedTopic = URLEncoder.encode(topic, "UTF-8")
                                    navController.navigate("message/${encodedTopic}") {
                                        popUpTo("create_group") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable(
                            route = "group_members/{topic}",
                            arguments = listOf(navArgument("topic") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val encodedTopic = backStackEntry.arguments?.getString("topic") ?: return@composable
                            val topic = URLDecoder.decode(encodedTopic, "UTF-8")
                            val convViewModel: ConversationViewModel = viewModel()
                            
                            LaunchedEffect(topic) {
                                convViewModel.setTopic(topic)
                            }
                            
                            GroupMembersScreen(
                                viewModel = convViewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        walletConnectModalGraph(navController)
                    }
                }
            }
            
            checkAndRequestBatteryOptimizations()
            checkAndRequestNotificationPermission()
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

    private fun checkAndRequestBatteryOptimizations() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val packageName = packageName
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error requesting battery optimization exemption: ${e.message}")
            }
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
            }
        }
    }
}
