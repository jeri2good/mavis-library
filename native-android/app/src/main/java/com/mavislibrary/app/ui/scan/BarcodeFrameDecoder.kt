package com.mavislibrary.app.ui.scan

import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

class BarcodeFrameDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13, BarcodeFormat.UPC_A, BarcodeFormat.EAN_8, BarcodeFormat.UPC_E)))
    }

    fun decode(frame: ImageProxy): String? {
        val plane = frame.planes[0]
        val data = ByteArray(frame.width * frame.height)
        val buffer = plane.buffer
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) data[y * frame.width + x] = buffer.get(y * plane.rowStride + x * plane.pixelStride)
        }
        return decode(data, frame.width, frame.height)
    }

    fun decode(luminance: ByteArray, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        fun tryDecode(view: com.google.zxing.LuminanceSource): String? = try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(view))).text
        } catch (_: com.google.zxing.ReaderException) {
            reader.reset()
            null
        }
        tryDecode(source)?.let { return it }
        tryDecode(source.invert())?.let { return it }
        val rotated = ByteArray(luminance.size)
        for (y in 0 until height) for (x in 0 until width) {
            rotated[x * height + height - y - 1] = luminance[y * width + x]
        }
        val sideways = PlanarYUVLuminanceSource(rotated, height, width, 0, 0, height, width, false)
        return tryDecode(sideways) ?: tryDecode(sideways.invert())
    }
}
