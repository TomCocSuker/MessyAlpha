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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.MoreVert
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
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.example.messenger.R
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.Image

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
    onBackupClick: () -> Unit = {}
) {
    val conversations by viewModel.conversations.collectAsState()
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
            FloatingActionButton(onClick = { showNewChatDialog = true }) {
                Text("+")
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
                    items(conversations) { conversation ->
                        ConversationItem(conversation, onConversationClick)
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
fun ConversationItem(conversation: Conversation, onClick: (String) -> Unit) {
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .combinedClickable(
                onClick = { onClick(conversation.topic) },
                onLongClick = { 
                    if (addressToRename.isNotBlank()) {
                        aliasInput = ContactManager.getAlias(context, addressToRename) ?: ""
                        showRenameDialog = true
                    }
                }
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(R.string.topic_prefix, conversation.topic.take(10)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(
    viewModel: ConversationViewModel,
    onBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val clientState by ClientManager.clientState.collectAsState()
    val topic by viewModel.topic.collectAsState()
    var inputText by remember { mutableStateOf("") }
    
    // Fetch and resolve the conversation title
    val chatTitleStr = stringResource(R.string.chat_title)
    val unnamedGroupStr = stringResource(R.string.unnamed_group)
    var chatTitle by remember { mutableStateOf(chatTitleStr) }
    val context = LocalContext.current
    
    LaunchedEffect(topic) {
        if (topic.isNotBlank()) {
            val conversation = ClientManager.client.conversations.list().find { it.topic == topic }
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

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(getConnectionColor(clientState), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(chatTitle) 
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
                Spacer(modifier = Modifier.width(8.dp))
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
                items(messages.reversed()) { message ->
                    MessageBubble(message, currentUserAddress = com.example.messenger.ClientManager.client.inboxId)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: DecodedMessage, currentUserAddress: String) {
    val isMine = message.senderInboxId.equals(currentUserAddress, ignoreCase = true)

    // Material 3 colors based on sender
    val backgroundColor = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val alignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isMine) {
        androidx.compose.foundation.shape.RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        androidx.compose.foundation.shape.RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    // Wrap in a layout that enforces left/right alignment
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = backgroundColor,
                contentColor = contentColor
            ),
            shape = shape,
            modifier = Modifier.widthIn(max = 280.dp) // Prevent bubbles from stretching across the entire screen
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // If it's a group chat or we want context, we could show the sender's address here
                if (!isMine) {
                    val address = message.senderInboxId
                    Text(
                        text = address.take(6) + "...", 
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Text(
                    text = message.body as? String ?: stringResource(R.string.unsupported_content), 
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 2.dp)
                )

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
    }
}
