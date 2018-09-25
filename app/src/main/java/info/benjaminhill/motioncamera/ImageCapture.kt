package info.benjaminhill.motioncamera

import android.graphics.*
import android.media.Image
import com.github.ajalt.timberkt.i
import com.github.ajalt.timberkt.w
import java.io.File
import java.io.FileOutputStream
import kotlin.system.measureTimeMillis


class ImageCapture(private val albumFolder: File) {

    fun processImage(image: Image) {
        val format = image.format
        require(format == ImageFormat.YUV_420_888)
        val cropRect = image.cropRect
        val width = cropRect.width()
        val height = cropRect.height()
        i { "cropRect: $cropRect" }
        require(image.width == width) { "Image width and plane width should match, but ${image.width}!=$width" }

        val yPlane = image.planes[0]
        require(yPlane.pixelStride == 1) { "pixel stride from YUV_420_888 should not be interleaved." }
        val bb = yPlane.buffer

        val alpha2webpTime = measureTimeMillis {
            bb.rewind() // necessary?
            val lumAsAlpha = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            lumAsAlpha.copyPixelsFromBuffer(bb)

            val backgroundBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(backgroundBitmap)
            canvas.drawColor(Color.BLACK)
            val maskPaint = Paint()
            maskPaint.color = Color.WHITE
            canvas.drawBitmap(lumAsAlpha, 0f, 0f, maskPaint)

            File(albumFolder, "mc_alpha3_${image.timestamp}.webp").let { file ->
                FileOutputStream(file).use {
                    backgroundBitmap.compress(Bitmap.CompressFormat.WEBP, 100, it)
                }
            }
        }

        w { "Times: $alpha2webpTime" }
    }
}

