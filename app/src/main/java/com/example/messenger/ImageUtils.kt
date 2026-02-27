package com.example.messenger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.xmtp.android.library.codecs.Attachment
import java.io.ByteArrayOutputStream
import java.io.InputStream

object ImageUtils {
    private const val MAX_SIZE_BYTES = 1000 * 1024 // ~1MB
    private const val MAX_DIMENSION = 1280

    fun compressAndTranscodeToJpg(uri: Uri, context: Context): Attachment? {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return null
        val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return null
        
        // 1. Resize if needed
        val scaledBitmap = if (originalBitmap.width > MAX_DIMENSION || originalBitmap.height > MAX_DIMENSION) {
            val ratio = originalBitmap.width.toFloat() / originalBitmap.height.toFloat()
            val newWidth: Int
            val newHeight: Int
            if (ratio > 1) {
                newWidth = MAX_DIMENSION
                newHeight = (MAX_DIMENSION / ratio).toInt()
            } else {
                newHeight = MAX_DIMENSION
                newWidth = (MAX_DIMENSION * ratio).toInt()
            }
            Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
        } else {
            originalBitmap
        }

        // 2. Compress to JPEG
        var quality = 90
        var byteArray: ByteArray
        val outputStream = ByteArrayOutputStream()
        
        do {
            outputStream.reset()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            byteArray = outputStream.toByteArray()
            quality -= 10
        } while (byteArray.size > MAX_SIZE_BYTES && quality > 10)

        // Cleanup
        if (scaledBitmap != originalBitmap) {
            scaledBitmap.recycle()
        }
        originalBitmap.recycle()

        if (byteArray.size > MAX_SIZE_BYTES) {
            android.util.Log.e("ImageUtils", "Failed to compress image below 1MB")
            return null
        }

        return Attachment(
            filename = "image.jpg",
            mimeType = "image/jpeg",
            data = com.google.protobuf.ByteString.copyFrom(byteArray)
        )
    }
}
