package info.benjaminhill.motioncamera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import ar.com.hjg.pngj.ImageInfo
import ar.com.hjg.pngj.ImageLineByte
import ar.com.hjg.pngj.PngWriter
import com.github.ajalt.timberkt.Timber
import com.github.ajalt.timberkt.i
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*


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
        bb.rewind()
        val bytes = ByteArray(bb.remaining())
        i { "ready to copy from Y ByteBuffer into byte array ${bytes.size}"}
        bb.get(bytes)

        i { "Y: " + bytes.take(10).map { (it.toInt() and 0xFF) - 16 }.joinToString { ", " } }

        // Try saving the Y-plane as an alpha-only PNG
        try {
            bb.rewind() // necessary?
            val lumAsAlpha = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            lumAsAlpha.copyPixelsFromBuffer(bb)
            File(albumFolder, "mc_alpha_${SDF.format(Date())}.png").let { file ->
                FileOutputStream(file).use {
                    lumAsAlpha.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                i { "Write lumAsAlpha to ${file.path} ${file.length()}" }
            }
        } catch (e: Exception) {
            Timber.e(e) { "Failed during lumAsAlpha" }
        }

        // Try using pngj to write out a grayscale PNG
        try {
            i { "Writing to $width x $height" }
            // int cols, int rows, int bitdepth, boolean alpha, boolean grayscale, boolean indexed
            val grayii = ImageInfo(
                    width,
                    height,
                    8,
                    false,
                    true,
                    false
            )

            val imageFile = File(albumFolder, "mc2_${SDF.format(Date())}.png")
            val pngw = PngWriter(imageFile, grayii, true)
            for (rowNum: Int in 0 until height) {
                val row = ImageLineByte(grayii, ByteArray(width))
                val rowLine = row.scanline
                System.arraycopy(
                        bytes,
                        rowNum * yPlane.rowStride,
                        rowLine,
                        0,
                        yPlane.rowStride
                )
                pngw.writeRow(row)
            }
            pngw.end()
            i { "pngj: ${imageFile.path} ${imageFile.length()}" }
        } catch (e: Exception) {
            Timber.e(e) { "Failed during pngj" }
        }

    }

    companion object {
        private val SDF = SimpleDateFormat("yyyyMMddhhmmssSSS", Locale.US)

        /**
         * Save image to storage
         * @param image Image object got from onPicture() callback of EZCamCallback
         * @param file File where image is going to be written
         * @return File object pointing to the file uri, null if file already exist
         */
        private fun saveImage(image: Image, file: File) {
            require(!file.exists()) { "Image target file $file must not exist." }

            val buffer = image.planes[0].buffer!! // TODO: Won't work with YUV
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            file.writeBytes(bytes)
            i { "Finished writing image to $file: ${file.length()}" }
        }


        // from https://stackoverflow.com/questions/44022062/converting-yuv-420-888-to-jpeg-and-saving-file-results-distorted-image
        private fun NV21toJPEG(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            yuv.compressToJpeg(Rect(0, 0, width, height), quality, out)
            return out.toByteArray()
        }

        // from https://stackoverflow.com/questions/44022062/converting-yuv-420-888-to-jpeg-and-saving-file-results-distorted-image
        private fun YUV420toNV21(image: Image): ByteArray {
            val crop = image.cropRect
            val format = image.format
            val width = crop.width()
            val height = crop.height()
            val planes = image.planes
            val data = ByteArray(width * height * ImageFormat.getBitsPerPixel(format) / 8)
            val rowData = ByteArray(planes[0].rowStride)

            var channelOffset = 0
            var outputStride = 1
            // Y first, then interleaved UV
            for (i in planes.indices) {
                when (i) {
                    0 -> {
                        channelOffset = 0
                        outputStride = 1
                    }
                    1 -> {
                        channelOffset = width * height + 1
                        outputStride = 2
                    }
                    2 -> {
                        channelOffset = width * height
                        outputStride = 2
                    }
                }

                val buffer = planes[i].buffer
                val rowStride = planes[i].rowStride
                val pixelStride = planes[i].pixelStride

                val shift = if (i == 0) 0 else 1
                val w = width shr shift
                val h = height shr shift
                buffer.position(rowStride * (crop.top shr shift) + pixelStride * (crop.left shr shift))
                for (row in 0 until h) {
                    val length: Int
                    if (pixelStride == 1 && outputStride == 1) {
                        length = w
                        buffer.get(data, channelOffset, length)
                        channelOffset += length
                    } else {
                        length = (w - 1) * pixelStride + 1
                        buffer.get(rowData, 0, length)
                        for (col in 0 until w) {
                            data[channelOffset] = rowData[col * pixelStride]
                            channelOffset += outputStride
                        }
                    }
                    if (row < h - 1) {
                        buffer.position(buffer.position() + rowStride - length)
                    }
                }
            }
            return data
        }


        fun YUV420toLum(image: Image): IntArray {
            val crop = image.cropRect
            val width = crop.width()
            val height = crop.height()
            val planes = image.planes
            val data = ByteArray(width * height)
            val planeIdx = 0
            var channelOffset = 0
            val buffer = planes[planeIdx].buffer
            val rowStride = planes[planeIdx].rowStride
            val pixelStride = planes[planeIdx].pixelStride
            buffer.position(rowStride * crop.top + pixelStride * crop.left)
            for (row in 0 until height) {
                buffer.get(data, channelOffset, width)
                channelOffset += width
                if (row < height - 1) {
                    buffer.position(buffer.position() + rowStride - width)
                }
            }
            val result = IntArray(data.size)
            data.forEachIndexed { index, byte ->
                result[index] = byte.toInt() and 0xFF
            }
            return result
        }
    }
}

