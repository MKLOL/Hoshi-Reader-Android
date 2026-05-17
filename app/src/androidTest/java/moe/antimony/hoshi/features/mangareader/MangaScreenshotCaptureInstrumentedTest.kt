package moe.antimony.hoshi.features.mangareader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaScreenshotCaptureInstrumentedTest {
    @Test
    fun cropWebViewBitmapPngPreservesSelectedPixels() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                source.setPixel(x, y, Color.rgb(x, y, 40))
            }
        }

        val png = cropWebViewBitmapPng(
            bitmap = source,
            rect = MangaScreenshotCropRect(left = 10, top = 12, right = 50, bottom = 52),
        )

        assertNotNull(png)
        val decoded = BitmapFactory.decodeByteArray(png, 0, png!!.size)
        assertEquals(40, decoded.width)
        assertEquals(40, decoded.height)
        assertEquals(Color.rgb(10, 12, 40), decoded.getPixel(0, 0))
        assertEquals(Color.rgb(49, 51, 40), decoded.getPixel(39, 39))
        decoded.recycle()
    }
}
