package com.example.cameraxbarcode


import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.ImageAnalysis.Analyzer
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cameraxbarcode.databinding.ActivityMainBinding
import com.example.cameraxbarcode.ml.Model

import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.lang.Math.exp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.exp


class MainActivity : AppCompatActivity(), Analyzer {
    private val model_w = 640
    private val model_h = 480
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var bitmapBuffer: Bitmap
    private lateinit var cameraExecutor: ExecutorService


    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }


    private fun takePhoto() {}

    private fun captureVideo() {}

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .build()

            val imageAnalyzer =
                ImageAnalysis.Builder().setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also {
                        it.targetRotation = Surface.ROTATION_270
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            analyze(imageProxy)
                        }
                    }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "cameraxbarcode"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    override fun analyze(image: ImageProxy) {
        if (!::bitmapBuffer.isInitialized) {
            bitmapBuffer = Bitmap.createBitmap(
                image.width, image.height, Bitmap.Config.ARGB_8888
            )
        }
        bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer)
        val resizedBitmap = Bitmap.createScaledBitmap(bitmapBuffer, model_w, model_h, false)
        classifyImage(resizedBitmap)
        image.close()
    }

    private fun classifyImage(resizedBitmap: Bitmap?) {
        val model = Model.newInstance(applicationContext)
        val byteBuffer: ByteBuffer = getByteBufferFromBitmap(resizedBitmap)
        // Creates inputs for reference.
        val inputFeature0 =
            TensorBuffer.createFixedSize(intArrayOf(1, 3, 640, 480), DataType.FLOAT32)
        inputFeature0.loadBuffer(byteBuffer)
        inputFeature0.apply {
            NormalizeOp(
                floatArrayOf(0.485f, 0.456f, 0.406f),
                floatArrayOf(0.229f, 0.224f, 0.225f)
            )
        }
        // Runs model inference and gets result.
        val outputs = model.process(inputFeature0)
        val outputFeature0 = outputs.outputFeature0AsTensorBuffer

        for (i in 0 until outputFeature0.floatArray.size) {
            var output = 1 / (1 + exp(-outputFeature0.floatArray[i]))
            Log.d(TAG, "Model Output $output")

        }

        // Releases model resources if no longer used.
        model.close()

    }

    private fun getByteBufferFromBitmap(resizedBitmap: Bitmap?): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(
            4 * (resizedBitmap?.width ?: 0) * (resizedBitmap?.height
                ?: 0) * 3
        )
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues: IntArray = IntArray(resizedBitmap!!.width * resizedBitmap.height)
        resizedBitmap.getPixels(
            intValues, 0,
            resizedBitmap.width, 0, 0, resizedBitmap.width, resizedBitmap.height
        )
        var pixel: Int = 0
        for (i in 0 until resizedBitmap.width) {
            for (j in 0 until resizedBitmap.height) {
                val intVal = intValues[pixel++]
                byteBuffer.putFloat((intVal shr 16 and 0xFF) * (1f / 255))
                byteBuffer.putFloat((intVal shr 8 and 0xFF) * (1f / 255))
                byteBuffer.putFloat((intVal and 0xFF) * (1f / 255))
            }
        }
        return byteBuffer

    }
}