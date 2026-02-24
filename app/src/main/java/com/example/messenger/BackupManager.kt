package com.example.messenger

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Handles encrypted backup and restore of XMTP data.
 * Packages the XMTP database + session metadata into a password-encrypted file.
 * Uses AES-256-CBC with PBKDF2 key derivation.
 */
object BackupManager {
    private const val TAG = "BackupManager"
    private const val ITERATION_COUNT = 65536
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 16

    fun createBackup(context: Context, outputUri: Uri, password: String): Boolean {
        return try {
            val zipData = ByteArrayOutputStream()
            ZipOutputStream(zipData).use { zip ->
                // 1. SharedPreferences
                val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/xmtp_key_prefs.xml")
                if (prefsFile.exists()) {
                    addFileToZip(zip, prefsFile, "shared_prefs/xmtp_key_prefs.xml")
                    Log.d(TAG, "Backed up prefs: ${prefsFile.length()} bytes")
                }

                // 2. All database files EXCEPT WalletConnect
                val dbDir = context.getDatabasePath("placeholder").parentFile
                if (dbDir != null && dbDir.exists()) {
                    dbDir.walkTopDown().forEach { file ->
                        val n = file.name.lowercase()
                        if (file.isFile && !n.contains("walletconnect") && !n.contains("wc_")) {
                            val relativePath = file.relativeTo(dbDir).path
                            addFileToZip(zip, file, "databases/$relativePath")
                            Log.d(TAG, "Backed up db: $relativePath (${file.length()} bytes)")
                        }
                    }
                }

                // 3. All internal files EXCEPT WalletConnect
                val filesDir = context.filesDir
                filesDir.walkTopDown().forEach { file ->
                    val n = file.name.lowercase()
                    if (file.isFile && !n.contains("walletconnect") && !n.contains("wc_")) {
                        val relativePath = file.relativeTo(filesDir).path
                        addFileToZip(zip, file, "files/$relativePath")
                        Log.d(TAG, "Backed up file: $relativePath (${file.length()} bytes)")
                    }
                }

                // 4. Metadata
                val metadata = """{"app":"messy","version":1,"timestamp":${System.currentTimeMillis()}}"""
                zip.putNextEntry(ZipEntry("metadata.json"))
                zip.write(metadata.toByteArray())
                zip.closeEntry()
            }

            val encrypted = encrypt(zipData.toByteArray(), password)

            context.contentResolver.openOutputStream(outputUri)?.use { output ->
                output.write(encrypted)
            }

            Log.d(TAG, "Backup created successfully (${encrypted.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed: ${e.message}", e)
            false
        }
    }

    /**
     * ATOMIC restore: extracts everything to a temp directory first,
     * validates success, then moves files into place.
     * If anything fails, the original data is untouched.
     */
    fun restoreBackup(context: Context, inputUri: Uri, password: String): Boolean {
        val tempDir = File(context.cacheDir, "restore_temp_${System.currentTimeMillis()}")
        return try {
            val encryptedData = context.contentResolver.openInputStream(inputUri)?.use {
                it.readBytes()
            } ?: throw Exception("Cannot read backup file")

            Log.d(TAG, "Read backup file: ${encryptedData.size} bytes")

            val zipData = decrypt(encryptedData, password)
            Log.d(TAG, "Decrypted OK: ${zipData.size} bytes")

            // Phase 1: Extract everything to temp directory
            tempDir.mkdirs()

            ZipInputStream(ByteArrayInputStream(zipData)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name != "metadata.json") {
                        val data = readZipEntry(zip)
                        val tempFile = File(tempDir, entry.name)
                        tempFile.parentFile?.mkdirs()
                        tempFile.writeBytes(data)
                        Log.d(TAG, "Extracted to temp: ${entry.name} (${data.size} bytes)")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            // Phase 2: Validate we have something to restore
            val tempFiles = tempDir.walkTopDown().filter { it.isFile }.toList()
            if (tempFiles.isEmpty()) {
                throw Exception("Backup file appears empty")
            }

            // Phase 3: Move files into place (atomic-ish)
            for (tempFile in tempFiles) {
                val relativePath = tempFile.relativeTo(tempDir).path
                val targetFile = when {
                    relativePath.startsWith("shared_prefs/") ->
                        File(context.applicationInfo.dataDir, relativePath)
                    relativePath.startsWith("databases/") ->
                        File(context.applicationInfo.dataDir, relativePath)
                    relativePath.startsWith("files/") ->
                        File(context.filesDir, relativePath.removePrefix("files/"))
                    else -> null
                }
                targetFile?.let { target ->
                    target.parentFile?.mkdirs()
                    tempFile.copyTo(target, overwrite = true)
                    Log.d(TAG, "Installed: $relativePath")
                }
            }

            Log.d(TAG, "Restore completed successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed: ${e.message}", e)
            false
        } finally {
            // Clean up temp directory
            tempDir.deleteRecursively()
        }
    }

    /**
     * Reads all bytes from the CURRENT zip entry without advancing to the next one.
     * ZipInputStream.readBytes() can be unreliable — this uses the proper read loop.
     */
    private fun readZipEntry(zip: ZipInputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val tmp = ByteArray(4096)
        var len: Int
        while (zip.read(tmp).also { len = it } > 0) {
            buffer.write(tmp, 0, len)
        }
        return buffer.toByteArray()
    }

    /**
     * Clears only XMTP databases and files, keeping the encryption key and WalletConnect data intact.
     * Used to safely delete corrupted DB files before attempting to recreate the Client.
     */
    fun clearXmtpDataFiles(context: Context) {
        try {
            val dbDir = context.getDatabasePath("placeholder").parentFile
            dbDir?.walkTopDown()?.forEach { file ->
                val n = file.name.lowercase()
                if (file.isFile && !n.contains("walletconnect") && !n.contains("wc_")) {
                    file.delete()
                    Log.d(TAG, "Deleted corrupted db: ${file.name}")
                }
            }

            val filesDir = context.filesDir
            filesDir.walkTopDown().forEach { file ->
                val n = file.name.lowercase()
                if (file.isFile && !n.contains("walletconnect") && !n.contains("wc_")) {
                    file.delete()
                    Log.d(TAG, "Deleted corrupted file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing XMTP data files: ${e.message}", e)
        }
    }

    /**
     * Clears all app data — use when database is corrupted
     * and user needs to start fresh without reinstalling.
     */
    fun clearAllData(context: Context) {
        try {
            // Clear our SharedPreferences
            context.getSharedPreferences("xmtp_key_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit()

            // Delete ALL shared_prefs files (including WalletConnect's)
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            prefsDir.listFiles()?.forEach { it.delete() }

            // Delete databases (recursively to catch subdirectories used by libxmtp)
            val dbDir = context.getDatabasePath("placeholder").parentFile
            dbDir?.deleteRecursively()

            // Delete internal files
            context.filesDir.listFiles()?.forEach { it.deleteRecursively() }

            // Delete cache (WalletConnect may store data here)
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }

            // Delete no_backup dir
            context.noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }

            Log.d(TAG, "All app data cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing data: ${e.message}", e)
        }
    }

    private fun addFileToZip(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun encrypt(data: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        return salt + iv + cipher.doFinal(data)
    }

    private fun decrypt(data: ByteArray, password: String): ByteArray {
        val salt = data.copyOfRange(0, SALT_LENGTH)
        val iv = data.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val encrypted = data.copyOfRange(SALT_LENGTH + IV_LENGTH, data.size)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return cipher.doFinal(encrypted)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    // ──────────────────────────────────────────────────────────────────────
    // AUTO-BACKUP / AUTO-RESTORE  (survives app reinstall via MediaStore)
    // ──────────────────────────────────────────────────────────────────────

    private fun backupFileName(walletAddress: String): String {
        val hash = walletAddress.lowercase().hashCode().toUInt().toString(16)
        return "xmtp_$hash.enc"
    }

    /**
     * Backs up XMTP database files via MediaStore to Documents/MessengerBackup/.
     * Survives app uninstall. Encrypted with wallet address via PBKDF2 + AES-256.
     *
     * Stores `db_key.bin` in the zip — the raw 32-byte DB encryption key —
     * so restore can retrieve it directly without parsing XML or reading SharedPrefs.
     */
    fun autoBackup(context: Context, walletAddress: String): Boolean {
        return try {
            val password = walletAddress.lowercase()
            val fileName = backupFileName(walletAddress)
            val zipData = ByteArrayOutputStream()

            ZipOutputStream(zipData).use { zip ->
                // 1. Raw DB encryption key — stored separately to avoid XML parsing on restore
                val dbKey = KeyManager.getOrCreateDbEncryptionKey(context, walletAddress)
                zip.putNextEntry(ZipEntry("db_key.bin"))
                zip.write(dbKey)
                zip.closeEntry()
                Log.d(TAG, "Auto-backup: db_key.bin (${dbKey.size} bytes)")

                // 2. XMTP database files
                val xmtpDbDir = File(context.filesDir, "xmtp_db")
                if (xmtpDbDir.exists()) {
                    xmtpDbDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zip, file, "xmtp_db/${file.name}")
                            Log.d(TAG, "Auto-backup: xmtp_db/${file.name} (${file.length()} bytes)")
                        }
                    }
                }

                // 3. SharedPreferences (wallet address etc.)
                val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/xmtp_key_prefs.xml")
                if (prefsFile.exists()) addFileToZip(zip, prefsFile, "shared_prefs/xmtp_key_prefs.xml")
                val syncPrefsFile = File(context.applicationInfo.dataDir, "shared_prefs/sync_prefs.xml")
                if (syncPrefsFile.exists()) addFileToZip(zip, syncPrefsFile, "shared_prefs/sync_prefs.xml")
            }

            val encrypted = encrypt(zipData.toByteArray(), password)
            writeToMediaStore(context, fileName, encrypted)
            Log.d(TAG, "Auto-backup saved via MediaStore: $fileName (${encrypted.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Auto-backup failed: ${e.message}", e)
            false
        }
    }


    /**
     * Restores from auto-backup found via MediaStore or direct file.
     *
     * @return the DB encryption key extracted directly from the backup (bypasses
     *   Android's stale in-memory SharedPreferences cache), or null if no backup
     *   found or restore failed.
     */
    fun autoRestore(context: Context, walletAddress: String): ByteArray? {
        val password = walletAddress.lowercase()
        val fileName = backupFileName(walletAddress)

        return try {
            // Try MediaStore first, then direct file access
            var encrypted = readFromMediaStore(context, fileName)
            if (encrypted == null) {
                Log.d(TAG, "MediaStore query returned null for $fileName, trying direct file access...")
                val directFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "MessengerBackup/$fileName"
                )
                if (directFile.exists() && directFile.canRead()) {
                    encrypted = directFile.readBytes()
                    Log.d(TAG, "Found backup via direct file: ${directFile.absolutePath} (${encrypted.size} bytes)")
                } else if (directFile.exists()) {
                    Log.d(TAG, "Backup file exists but not readable. isManager=${Environment.isExternalStorageManager()}")
                    return null
                } else {
                    Log.d(TAG, "No backup file at ${directFile.absolutePath}")
                    return null
                }
            } else {
                Log.d(TAG, "Found backup via MediaStore: $fileName (${encrypted.size} bytes)")
            }

            val zipData = decrypt(encrypted, password)
            Log.d(TAG, "Decrypted OK: ${zipData.size} bytes")

            // Read db_key.bin directly — no XML parsing, no SharedPrefs cache issues.
            // For old backups that don't have db_key.bin, returns null → caller does Client.create() fresh.
            var restoredDbKey: ByteArray? = null

            ZipInputStream(ByteArrayInputStream(zipData)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val data = readZipEntry(zip)

                        if (entry.name == "db_key.bin") {
                            restoredDbKey = data
                            Log.d(TAG, "Restored DB key from db_key.bin (${data.size} bytes)")
                        }

                        val targetFile = when {
                            entry.name.startsWith("xmtp_db/") -> {
                                val dbDir = File(context.filesDir, "xmtp_db")
                                dbDir.mkdirs()
                                File(dbDir, entry.name.removePrefix("xmtp_db/"))
                            }
                            entry.name.startsWith("shared_prefs/") ->
                                File(context.applicationInfo.dataDir, entry.name)
                            entry.name.startsWith("files/") ->
                                File(context.filesDir, entry.name.removePrefix("files/"))
                            else -> null  // db_key.bin and others go here — not written to disk
                        }
                        targetFile?.let {
                            it.parentFile?.mkdirs()
                            it.writeBytes(data)
                            Log.d(TAG, "Restored: ${entry!!.name} (${data.size} bytes)")
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            Log.d(TAG, "Auto-restore completed (key=${restoredDbKey != null})")
            restoredDbKey
        } catch (e: Exception) {
            Log.e(TAG, "Auto-restore failed: ${e.message}", e)
            null
        }
    }


    // ── MediaStore helpers ────────────────────────────────────────────────

    private fun writeToMediaStore(context: Context, fileName: String, data: ByteArray) {
        val resolver = context.contentResolver

        // Delete MediaStore record for this file (if owned by current installation)
        findInMediaStore(context, fileName)?.let { existingUri ->
            resolver.delete(existingUri, null, null)
        }

        // Also delete the physical file directly — it may exist from a previous installation
        // that wrote it without MediaStore (old code), or from a different MediaStore owner.
        // Without this, MediaStore would create "xmtp_b343d21a (1).enc" alongside the old file,
        // and direct-file fallback would find the old file instead of the new one.
        val physicalFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "MessengerBackup/$fileName"
        )
        if (physicalFile.exists()) {
            physicalFile.delete()
            Log.d(TAG, "Deleted old physical backup file before MediaStore write: $fileName")
        }

        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOCUMENTS}/MessengerBackup")
        }

        val uri = resolver.insert(
            android.provider.MediaStore.Files.getContentUri("external"), values
        ) ?: throw Exception("MediaStore insert failed for $fileName")

        resolver.openOutputStream(uri)?.use { it.write(data) }
            ?: throw Exception("Cannot open output stream for $uri")
    }

    private fun readFromMediaStore(context: Context, fileName: String): ByteArray? {
        val uri = findInMediaStore(context, fileName) ?: return null
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    private fun findInMediaStore(context: Context, fileName: String): Uri? {
        val collection = android.provider.MediaStore.Files.getContentUri("external")
        val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
        val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(fileName, "%MessengerBackup%")

        context.contentResolver.query(collection, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(
                        cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                    )
                    return android.content.ContentUris.withAppendedId(collection, id)
                }
            }
        return null
    }

    fun hasAutoBackup(context: Context, walletAddress: String): Boolean {
        return findInMediaStore(context, backupFileName(walletAddress)) != null
    }
}

