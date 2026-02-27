package com.example.messenger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.xmtp.android.library.Conversation
import org.xmtp.android.library.libxmtp.DecodedMessage
import org.xmtp.android.library.codecs.Attachment
import org.xmtp.android.library.codecs.AttachmentCodec
import org.xmtp.android.library.codecs.ContentTypeAttachment
import org.xmtp.android.library.codecs.ContentTypeRemoteAttachment
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.example.messenger.ContactManager
import com.example.messenger.BackupManager
import com.example.messenger.ClientManager
import com.example.messenger.KeyManager
import com.example.messenger.SyncManager
import com.example.messenger.FileUtils
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import org.xmtp.android.library.push.StrategyRegistry
import org.xmtp.android.library.push.CustomStrategyConfig
import org.xmtp.android.library.push.BypassStrategyId
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import com.example.messenger.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Info

// Helper to extract bitmaps from a list of messages
fun extractBitmaps(messages: List<DecodedMessage>): List<android.graphics.Bitmap> {
    val list = mutableListOf<android.graphics.Bitmap>()
    messages.forEach { msg ->
        val content: Any? = msg.content()
        if (content is Attachment) {
            try {
                val data = content.data.toByteArray()
                val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bmp != null) list.add(bmp)
            } catch (e: Exception) {
                android.util.Log.e("Screens", "Error extracting mosaic bitmap: ${e.message}")
            }
        }
    }
    return list
}

fun extractBitmap(message: DecodedMessage): android.graphics.Bitmap? {
    val content: Any? = message.content()
    if (content is Attachment) {
        try {
            val data = content.data.toByteArray()
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (e: Exception) {
            android.util.Log.e("Screens", "Error extracting single bitmap: ${e.message}")
        }
    }
    return null
}

// Helper function to get a color based on the XMTP connection state
@Composable
fun getConnectionColor(state: ClientManager.ClientState): Color {
    return when (state) {
        is ClientManager.ClientState.Ready -> Color(0xFF4CAF50) // Material Green
        is ClientManager.ClientState.Loading -> Color(0xFFFFC107) // Material Yellow
        is ClientManager.ClientState.Disconnected -> Color(0xFFFFC107) // Yellow for offline
        is ClientManager.ClientState.Error -> Color(0xFFF44336) // Material Red
        is ClientManager.ClientState.Unknown -> Color(0xFF9E9E9E) // Material Gray
    }
}

// ..
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    viewModel: MainViewModel,
    onConversationClick: (String) -> Unit,
    onBackupClick: () -> Unit,
    onAdvancedDpiClick: () -> Unit,
    onNewGroupClick: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsState()
    val pinnedTopics by viewModel.pinnedTopics.collectAsState()
    val mutedTopics by viewModel.mutedTopics.collectAsState()
    val savedMessagesTopic by viewModel.savedMessagesTopic.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentAddress by viewModel.currentAddress.collectAsState()
    val clientState by ClientManager.clientState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    var showNewChatDialog by remember { mutableStateOf(false) }
    var newChatAddress by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    
    var showProfileDialog by remember { mutableStateOf(false) }
    var profileNameInput by remember { mutableStateOf("") }
    var myProfileName by remember { mutableStateOf(ContactManager.getMyProfileName(context)) }
    var isProfileSharingEnabled by remember { mutableStateOf(ContactManager.isProfileSharingEnabled(context)) }

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    val selectedTabIndex by viewModel.selectedTabIndex.collectAsState()
    
    var dpiEnabled by remember { mutableStateOf(com.example.messenger.DpiBypassPrefsManager.isEnabled(context)) }
    val currentStrategies by ClientManager.currentStrategies.collectAsState()
    val activeEnvironment = org.xmtp.android.library.XMTPEnvironment.DEV.getValue() // Assuming DEV for now
    val activeStrategy = currentStrategies[activeEnvironment] ?: org.xmtp.android.library.push.BypassStrategyId.NONE

    val qrScannerLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            newChatAddress = result.contents
            errorMessage = null
        }
    }

    if (showProfileDialog) {
        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text(stringResource(R.string.display_name)) },
            text = {
                Column {
                    TextField(
                        value = profileNameInput,
                        onValueChange = { profileNameInput = it },
                        label = { Text(stringResource(R.string.your_name_label)) },
                        placeholder = { Text(stringResource(R.string.your_name_placeholder)) }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.share_profile_name), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.share_profile_name_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isProfileSharingEnabled,
                            onCheckedChange = { isProfileSharingEnabled = it }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ContactManager.saveMyProfileName(context, profileNameInput)
                    ContactManager.setProfileSharingEnabled(context, isProfileSharingEnabled)
                    myProfileName = ContactManager.getMyProfileName(context)
                    showProfileDialog = false
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showProfileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.log_out_title)) },
            text = { Text(stringResource(R.string.log_out_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    KeyManager.clearSession(context)
                    ClientManager.clearClient(context)
                    
                    // Restart the Activity safely to clear any static or ViewModel states
                    val intent = android.content.Intent(context, com.example.messenger.MainActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    context.startActivity(intent)
                    (context as? android.app.Activity)?.finish()
                }) {
                    Text(stringResource(R.string.log_out), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("My QR Code") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    val qrBitmap = rememberQrBitmap(content = currentAddress)
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "My QR Code",
                            modifier = Modifier.size(250.dp)
                        )
                    } else {
                        Text("Failed to generate QR code")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = currentAddress,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clickable { clipboardManager.setText(AnnotatedString(currentAddress)) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { 
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(getConnectionColor(clientState), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (!myProfileName.isNullOrBlank()) myProfileName!! else stringResource(R.string.messages_title))
                            IconButton(onClick = { 
                                profileNameInput = myProfileName ?: ""
                                showProfileDialog = true 
                            }, modifier = Modifier.size(24.dp).padding(start = 4.dp)) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit Profile", modifier = Modifier.size(16.dp))
                            }
                        }
                        if (currentAddress.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.my_addr, currentAddress.take(6), currentAddress.takeLast(4)),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.clickable { 
                                    clipboardManager.setText(AnnotatedString(currentAddress)) 
                                }
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.syncHistory() },
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("\u21BB", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Options")
                        }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.my_qr_code)) },
                                onClick = {
                                    showOptionsMenu = false
                                    showQrDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.backup_and_restore_title)) },
                                onClick = {
                                    showOptionsMenu = false
                                    onBackupClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { 
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(stringResource(R.string.dpi_bypass_toggle))
                                            if (dpiEnabled && activeStrategy != org.xmtp.android.library.push.BypassStrategyId.NONE) {
                                                Text(
                                                    text = "Strategy: ${activeStrategy.name}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Switch(
                                            checked = dpiEnabled,
                                            onCheckedChange = { 
                                                dpiEnabled = it
                                                com.example.messenger.DpiBypassPrefsManager.setEnabled(context, it)
                                                ClientManager.refreshDpiBypassState(context)
                                            },
                                            modifier = Modifier.scale(0.8f)
                                        )
                                    }
                                },
                                onClick = {
                                    // Switch handles its own change
                                }
                            )
                            if (dpiEnabled) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cycle_dpi_strategy)) },
                                    onClick = {
                                        showOptionsMenu = false
                                        ClientManager.cycleDpiStrategy(context)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.advanced_dpi_settings)) },
                                    onClick = {
                                        showOptionsMenu = false
                                        onAdvancedDpiClick()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.language_english)) },
                                onClick = {
                                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                        androidx.core.os.LocaleListCompat.forLanguageTags("en")
                                    )
                                    showOptionsMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.language_russian)) },
                                onClick = {
                                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                        androidx.core.os.LocaleListCompat.forLanguageTags("ru")
                                    )
                                    showOptionsMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.log_out)) },
                                onClick = {
                                    showOptionsMenu = false
                                    showLogoutDialog = true
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            var fabExpanded by remember { mutableStateOf(false) }
            Box {
                FloatingActionButton(onClick = { fabExpanded = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add")
                }
                DropdownMenu(
                    expanded = fabExpanded,
                    onDismissRequest = { fabExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_conversation)) },
                        onClick = {
                            fabExpanded = false
                            showNewChatDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_group)) },
                        onClick = {
                            fabExpanded = false
                            onNewGroupClick()
                        }
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.tab_chats)) },
                    label = { Text(stringResource(R.string.tab_chats)) },
                    selected = selectedTabIndex == 0,
                    onClick = { viewModel.setSelectedTabIndex(0) }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.List, contentDescription = stringResource(R.string.tab_groups)) },
                    label = { Text(stringResource(R.string.tab_groups)) },
                    selected = selectedTabIndex == 1,
                    onClick = { viewModel.setSelectedTabIndex(1) }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Show banner while XMTP is still initializing in the background
            if (clientState is ClientManager.ClientState.Loading) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.setting_up_account), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (clientState is ClientManager.ClientState.Error) {
                val err = (clientState as ClientManager.ClientState.Error).message
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.error_prefix, err), style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                BackupManager.clearAllData(context)
                                // Restart the app
                                val pm = context.packageManager
                                val intent = pm.getLaunchIntentForPackage(context.packageName)
                                intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                                Runtime.getRuntime().exit(0)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.clear_data_restart))
                        }
                    }
                }
            }
            // Sync progress banner
            if (syncState is SyncManager.SyncState.Syncing) {
                val syncing = syncState as SyncManager.SyncState.Syncing
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(syncing.phase, style = MaterialTheme.typography.bodySmall)
                        }
                        if (syncing.detail.isNotBlank()) {
                            Text(
                                syncing.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        if (syncing.totalCount > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = syncing.syncedCount.toFloat() / syncing.totalCount.toFloat(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            // Sync result banner (success / no conversations found)
            if (syncState is SyncManager.SyncState.SyncResult) {
                val result = syncState as SyncManager.SyncState.SyncResult
                Surface(
                    color = if (result.success)
                        Color(0xFF2E7D32).copy(alpha = 0.15f)
                    else
                        Color(0xFFF9A825).copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        result.messagesPhase,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            if (syncState is SyncManager.SyncState.Error) {
                val err = (syncState as SyncManager.SyncState.Error).message
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    val filteredConversations = conversations.filter {
                        val isSavedMsgs = (savedMessagesTopic != null && it.topic == savedMessagesTopic) || 
                                          (it is Conversation.Group && it.group.name == "Saved Messages")
                        if (selectedTabIndex == 0) {
                            it is Conversation.Dm || isSavedMsgs
                        } else {
                            it is Conversation.Group && !isSavedMsgs
                        }
                    }
                    val sortedConversations = filteredConversations.sortedByDescending { pinnedTopics.contains(it.topic) }
                    items(sortedConversations) { conversation ->
                        ConversationItem(
                            conversation = conversation, 
                            isPinned = pinnedTopics.contains(conversation.topic),
                            isMuted = mutedTopics.contains(conversation.topic),
                            onClick = onConversationClick,
                            onRename = { /* Handled completely inside ConversationItem now for simplicity, or we can just leave it */ },
                            onPinToggle = { viewModel.togglePin(conversation.topic) },
                            onMuteToggle = { viewModel.toggleMute(conversation.topic) },
                            onDelete = { viewModel.deleteConversation(conversation.topic) }
                        )
                    }
                }
            }
        }

        if (showNewChatDialog) {
            AlertDialog(
                onDismissRequest = { 
                    showNewChatDialog = false
                    errorMessage = null 
                },
                title = { Text(stringResource(R.string.new_conversation)) },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                value = newChatAddress,
                                onValueChange = { 
                                    newChatAddress = it
                                    errorMessage = null
                                },
                                label = { Text(stringResource(R.string.wallet_address_or_ens)) },
                                placeholder = { Text(stringResource(R.string.wallet_address_placeholder)) },
                                isError = errorMessage != null,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    val options = ScanOptions()
                                    options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    options.setPrompt(context.getString(R.string.scan_qr_code))
                                    options.setBeepEnabled(false)
                                    options.setOrientationLocked(true)
                                    options.setCaptureActivity(com.example.messenger.PortraitCaptureActivity::class.java)
                                    qrScannerLauncher.launch(options)
                                }
                            ) {
                                Text("📷", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        if (errorMessage != null) {
                            Text(
                                text = errorMessage!!, 
                                color = MaterialTheme.colorScheme.error, 
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newChatAddress.isNotBlank()) {
                            viewModel.startConversation(
                                peerInput = newChatAddress,
                                onCreated = { newTopic ->
                                    showNewChatDialog = false
                                    newChatAddress = ""
                                    errorMessage = null
                                    onConversationClick(newTopic)
                                },
                                onError = { error ->
                                    errorMessage = error
                                }
                            )
                        }
                    }) {
                        Text(stringResource(R.string.start))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        showNewChatDialog = false
                        errorMessage = null 
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationItem(
    conversation: Conversation, 
    isPinned: Boolean,
    isMuted: Boolean = false,
    onClick: (String) -> Unit,
    onRename: () -> Unit = {},
    onPinToggle: () -> Unit,
    onMuteToggle: () -> Unit = {},
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val loadingStr = stringResource(R.string.loading)
    val unnamedGroupStr = stringResource(R.string.unnamed_group)
    val unknownChatStr = stringResource(R.string.unknown_chat)

    var title by remember { mutableStateOf(loadingStr) }
    val scope = rememberCoroutineScope()
    
    var showRenameDialog by remember { mutableStateOf(false) }
    var aliasInput by remember { mutableStateOf("") }
    val addressToRename = if (conversation is Conversation.Dm) conversation.dm.peerInboxId else ""
    var titleRefreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(conversation, titleRefreshTrigger) {
        title = try {
            when (conversation) {
                is Conversation.Group -> {
                    val name = conversation.group.name
                    if (name.isNotBlank()) name else unnamedGroupStr
                }
                is Conversation.Dm -> {
                    val address = conversation.dm.peerInboxId
                    val aliasName = ContactManager.getAlias(context, address)
                    if (!aliasName.isNullOrBlank()) {
                        aliasName
                    } else {
                        val resolvedName = com.example.messenger.EnsResolverManager.resolveAddress(address)
                        if (resolvedName != null) {
                            context.getString(R.string.dm_prefix, resolvedName)
                        } else {
                            context.getString(R.string.dm_prefix, "${address.take(10)}...")
                        }
                    }
                }
                else -> unknownChatStr
            }
        } catch (e: Exception) {
            unknownChatStr
        }
    }

    if (showRenameDialog && addressToRename.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.rename_chat_title)) },
            text = {
                TextField(
                    value = aliasInput,
                    onValueChange = { aliasInput = it },
                    label = { Text(stringResource(R.string.custom_name_label)) },
                    placeholder = { Text(stringResource(R.string.custom_name_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ContactManager.saveAlias(context, addressToRename, aliasInput)
                    showRenameDialog = false
                    titleRefreshTrigger++
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.delete_chat_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmDialog = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .combinedClickable(
                onClick = { onClick(conversation.topic) },
                onLongClick = { 
                    showContextMenu = true
                }
            )
    ) {
        Box {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    if (isPinned) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "📌",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (isMuted) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "🔇",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Text(text = stringResource(R.string.topic_prefix, conversation.topic.take(10)), style = MaterialTheme.typography.bodySmall)
            }
            
            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false }
            ) {
                if (addressToRename.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        onClick = {
                            showContextMenu = false
                            aliasInput = ContactManager.getAlias(context, addressToRename) ?: ""
                            showRenameDialog = true
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(if (isPinned) R.string.action_unpin else R.string.action_pin)) },
                    onClick = {
                        showContextMenu = false
                        onPinToggle()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(if (isMuted) R.string.action_unmute else R.string.action_mute)) },
                    onClick = {
                        showContextMenu = false
                        onMuteToggle()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showContextMenu = false
                        showDeleteConfirmDialog = true
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MessageScreen(
    viewModel: ConversationViewModel,
    onBack: () -> Unit,
    onGroupInfoClick: (String) -> Unit = {}
) {
    val messages by viewModel.messages.collectAsState()
    val clientState by ClientManager.clientState.collectAsState()
    val topic by viewModel.topic.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val context = LocalContext.current
    var galleryImages by remember { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }
    var galleryMessages by remember { mutableStateOf<List<DecodedMessage>>(emptyList()) }
    var initialGalleryIndex by remember { mutableStateOf(0) }
    var showMediaGrid by remember { mutableStateOf(false) }
    val isGalleryOpen = (galleryImages.isNotEmpty() || galleryMessages.isNotEmpty()) && !showMediaGrid

    DisposableEffect(topic) {
        onDispose {
            if (ClientManager.activeConversationTopic == topic) {
                ClientManager.activeConversationTopic = null
            }
        }
    }

    BackHandler(enabled = isGalleryOpen || showMediaGrid) {
        if (isGalleryOpen) {
            if (galleryMessages.isNotEmpty()) {
                showMediaGrid = true
            } else {
                galleryImages = emptyList()
            }
        } else if (showMediaGrid) {
            showMediaGrid = false
            galleryMessages = emptyList()
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.sendImages(uris, context)
        }
    }
    
    // Fetch and resolve the conversation title
    val chatTitleStr = stringResource(R.string.chat_title)
    val unnamedGroupStr = stringResource(R.string.unnamed_group)
    var chatTitle by remember { mutableStateOf(chatTitleStr) }
    var isGroupChat by remember { mutableStateOf(false) }
    
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var newMemberAddress by remember { mutableStateOf("") }
    
    val addMemberResult by viewModel.addMemberResult.collectAsState()

    LaunchedEffect(addMemberResult) {
        if (addMemberResult?.isSuccess == true) {
            showAddMemberDialog = false
            newMemberAddress = ""
            viewModel.resetAddMemberResult()
            android.widget.Toast.makeText(context, "Member added successfully", android.widget.Toast.LENGTH_SHORT).show()
        } else if (addMemberResult?.isFailure == true) {
            android.widget.Toast.makeText(context, "Failed to add member", android.widget.Toast.LENGTH_SHORT).show()
            viewModel.resetAddMemberResult()
        }
    }

    LaunchedEffect(topic) {
        if (topic.isNotBlank()) {
            val conversation = ClientManager.client.conversations.list().find { it.topic == topic }
            isGroupChat = conversation is Conversation.Group && conversation.group.name != "Saved Messages"
            chatTitle = try {
                when (conversation) {
                    is Conversation.Group -> {
                        val name = conversation.group.name
                        if (name.isNotBlank()) name else unnamedGroupStr
                    }
                    is Conversation.Dm -> {
                        val address = conversation.dm.peerInboxId
                        val aliasName = ContactManager.getAlias(context, address)
                        if (!aliasName.isNullOrBlank()) {
                            aliasName
                        } else {
                            val resolvedName = com.example.messenger.EnsResolverManager.resolveAddress(address)
                            if (resolvedName != null) {
                                resolvedName
                            } else {
                                "${address.take(6)}...${address.takeLast(4)}"
                            }
                        }
                    }
                    else -> chatTitleStr
                }
            } catch (e: Exception) {
                chatTitleStr
            }
        }
    }

        if (showAddMemberDialog) {
            AlertDialog(
                onDismissRequest = { showAddMemberDialog = false },
                title = { Text(stringResource(R.string.add_member_dialog_title)) },
                text = {
                    TextField(
                        value = newMemberAddress,
                        onValueChange = { newMemberAddress = it },
                        label = { Text(stringResource(R.string.wallet_address_or_ens)) },
                        placeholder = { Text(stringResource(R.string.wallet_address_placeholder)) }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newMemberAddress.isNotBlank()) {
                            viewModel.addMember(newMemberAddress)
                        }
                    }) {
                        Text(stringResource(R.string.add))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddMemberDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { 
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            val mediaMessages = messages.filter { msg ->
                                val type = msg.encodedContent.type
                                type == ContentTypeAttachment || type == ContentTypeRemoteAttachment
                            }.reversed() // Reverse to show latest first if desired, or keep original order
                            if (mediaMessages.isNotEmpty()) {
                                galleryMessages = mediaMessages
                                showMediaGrid = true
                            }
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(getConnectionColor(clientState), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(chatTitle) 
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.initiateCall(context, isVideo = false) }) {
                        Icon(Icons.Filled.Call, contentDescription = "Audio Call")
                    }
                    IconButton(onClick = { viewModel.initiateCall(context, isVideo = true) }) {
                        Icon(Icons.Filled.Videocam, contentDescription = "Video Call")
                    }
                    if (isGroupChat) {
                        IconButton(onClick = { onGroupInfoClick(topic) }) {
                            Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.group_info))
                        }
                        IconButton(onClick = { showAddMemberDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_member))
                        }
                    }
                },
                navigationIcon = { 
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) 
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    placeholder = { Text(stringResource(R.string.message_placeholder)) }
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add, 
                        contentDescription = "Send Image",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = { 
                        if (inputText.isNotBlank()) {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        }
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp)
                ) {
                    Text(stringResource(R.string.send))
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true // display latest messages at the bottom
            ) {
                // Group consecutive image messages from same sender
                val groupedMessages = mutableListOf<List<DecodedMessage>>()
                var currentGroup = mutableListOf<DecodedMessage>()
                
                messages.forEach { msg ->
                    val type = msg.encodedContent.type
                    val isImage = type == ContentTypeAttachment || type == ContentTypeRemoteAttachment
                    
                    if (isImage) {
                        if (currentGroup.isEmpty() || currentGroup.first().senderInboxId == msg.senderInboxId) {
                            currentGroup.add(msg)
                        } else {
                            groupedMessages.add(currentGroup)
                            currentGroup = mutableListOf(msg)
                        }
                    } else {
                        if (currentGroup.isNotEmpty()) {
                            groupedMessages.add(currentGroup)
                            currentGroup = mutableListOf()
                        }
                        groupedMessages.add(listOf(msg))
                    }
                }
                if (currentGroup.isNotEmpty()) {
                    groupedMessages.add(currentGroup)
                }

                items(groupedMessages.reversed()) { group ->
                    val isImageGroup = group.any { msg ->
                        val type = msg.encodedContent.type
                        type == ContentTypeAttachment || type == ContentTypeRemoteAttachment
                    }
                    
                    if (isImageGroup && group.size > 1) {
                        MessageGroupBubble(
                            group, 
                            currentUserAddress = com.example.messenger.ClientManager.client.inboxId,
                            onImageClick = { bitmaps, index -> 
                                galleryImages = bitmaps
                                initialGalleryIndex = index
                            }
                        )
                    } else {
                        // Single message (image or text)
                        MessageBubble(
                            group.first(), 
                            currentUserAddress = com.example.messenger.ClientManager.client.inboxId,
                            onImageClick = { bitmaps, index -> 
                                galleryImages = bitmaps
                                initialGalleryIndex = index
                            }
                        )
                    }
                }
            }
        }
    }

    // Shared Media Grid Overlay
    if (showMediaGrid && galleryMessages.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.shared_media)) },
                    navigationIcon = {
                        IconButton(onClick = { 
                            showMediaGrid = false
                            galleryMessages = emptyList()
                        }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(1.dp)
                ) {
                    itemsIndexed(galleryMessages) { index, msg ->
                        val bitmap = remember(msg) {
                            val content = msg.content<Attachment>()
                            if (content is Attachment) {
                                val data = content.data.toByteArray()
                                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                            } else null
                        }
                        
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .padding(1.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    initialGalleryIndex = index
                                    showMediaGrid = false
                                }
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Full-screen Image Overlay
    if (isGalleryOpen) {
        val pagerCount = if (galleryImages.isNotEmpty()) galleryImages.size else galleryMessages.size
        val pagerState = rememberPagerState(initialPage = initialGalleryIndex, pageCount = { pagerCount })
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        
        val verticalOffset = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()

        // Reset zoom when page changes
        LaunchedEffect(pagerState.currentPage) {
            scale = 1f
            offset = Offset.Zero
            verticalOffset.snapTo(0f)
        }

        val backgroundAlpha = (0.9f * (1f - (Math.abs(verticalOffset.value) / 1000f))).coerceIn(0f, 0.9f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = backgroundAlpha)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = scale == 1f && Math.abs(verticalOffset.value) < 10f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (scale > 1f || zoom > 1f) {
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    offset = if (scale > 1f) offset + pan else Offset.Zero
                                } else {
                                    scope.launch {
                                        verticalOffset.snapTo(verticalOffset.value + pan.y)
                                    }
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                do {
                                    val event = awaitPointerEvent()
                                } while (event.changes.any { it.pressed })
                                
                                if (scale == 1f) {
                                    if (Math.abs(verticalOffset.value) > 300f) {
                                        scope.launch {
                                            verticalOffset.animateTo(
                                                if (verticalOffset.value > 0) 1500f else -1500f,
                                                animationSpec = spring()
                                            )
                                            if (galleryMessages.isNotEmpty()) {
                                                showMediaGrid = true
                                            } else {
                                                galleryImages = emptyList()
                                            }
                                        }
                                    } else {
                                        scope.launch {
                                            verticalOffset.animateTo(0f, animationSpec = spring())
                                        }
                                    }
                                }
                            }
                        }
                ) { pageIndex ->
                    val bitmap = remember<android.graphics.Bitmap?>(pageIndex, galleryImages, galleryMessages) {
                        if (galleryImages.isNotEmpty()) {
                            galleryImages[pageIndex]
                        } else {
                            val msg = galleryMessages[pageIndex]
                            val content = msg.content<Attachment>()
                            if (content is Attachment) {
                                val data = content.data.toByteArray()
                                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                            } else {
                                null
                            }
                        }
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Fullscreen image",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .graphicsLayer(
                                    scaleX = if (pagerState.currentPage == pageIndex) scale else 1f,
                                    scaleY = if (pagerState.currentPage == pageIndex) scale else 1f,
                                    translationX = if (pagerState.currentPage == pageIndex) offset.x else 0f,
                                    translationY = if (pagerState.currentPage == pageIndex) {
                                        if (scale > 1f) offset.y else verticalOffset.value
                                    } else 0f
                                ),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = {
                            val currentBitmap = if (galleryImages.isNotEmpty()) {
                                galleryImages[pagerState.currentPage]
                            } else {
                                val msg = galleryMessages[pagerState.currentPage]
                                val content = msg.content<Attachment>()
                                if (content is Attachment) {
                                    val data = content.data.toByteArray()
                                    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                                } else null
                            }
                            
                            if (currentBitmap != null) {
                                val uri = FileUtils.saveImageToGallery(context, currentBitmap)
                                if (uri != null) {
                                    android.widget.Toast.makeText(context, context.getString(R.string.image_saved), android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, context.getString(R.string.image_save_error), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Text(text = stringResource(R.string.download_image), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            
            // Close button
            IconButton(
                onClick = { 
                    if (galleryMessages.isNotEmpty()) {
                        showMediaGrid = true
                    } else {
                        galleryImages = emptyList()
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

@Composable
fun MessageGroupBubble(
    messages: List<DecodedMessage>,
    currentUserAddress: String,
    onImageClick: (List<android.graphics.Bitmap>, Int) -> Unit
) {
    if (messages.isEmpty()) return
    
    val chronologicalGroup = messages.reversed()
    val firstMsg = chronologicalGroup.first()
    val isMine = firstMsg.senderInboxId.equals(currentUserAddress, ignoreCase = true)
    val alignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    
    val backgroundColor = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (isMine) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)

    val bitmaps = remember(chronologicalGroup) { extractBitmaps(chronologicalGroup) }

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = backgroundColor, contentColor = contentColor),
            shape = shape,
            modifier = Modifier.width(280.dp)
        ) {
            androidx.compose.foundation.layout.Box {
                ImageMosaic(bitmaps) { index -> onImageClick(bitmaps, index) }
                
                val lastMsg = chronologicalGroup.last()
                val statusText = if (isMine) {
                    when (lastMsg.deliveryStatus) {
                        DecodedMessage.MessageDeliveryStatus.PUBLISHED -> "✓✓"
                        DecodedMessage.MessageDeliveryStatus.UNPUBLISHED -> "✓"
                        DecodedMessage.MessageDeliveryStatus.FAILED -> "!"
                        DecodedMessage.MessageDeliveryStatus.ALL -> "?"
                    }
                } else null
                
                TimestampOverlay(
                    timeString = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(lastMsg.sentAt),
                    statusText = statusText,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                )
            }
        }
    }
}

@Composable
fun ImageMosaic(
    bitmaps: List<android.graphics.Bitmap>,
    onImageClick: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val chunks = mutableListOf<List<android.graphics.Bitmap>>()
        var i = 0
        while (i < bitmaps.size) {
            if (bitmaps.size - i == 3) {
                chunks.add(bitmaps.subList(i, i + 2))
                chunks.add(bitmaps.subList(i + 2, i + 3))
                i += 3
            } else if (bitmaps.size - i >= 2) {
                chunks.add(bitmaps.subList(i, i + 2))
                i += 2
            } else {
                chunks.add(bitmaps.subList(i, i + 1))
                i += 1
            }
        }

        var globalIndex = 0
        chunks.forEach { rowBitmaps ->
            Row(
                modifier = Modifier.fillMaxWidth().height(if (rowBitmaps.size == 1) 200.dp else 140.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                rowBitmaps.forEach { bitmap ->
                    val currentIndex = globalIndex++
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Mosaic Image",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onImageClick(currentIndex) },
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

@Composable
fun TimestampOverlay(
    timeString: String,
    statusText: String?,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = timeString,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = androidx.compose.ui.graphics.Color.White
            )
            if (statusText != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (statusText == "✓✓") MaterialTheme.colorScheme.inversePrimary else androidx.compose.ui.graphics.Color.White
                )
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: DecodedMessage, 
    currentUserAddress: String,
    onImageClick: (List<android.graphics.Bitmap>, Int) -> Unit = { _, _ -> }
) {
    val isMine = message.senderInboxId.equals(currentUserAddress, ignoreCase = true)
    val backgroundColor = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val alignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    
    val shape = if (isMine) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    }

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = backgroundColor, contentColor = contentColor),
            shape = shape,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            val bitmap = remember(message) { extractBitmap(message) }
            if (bitmap != null) {
                androidx.compose.foundation.layout.Box {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Attachment",
                        modifier = Modifier
                            .widthIn(max = 280.dp, min = 150.dp)
                            .heightIn(max = 300.dp)
                            .clickable { onImageClick(listOf(bitmap), 0) },
                        contentScale = ContentScale.Crop
                    )
                    val statusText = if (isMine) {
                        when (message.deliveryStatus) {
                            DecodedMessage.MessageDeliveryStatus.PUBLISHED -> "✓✓"
                            DecodedMessage.MessageDeliveryStatus.UNPUBLISHED -> "✓"
                            DecodedMessage.MessageDeliveryStatus.FAILED -> "!"
                            DecodedMessage.MessageDeliveryStatus.ALL -> "?"
                        }
                    } else null
                    
                    TimestampOverlay(
                        timeString = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(message.sentAt),
                        statusText = statusText,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    )
                }
            } else {
                BubbleInnerContent(message, contentColor, { bmp -> onImageClick(listOf(bmp), 0) }, isMine, showSender = !isMine)
            }
        }
    }
}

@Composable
fun BubbleInnerContent(
    message: DecodedMessage,
    contentColor: Color,
    onImageClick: (android.graphics.Bitmap) -> Unit,
    isMine: Boolean,
    showSender: Boolean
) {
    Column(modifier = Modifier.padding(12.dp)) {
                // If it's a group chat or we want context, we could show the sender's address here
                if (showSender) {
                    val address = message.senderInboxId
                    val context = LocalContext.current
                    val alias = ContactManager.getAlias(context, address)
                    val displaySender = alias ?: (address.take(6) + "...")
                    
                    Text(
                        text = displaySender, 
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                val content: Any? = message.content()
                if (content is Attachment) {
                    val bitmap = remember(content) {
                        val data = content.data.toByteArray()
                        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Attachment",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onImageClick(bitmap) },
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    } else {
                        Text(
                            text = stringResource(R.string.unsupported_content),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                } else {
                    val body = message.body as? String ?: stringResource(R.string.unsupported_content)
                    if (body.startsWith("__HUDDLE01_CALL:")) {
                        val parts = body.removePrefix("__HUDDLE01_CALL:").split(":")
                        if (parts.size >= 2) {
                            val roomId = parts[0]
                            val type = parts[1]
                            val context = androidx.compose.ui.platform.LocalContext.current
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Incoming ${type.replaceFirstChar { it.uppercase() }} Call",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                 Button(
                                    onClick = {
                                        val intent = android.content.Intent(context, CallActivity::class.java).apply {
                                            putExtra("room_id", roomId)
                                            putExtra("is_video", type == "video")
                                            putExtra("is_initiator", false)
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                ) {
                                    Text("Join Call")
                                }
                            }
                        } else {
                            Text(text = body, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text(
                            text = body, 
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }


                // Timestamp and Delivery Status Row
                val timeString = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(message.sentAt)
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = contentColor.copy(alpha = 0.6f)
                    )
                    
                    if (isMine) {
                        Spacer(modifier = Modifier.width(4.dp))
                        val statusText = when (message.deliveryStatus) {
                            DecodedMessage.MessageDeliveryStatus.PUBLISHED -> "✓✓"
                            DecodedMessage.MessageDeliveryStatus.UNPUBLISHED -> "✓"
                            DecodedMessage.MessageDeliveryStatus.FAILED -> "!"
                            DecodedMessage.MessageDeliveryStatus.ALL -> "?"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (statusText == "✓✓") MaterialTheme.colorScheme.inversePrimary else contentColor.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onCreated: (String) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var memberAddressInput by remember { mutableStateOf("") }
    val members = remember { mutableStateListOf<String>() }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val isLoading by viewModel.isLoading.collectAsState()

    val qrScannerLauncher = rememberLauncherForActivityResult(
        contract = ScanContract()
    ) { result ->
        if (result.contents != null) {
            memberAddressInput = result.contents
            errorMessage = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_group)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text(stringResource(R.string.group_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Text(
                text = stringResource(R.string.members_list, members.size),
                style = MaterialTheme.typography.titleMedium
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = memberAddressInput,
                    onValueChange = { 
                        memberAddressInput = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.member_address)) },
                    placeholder = { Text("vitalik.eth or 0x...") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    isError = errorMessage != null
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val options = ScanOptions()
                        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        options.setPrompt(context.getString(R.string.scan_qr_code))
                        options.setBeepEnabled(false)
                        options.setOrientationLocked(true)
                        options.setCaptureActivity(com.example.messenger.PortraitCaptureActivity::class.java)
                        qrScannerLauncher.launch(options)
                    }
                ) {
                    Text("📷", style = MaterialTheme.typography.titleLarge)
                }
                TextButton(
                    onClick = {
                        val addr = memberAddressInput.trim()
                        if (addr.isNotEmpty()) {
                            if (!members.contains(addr)) {
                                members.add(addr)
                                memberAddressInput = ""
                                errorMessage = null
                            } else {
                                errorMessage = "Member already added"
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.add))
                }
            }

            if (errorMessage != null) {
                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(members) { address ->
                    ListItem(
                        headlineContent = { Text(address, style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = {
                            IconButton(onClick = { members.remove(address) }) {
                                Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                }
            }

            Button(
                onClick = {
                    if (groupName.isBlank()) {
                        errorMessage = context.getString(R.string.error_group_name_required)
                        return@Button
                    }
                    if (members.isEmpty()) {
                        errorMessage = context.getString(R.string.error_at_least_one_member)
                        return@Button
                    }
                    viewModel.createGroup(
                        name = groupName,
                        members = members.toList(),
                        onCreated = onCreated,
                        onError = { errorMessage = it }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(R.string.create))
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMembersScreen(
    viewModel: ConversationViewModel,
    onBack: () -> Unit
) {
    val members by viewModel.members.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_members)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val membersList = members
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            items(membersList) { member ->
                ListItem(
                    headlineContent = { 
                        Text(
                            text = member.displayName ?: member.id,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    supportingContent = { 
                        val localizedRole = when(member.role) {
                            "Super Admin" -> stringResource(R.string.super_admin)
                            "Admin" -> stringResource(R.string.admin)
                            else -> stringResource(R.string.member)
                        }
                        if (member.displayName != null) {
                            Column {
                                Text(localizedRole)
                                Text(member.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                        } else {
                            Text(localizedRole)
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Member ID", member.id)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Filled.Person, contentDescription = "Copy")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun AdvancedDpiSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(StrategyRegistry.getCustomStrategy()) }
    
    // Local state for text fields to improve performance
    var tailSizeStr by remember { mutableStateOf(config.tailFragmentSize.toString()) }
    var delayStr by remember { mutableStateOf(config.delay.toString()) }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(stringResource(R.string.advanced_dpi_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.custom_strategy_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Toggles
            item {
                DpiToggleItem(
                    title = stringResource(R.string.fake_header_label),
                    desc = stringResource(R.string.fake_header_desc),
                    checked = config.fakeHeader,
                    onCheckedChange = { config = config.copy(fakeHeader = it) }
                )
            }
            item {
                DpiToggleItem(
                    title = stringResource(R.string.split_s1_label),
                    desc = stringResource(R.string.split_s1_desc),
                    checked = config.splitS1,
                    onCheckedChange = { config = config.copy(splitS1 = it) }
                )
            }
            item {
                DpiToggleItem(
                    title = stringResource(R.string.disorder_label),
                    desc = stringResource(R.string.disorder_desc),
                    checked = config.disorder,
                    onCheckedChange = { config = config.copy(disorder = it) }
                )
            }
            item {
                DpiToggleItem(
                    title = stringResource(R.string.sni_split_label),
                    desc = stringResource(R.string.sni_split_desc),
                    checked = config.sniSplit,
                    onCheckedChange = { config = config.copy(sniSplit = it) }
                )
            }
            item {
                DpiToggleItem(
                    title = stringResource(R.string.oob_label),
                    desc = stringResource(R.string.oob_desc),
                    checked = config.oob,
                    onCheckedChange = { config = config.copy(oob = it) }
                )
            }

            // Numeric Inputs
            item {
                OutlinedTextField(
                    value = tailSizeStr,
                    onValueChange = { 
                        tailSizeStr = it
                        it.toIntOrNull()?.let { size -> config = config.copy(tailFragmentSize = size) }
                    },
                    label = { Text(stringResource(R.string.tail_size_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = delayStr,
                    onValueChange = { 
                        delayStr = it
                        it.toLongOrNull()?.let { d -> config = config.copy(delay = d) }
                    },
                    label = { Text(stringResource(R.string.delay_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Raw Command String
            item {
                Text(
                    text = "ByeDPI Commands",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = config.commandString,
                    onValueChange = { config = config.copy(commandString = it) },
                    label = { Text(stringResource(R.string.strategy_command_label)) },
                    supportingText = { Text(stringResource(R.string.strategy_command_desc)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }

            item {
                OutlinedButton(
                    onClick = {
                        val example = "-s1 -q1 -Y -Ar -s5 -o1+s -At -f-1 -r1+s -As -s1 -o1+s -s-1 -An -b+500"
                        config = config.copy(commandString = example)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.paste_example))
                }
            }

            // Guide
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.dpi_config_guide),
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 20.sp
                        )
                    }
                }
            }

            // Buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            StrategyRegistry.setCustomStrategy(config)
                            // Also mark CUSTOM as preferred so it takes effect immediately
                            val host = "grpc.xmtp.org" // Default host
                            StrategyRegistry.markSuccess(host, BypassStrategyId.CUSTOM.ordinal)
                            Toast.makeText(context, "Config Applied", Toast.LENGTH_SHORT).show()
                            onBack()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.save))
                    }
                    OutlinedButton(
                        onClick = {
                            val default = CustomStrategyConfig()
                            config = default
                            tailSizeStr = default.tailFragmentSize.toString()
                            delayStr = default.delay.toString()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reset")
                    }
                }
            }
        }
    }
}

@Composable
fun DpiToggleItem(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
