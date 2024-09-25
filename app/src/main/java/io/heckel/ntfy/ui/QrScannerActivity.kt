package io.heckel.ntfy.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.QrCodeAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class QrScannerActivity : AppCompatActivity(R.layout.activity_qr_scanner) {
    private lateinit var cameraExecutor: ExecutorService
    private final val ntfyQrDataPrefix = "ntfy://"
    private final val ntfyQrDataTopicSeperator = ";"



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        checkCameraPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    /**
     * 1. This function is responsible to request the required CAMERA permission
     */
    private fun checkCameraPermission() {
        try {
            val requiredPermissions = arrayOf(Manifest.permission.CAMERA)
            ActivityCompat.requestPermissions(this, requiredPermissions, 0)
        } catch (e: IllegalArgumentException) {
            checkIfCameraPermissionIsGranted()
        }
    }

    /**
     * 2. This function will check if the CAMERA permission has been granted.
     * If so, it will call the function responsible to initialize the camera preview.
     * Otherwise, it will raise an alert.
     */
    private fun checkIfCameraPermissionIsGranted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            // Permission granted: start the preview
            startCamera()
        } else {
            // Permission denied
            AlertDialog.Builder(this)
                .setTitle("Permission required")
                .setMessage("This application needs to access the camera to process barcodes")
                .setPositiveButton("Ok") { _, _ ->
                    // Keep asking for permission until granted
                    checkCameraPermission()
                }
                .setCancelable(false)
                .create()
                .apply {
                    setCanceledOnTouchOutside(false)
                    show()
                }
        }
    }

    /**
     * 3. This function is executed once the user has granted or denied the missing permission
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkIfCameraPermissionIsGranted()
    }

    /**
     * This function is responsible for the setup of the camera preview and the image analyzer.
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(this.findViewById<PreviewView>(R.id.preview_view).surfaceProvider)
                }

            // Image analyzer
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QrCodeAnalyzer { data ->
                        Log.d(TAG, "Found URL with value $data")
                        val intent = Intent()
                        try {
                            val (qrUrl, qrTopic) = parseQrCodeData(data)
                            intent.putExtra(QR_CODE_DATA_SERVER_URL, qrUrl)
                            intent.putExtra(QR_CODE_DATA_TOPIC, qrTopic)
                            setResult(Activity.RESULT_OK, intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "QR reading failed with message ${e.message.toString()}")
                            setResult(Activity.RESULT_CANCELED, intent)
                        }
                        finish()

                    })
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

            } catch (exc: Exception) {
                exc.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun parseQrCodeData(data: String?): Pair<String, String> {
        if (data == null || !data.startsWith(ntfyQrDataPrefix)) {
            throw Exception("Bad QR code data format")
        }

        val startIndex = ntfyQrDataPrefix.length
        val seperatorIndex = data.indexOf(ntfyQrDataTopicSeperator)

        if (seperatorIndex == -1) {
            throw Exception("Bad QR code data format")
        }

        val url = data.substring(startIndex, seperatorIndex)
        val topicName = data.substring(seperatorIndex + 1)

        return Pair(url, topicName)
    }

    companion object {
        const val TAG = "NtfyQrScannerActivity"
        const val QR_CODE_DATA_SERVER_URL = "qrServerUrls"
        const val QR_CODE_DATA_TOPIC = "qrTopic"
    }
}