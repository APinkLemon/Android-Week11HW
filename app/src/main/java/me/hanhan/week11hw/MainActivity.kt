package me.hanhan.week11hw

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.*
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.RuntimeException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var fileEdit : EditText
    private lateinit var textureView : TextureView
    private lateinit var fileInfo : TextView
    private lateinit var btnReverse : ImageButton
    private lateinit var btnShutter : ImageButton
    private lateinit var lastImageView : ImageView

    private lateinit var bytesHistory : ByteArray
    private lateinit var bitmapHistory : Bitmap

    private lateinit var camera : CameraDevice
    private lateinit var imageReader : ImageReader
    private lateinit var cameraManager : CameraManager
    private lateinit var cameraCaptureSession : CameraCaptureSession

    private lateinit var textureSurface : Surface
    private lateinit var imageReaderSurface : Surface

    private val mediaRecorder = MediaRecorder()
    private var isRecording = false

    private var outputFile : File? = null

    private var cameraId = 0


    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_DENIED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA,
                        android.Manifest.permission.RECORD_AUDIO,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        REQUEST_PERMISSION
            )
        }

        fileEdit = findViewById(R.id.file_edit)
        textureView = findViewById(R.id.texture_view)
        fileInfo = findViewById(R.id.file_info)
        btnReverse = findViewById(R.id.btn_reverse)
        btnShutter = findViewById(R.id.btn_shutter)
        lastImageView = findViewById(R.id.last_img_view)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                textureSurface = Surface(textureView.surfaceTexture)
                startCamera()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            }
        }

        btnReverse.setOnClickListener {
            cameraCaptureSession?.close()
            camera.close()
            imageReader.close()

            cameraId = 1 - cameraId
            startCamera()
        }
    }

    private fun startCamera() {
        val camStateCallback = object : CameraDevice.StateCallback() {
            @RequiresApi(Build.VERSION_CODES.O)
            @SuppressLint("ClickableViewAccessibility")
            override fun onOpened(camera: CameraDevice) {
                this@MainActivity.camera = camera

                val cameraCharacteristics = cameraManager.getCameraCharacteristics(camera.id)
                val streamConfigurationMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = streamConfigurationMap!!.getOutputSizes(ImageFormat.JPEG)
                Log.d("startCamera()", "${sizes.size}, [0]: ${sizes[0].width}, ${sizes[0].height}")
                imageReader = ImageReader.newInstance(sizes[0].width, sizes[0].height, ImageFormat.JPEG, 2)
                imageReader.setOnImageAvailableListener({ reader ->
                    val image = reader!!.acquireLatestImage()

                    val photoW = image.width
                    val photoH = image.height
                    val targetW = lastImageView.width
                    val targetH = lastImageView.width
                    var inSampleSize = 1
                    while (photoW > targetW * inSampleSize || photoH > targetH * inSampleSize)
                        inSampleSize *= 2

                    Log.d("Input img size", "$photoW x $photoH")
                    Log.d("Target img size", "$targetW x $targetH")
                    Log.d("Output img size", "${photoW / (1 shl inSampleSize)} x ${targetH / (1 shl inSampleSize)}")

                    val buffer = image.planes[0].buffer
                    bytesHistory = ByteArray(buffer.remaining())
                    buffer.get(bytesHistory)
                    image.close()

                    outputFile = createOutputFile(MEDIA_TYPE_IMAGE)
                    val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                    "Last Shoot:\nImage, $photoW × $photoH\n${outputFile!!.name}\n${currentTime}\n".also { fileInfo.text = it }

                    val fos = FileOutputStream(outputFile)
                    fos.write(bytesHistory)
                    fos.close()

                    val bmOptions = BitmapFactory.Options()
                    bmOptions.inSampleSize = inSampleSize
                    bitmapHistory = BitmapFactory.decodeByteArray(bytesHistory, 0, bytesHistory.size, bmOptions)
                    lastImageView.setImageBitmap(bitmapHistory)
                }, null)

                imageReaderSurface = imageReader.surface

                try {
                    val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    requestBuilder.addTarget(textureSurface!!)
                    val request = requestBuilder.build()

                    val camCaptureSessionStateCallback = object :
                        CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            cameraCaptureSession = session
                            try {
                                session.setRepeatingRequest(request, null, null)
                            } catch (e : CameraAccessException) {
                                e.printStackTrace()
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }

                    camera.createCaptureSession(listOf(textureSurface, imageReaderSurface), camCaptureSessionStateCallback, null)
                } catch (e : CameraAccessException) {
                    e.printStackTrace()
                }

                btnShutter.setOnClickListener{
                    lateinit var requestBuilderImageReader : CaptureRequest.Builder
                    try {
                        requestBuilderImageReader = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    } catch (e : CameraAccessException) {
                        e.printStackTrace()
                    }
                    requestBuilderImageReader.set(CaptureRequest.JPEG_ORIENTATION, 90)
                    requestBuilderImageReader.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                    requestBuilderImageReader.addTarget(imageReaderSurface)

                    try {
                        cameraCaptureSession!!.capture(requestBuilderImageReader.build(), null, null)
                    } catch (e : CameraAccessException) {
                        e.printStackTrace()
                    }
                }

                btnShutter.setOnLongClickListener{
                    isRecording = true
                    startRecording()
                    true
                }

                btnShutter.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP && isRecording) {
                        isRecording = false
                        stopRecording()
                        val mediaMetadataRetriever = MediaMetadataRetriever()
                        mediaMetadataRetriever.setDataSource(outputFile!!.path)
                        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                        val duration = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toInt() / 1000.toDouble()
                        ("Last Shoot:\n" + "Video, ${textureView.width} × ${textureView.height}, $duration s\n" + "${outputFile!!.name}\n" + "$currentTime").also { fileInfo.text = it }
                        bitmapHistory = ThumbnailUtils.createVideoThumbnail(outputFile!!.path, MediaStore.Video.Thumbnails.MICRO_KIND)!!.scale(lastImageView.width, lastImageView.height)
                        lastImageView.setImageBitmap(bitmapHistory)
                        return@setOnTouchListener true
                    }
                    return@setOnTouchListener false
                }
            }

            override fun onDisconnected(camera: CameraDevice) {}

            override fun onError(camera: CameraDevice, error: Int) {}

        }

        cameraManager.openCamera(cameraManager.cameraIdList[cameraId], camStateCallback, null)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun prepareVideoRecorder() : Boolean {
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        if (camera.id.toInt() == 0)
            mediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH))
        else
            mediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_LOW))
        mediaRecorder.setPreviewDisplay(textureSurface)
        outputFile = createOutputFile(MEDIA_TYPE_VIDEO)
        mediaRecorder.setOutputFile(outputFile)

        mediaRecorder.setOrientationHint(
            when(windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            })
        try {
            mediaRecorder.prepare()
        } catch (e: IllegalStateException) {
            mediaRecorder.reset()
            mediaRecorder.release()
            return false
        } catch (e: IOException) {
            mediaRecorder.reset()
            mediaRecorder.release()
            return false
        }
        return true
    }

    private fun createOutputFile(type: Int): File {
        val mediaStorageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)

        val fileName: String =
            if (fileEdit.text.isNotEmpty()) fileEdit.text.toString()
            else SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())

        return when (type) {
            MEDIA_TYPE_IMAGE -> File(mediaStorageDir!!.path + File.separator + fileName + ".jpg")
            MEDIA_TYPE_VIDEO -> File(mediaStorageDir!!.path + File.separator + fileName + ".mp4")
            else -> throw RuntimeException("Invalid mediaFile type: $type")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startRecording() {
        cameraCaptureSession?.close()
        prepareVideoRecorder()

        val previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

        previewBuilder.addTarget(textureSurface!!)

        val recorderSurface = mediaRecorder.surface
        previewBuilder.addTarget(recorderSurface)

        camera.createCaptureSession(listOf(textureSurface, recorderSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                cameraCaptureSession = session
                try {
                    previewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    cameraCaptureSession!!.setRepeatingRequest(previewBuilder.build(), null, null)
                } catch (e : CameraAccessException) {
                    e.printStackTrace()
                }
                runOnUiThread{
                    mediaRecorder.start()
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}

        }, null)

    }
    private fun stopRecording() {
        mediaRecorder.stop()
        mediaRecorder.reset()
        startCamera()
    }

    override fun onPause() {
        cameraCaptureSession?.close()
        camera.close()
        imageReader.close()

        super.onPause()
    }

    companion object {
        const val REQUEST_PERMISSION = 1

        const val MEDIA_TYPE_IMAGE = 2
        const val MEDIA_TYPE_VIDEO = 3
    }
}