package com.mavislibrary.app.ui.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.*
import org.junit.Test

class BarcodeFrameDecoderTest {
    @Test fun bundledDecoderReadsBooklandEanWithoutNetwork() {
        val bits = MultiFormatWriter().encode("9780306406157", BarcodeFormat.EAN_13, 420, 180)
        val pixels = ByteArray(bits.width * bits.height) { i -> if (bits[i % bits.width, i / bits.width]) 0 else -1 }
        assertEquals("9780306406157", BarcodeFrameDecoder().decode(pixels, bits.width, bits.height))
        val rotated = ByteArray(pixels.size)
        for (y in 0 until bits.height) for (x in 0 until bits.width) {
            rotated[x * bits.height + bits.height - y - 1] = pixels[y * bits.width + x]
        }
        assertEquals("9780306406157", BarcodeFrameDecoder().decode(rotated, bits.height, bits.width))
    }

    @Test fun blankFrameIsIgnored() {
        assertNull(BarcodeFrameDecoder().decode(ByteArray(420 * 180) { -1 }, 420, 180))
    }
}
