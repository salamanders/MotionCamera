package info.benjaminhill.motioncamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.app.ActivityCompat
import ar.com.hjg.pngj.ImageInfo
import ar.com.hjg.pngj.ImageLineByte
import ar.com.hjg.pngj.PngWriter
import com.github.ajalt.timberkt.Timber
import com.github.ajalt.timberkt.d
import com.github.ajalt.timberkt.i
import com.github.ajalt.timberkt.w
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.suspendCoroutine


class MainActivity : ScopedActivity() {

    override fun onResume() {
        super.onResume()
        if (missingPermissions.isNotEmpty()) {
            w { "Skipping this onResume because still missing permissions: ${missingPermissions.joinToString(", ")}" }
            return
        }
        launch {
            cameraSetup()

            delay(1, TimeUnit.SECONDS)

            val albumFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "/Camera/motioncam")
            albumFolder.mkdirs()

            captureStill().use { image ->
                val format = image.format
                require(format == ImageFormat.YUV_420_888)
                require(image.width == image.cropRect.width()) { "Image width and plane width should match: ${image.width}!=${image.cropRect.width()}" }

                val yPlane = image.planes[0]
                val bb = yPlane.buffer
                bb.rewind() // necessary?
                val bytes = ByteArray(bb.remaining())
                bb.get(bytes)

                require(yPlane.pixelStride == 1) { "pixel stride from YUV_420_888 should not be interleaved." }

                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    throw IllegalStateException("Missing required permission WRITE_EXTERNAL_STORAGE, try guarding with EZPermission.")
                }

                /*
                try {
                    val gsBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

                    val imageFile = File(albumFolder, "mc_${SDF.format(Date())}.png")
                    FileOutputStream(imageFile).use { fos ->
                        gsBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }

                    MediaScannerConnection.scanFile(this@MainActivity, arrayOf(imageFile.toString()), arrayOf("image/png")) { filePath, u ->
                        i { "MediaScanner scanFile finished $filePath $u" }
                    }
                } catch(e:Exception) {
                    Timber.e(e)
                }
                */

                try {
                    val cropRect = image.cropRect
                    val width = cropRect.width()
                    val height = cropRect.height()

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
                    i { "imageFile: ${imageFile.path} ${imageFile.length()}"}
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
        }
    }

    private lateinit var backgroundHandler: Handler
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var imageReaderYUV: ImageReader

    private suspend fun cameraSetup() {
        i { "Launching camera setup " }

        val backgroundThread = HandlerThread("MotionCam")
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
        i { "backgroundThread and backgroundHandler OK" }

        cameraManager = this.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val bestCameraId = cameraManager.cameraIdList.filterNotNull().find {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }!!
        i { "bestCameraId:$bestCameraId" }

        val previewSurface = suspendCoroutine<Surface> { cont ->
            if (cameraPreviewTextureView.isAvailable) {
                cont.resume(Surface(cameraPreviewTextureView.surfaceTexture))
            } else {
                cameraPreviewTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                        cont.resume(Surface(surfaceTexture))
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        }
        i { "previewSurface: ${previewSurface.isValid}" }

        @SuppressLint("MissingPermission")
        cameraDevice = suspendCoroutine { cont ->

            cameraManager.openCamera(bestCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = cont.resume(camera)

                override fun onDisconnected(camera: CameraDevice) {
                    try {
                        cont.resumeWithException(Exception("Problem with cameraManager.openCamera onDisconnected"))
                    } catch (e: IllegalStateException) {
                        w { "Swallowing onDisconnected:resumeWithException because not the first resume." }
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    when (error) {
                        ERROR_CAMERA_DEVICE -> w { "CameraDevice.StateCallback: Camera device has encountered a fatal error." }
                        ERROR_CAMERA_DISABLED -> w { "CameraDevice.StateCallback: Camera device could not be opened due to a device policy." }
                        ERROR_CAMERA_IN_USE -> w { "CameraDevice.StateCallback: Camera device is in use already." }
                        ERROR_CAMERA_SERVICE -> w { "CameraDevice.StateCallback: Camera service has encountered a fatal error." }
                        ERROR_MAX_CAMERAS_IN_USE -> w { "CameraDevice.StateCallback: Camera device could not be opened because there are too many other open camera devices." }
                    }
                    try {
                        cont.resumeWithException(Exception("openCamera onError $error"))
                    } catch (e: IllegalStateException) {
                        w { "Swallowing onError:resumeWithException because not the first resume." }
                    }

                }
            }, backgroundHandler)
        }
        i { "cameraDevice: ${cameraDevice.id}" }

        val cameraCharacteristics: CameraCharacteristics = cameraManager.getCameraCharacteristics(bestCameraId)

        // Smallest size
        val imageSizeForYUVImageReader: Size = cameraCharacteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(ImageFormat.YUV_420_888)
                .minBy { it.width * it.height }!!
        i { "imageSizeForYUVImageReader (smallest): $imageSizeForYUVImageReader" }

        imageReaderYUV = ImageReader.newInstance(imageSizeForYUVImageReader.width, imageSizeForYUVImageReader.height, ImageFormat.YUV_420_888, 3)

        cameraCaptureSession = suspendCoroutine { cont ->
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReaderYUV.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) = cont.resume(session).also {
                    d { "Created cameraCaptureSession through createCaptureSession.onConfigured" }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    val err = Exception("createCaptureSession.onConfigureFailed")
                    Timber.e(err) { "onConfigureFailed: Could not configure capture session." }
                    cont.resumeWithException(err)
                }
            }, backgroundHandler)
        }
        i { "cameraCaptureSession OK" }


        val captureRequestPreview = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.SENSOR_FRAME_DURATION, (1_000_000_000.0 / 4).toLong())
        }
        i { "preview at 4 frames/second" }

        cameraCaptureSession.setRepeatingRequest(captureRequestPreview.build(), null, backgroundHandler)
        i { "cameraCaptureSession setRepeatingRequest OK" }
    }

    private suspend fun captureStill(): Image = suspendCoroutine { cont ->
        val captureRequestStill = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT).apply {
            addTarget(imageReaderYUV.surface)
        }
        val onYUVImageAvailableForImageReader = ImageReader.OnImageAvailableListener {
            i { "onYUVImageAvailableForImageReader" }
            cont.resume(imageReaderYUV.acquireLatestImage())
        }
        imageReaderYUV.setOnImageAvailableListener(onYUVImageAvailableForImageReader, backgroundHandler)
        cameraCaptureSession.capture(captureRequestStill.build(), null, backgroundHandler)
    }

    companion object {
        private val SDF = SimpleDateFormat("yyyyMMddhhmmssSSS", Locale.US)
    }
}
