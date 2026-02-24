package com.example.messenger.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.messenger.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.res.stringResource
import com.example.messenger.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    // SAF launcher for creating backup file
    val createFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            pendingUri = uri
            showBackupDialog = true
        }
    }

    // SAF launcher for picking restore file
    val openFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingUri = uri
            showRestoreDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_and_restore_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
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
            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.encrypted_backup),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.backup_description),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Backup button
            Button(
                onClick = {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
                        .format(Date())
                    createFileLauncher.launch("messy_backup_$timestamp.msbk")
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isProcessing
            ) {
                Text(stringResource(R.string.create_backup_btn))
            }

            // Restore button
            OutlinedButton(
                onClick = {
                    openFileLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isProcessing
            ) {
                Text(stringResource(R.string.restore_backup_btn))
            }

            // Processing indicator
            if (isProcessing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.processing), style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Status message
            statusMessage?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (msg.startsWith("✅"))
                            MaterialTheme.colorScheme.secondaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(msg, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Hint
            Text(
                stringResource(R.string.backup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Password dialog for BACKUP
    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false; password = "" },
            title = { Text(stringResource(R.string.encrypt_backup_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.enter_password_encrypt))
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingUri ?: return@TextButton
                        val pwd = password
                        showBackupDialog = false
                        password = ""
                        isProcessing = true
                        statusMessage = null
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                BackupManager.createBackup(context, uri, pwd)
                            }
                            isProcessing = false
                            statusMessage = if (success)
                                context.getString(R.string.backup_success)
                            else
                                context.getString(R.string.backup_failed)
                        }
                    },
                    enabled = password.length >= 4
                ) {
                    Text(stringResource(R.string.encrypt_and_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false; password = "" }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Password dialog for RESTORE
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false; password = "" },
            title = { Text(stringResource(R.string.restore_backup_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.enter_password_restore))
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.app_will_restart_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingUri ?: return@TextButton
                        val pwd = password
                        showRestoreDialog = false
                        password = ""
                        isProcessing = true
                        statusMessage = null
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                BackupManager.restoreBackup(context, uri, pwd)
                            }
                            if (success) {
                                // Force restart the app to reload SharedPreferences from disk.
                                // Without this, the in-memory cached encryption key doesn't match
                                // the restored database, causing PRAGMA key errors.
                                val pm = context.packageManager
                                val intent = pm.getLaunchIntentForPackage(context.packageName)
                                intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                                Runtime.getRuntime().exit(0)
                            } else {
                                isProcessing = false
                                statusMessage = context.getString(R.string.restore_failed)
                            }
                        }
                    },
                    enabled = password.length >= 4
                ) {
                    Text(stringResource(R.string.decrypt_and_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false; password = "" }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
