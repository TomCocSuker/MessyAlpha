package com.example.messenger.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

object QRCodeUtils {
    fun generateQrCode(content: String, size: Int = 512): Bitmap? {
        if (content.isBlank()) return null
        return try {
            val barcodeEncoder = BarcodeEncoder()
            barcodeEncoder.encodeBitmap(content, BarcodeFormat.QR_CODE, size, size)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

@Composable
fun rememberQrBitmap(content: String, size: Int = 512): ImageBitmap? {
    return remember(content, size) {
        QRCodeUtils.generateQrCode(content, size)?.asImageBitmap()
    }
}
