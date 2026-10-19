package com.roy233.tvcast.platform.qr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** 负责将登录地址编码为二维码位图。 */
class QrCodeGenerator {

    /**
     * 生成指定地址的二维码。
     *
     * @param url 待编码的地址。
     * @return 生成成功的二维码位图，编码失败时返回 null。
     */
    fun generate(url: String): Bitmap? = runCatching {
        val size = 512
        val bits = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                pixels[row + x] = if (bits[x, y]) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE
            }
        }
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }.getOrNull()
}
