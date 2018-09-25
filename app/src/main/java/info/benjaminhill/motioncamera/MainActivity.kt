package info.benjaminhill.motioncamera

import android.annotation.SuppressLint
import android.content.Context
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
import com.github.ajalt.timberkt.Timber
import com.github.ajalt.timberkt.d
import com.github.ajalt.timberkt.i
import com.github.ajalt.timberkt.w
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.suspendCoroutine


class MainActivity : ScopedActivity() {
    private lateinit var backgroundHandler: Handler
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var imageReaderYUV: ImageReader
    private lateinit var albumFolder: File

    override fun onResume() {
        super.onResume()
        if (missingPermissions.isNotEmpty()) {
            w { "Skipping this onResume because still missing permissions: ${missingPermissions.joinToString(", ")}" }
            return
        }

        albumFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "/Camera/motioncam").apply {
            mkdir()
        }

        launch {
            cameraSetup()
            delay(5, TimeUnit.SECONDS)
            w { "CAPTURING IMAGE NOW" }
            val ic = ImageCapture(albumFolder)
            captureStill().use { image ->
                i { "captureStill returned an image of ${image.width}x${image.height}" }
                ic.processImage(image)
            }
        }
    }


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
                        i { "cameraPreviewTextureView available with res ($width x $height)" }
                        cont.resume(Surface(surfaceTexture))
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        }

        i { "previewSurface: ${previewSurface.isValid} (${cameraPreviewTextureView.layoutParams.width}x${cameraPreviewTextureView.layoutParams.height})" }

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

        val imageSizeForPreview: Size = cameraCharacteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(SurfaceTexture::class.java)
                .minBy { it.width * it.height }!!
        i { "imageSizeForPreview (smallest): $imageSizeForPreview" }

        imageReaderYUV = ImageReader.newInstance(imageSizeForYUVImageReader.width, imageSizeForYUVImageReader.height, ImageFormat.YUV_420_888, 3)

        cameraCaptureSession = suspendCoroutine { cont ->
            cameraDevice.createCaptureSession(listOf(previewSurface, imageReaderYUV.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) = cont.resume(session).also {
                    d { "Created cameraCaptureSession through createCaptureSession.onConfigured, with two target surfaces" }
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
        i { "About to capture a still.  imageReaderYUV.width=${imageReaderYUV.width}, preview.width=${cameraPreviewTextureView.width}" }

        val captureRequestStill = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureRequestStill.addTarget(imageReaderYUV.surface)
        imageReaderYUV.setOnImageAvailableListener({ cont.resume(imageReaderYUV.acquireLatestImage()) }, backgroundHandler)
        cameraCaptureSession.capture(captureRequestStill.build(), null, backgroundHandler)
    }
}
