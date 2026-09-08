package app.ripple.mesh.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Offline QR rendering (ZXing, no camera): shows codes for another device's *scanner
 * app*. The Ripple apps deliberately have no camera permission — scanning happens
 * outside the app and the decoded text is pasted back in (docs/PAIRING.md).
 */
object Qr {
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * Encode [text] as a black/white QR bitmap. [targetPx] is the max requested side in
     * pixels; ZXing picks an integer module scale that fits and adds a quiet zone.
     * Returns null if the content cannot be encoded (too long for a QR).
     */
    fun bitmap(text: String, targetPx: Int = 512): ImageBitmap? = try {
        val hints = mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, targetPx, targetPx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) pixels[row + x] = if (matrix.get(x, y)) BLACK else WHITE
        }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
