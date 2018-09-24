package info.benjaminhill.motioncamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraDevice
import android.media.Image
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import androidx.core.app.ActivityCompat
import com.github.ajalt.timberkt.i
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ImageCapture {

    fun captureImage(cameraDevice: CameraDevice, imageReaderYUV: ImageReader, backgroundHandler: Handler, context:Context) {
        val captureRequestBuilderForYUVImageReader = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureRequestBuilderForYUVImageReader.addTarget(imageReaderYUV.surface)

        imageReaderYUV.setOnImageAvailableListener({
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                throw IllegalStateException("You don't have the required permission WRITE_EXTERNAL_STORAGE, try guarding with EZPermission.")
            }
            val albumFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "/Camera/motioncam")
            albumFolder.mkdirs()
            val imageFile = File(albumFolder, "image_${SDF.format(Date())}.jpg")
            imageReaderYUV.acquireLatestImage().use { image ->
                saveImage(image, imageFile)
            }
            MediaScannerConnection.scanFile(context, arrayOf(imageFile.toString()), arrayOf("image/jpeg")) { filePath, u ->
                i { "scanFile finished $filePath $u" }
            }
        }, backgroundHandler)
        i { "imageReaderYUV setOnImageAvailableListener OK" }

        //cameraCaptureSession().capture(captureRequestBuilderForJPEGImageReader().build(), null, backgroundHandler)
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



    }
}
