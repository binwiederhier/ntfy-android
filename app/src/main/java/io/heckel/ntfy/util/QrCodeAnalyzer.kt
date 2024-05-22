package io.heckel.ntfy.util

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

typealias BarcodeScannedCallback = (barcode: String) -> Unit

class QrCodeAnalyzer(private val onBarcodeScanned: BarcodeScannedCallback) : ImageAnalysis.Analyzer {

    private var lastSuccessfulScanTimestamp: Long = 0
    private var lastProcessedTimestamp: Long = 0

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        if (System.currentTimeMillis() - lastSuccessfulScanTimestamp < 3000) {
            image.close()
            return
        } else if (System.currentTimeMillis() - lastProcessedTimestamp < 100) {
            image.close()
            return
        }

        val img = image.image
        if (img != null) {
            val inputImage = InputImage.fromMediaImage(img, image.imageInfo.rotationDegrees)

            // Process image searching for barcodes
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()

            val scanner = BarcodeScanning.getClient(options)

            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        if (barcode.valueType !== Barcode.TYPE_URL) continue

                        onBarcodeScanned(barcodes[0].url.url)
                        lastSuccessfulScanTimestamp = System.currentTimeMillis()
                        break
                    }
                }
                .addOnFailureListener {
                }
                .addOnCompleteListener {
                    image.close()
                }
        }

        lastProcessedTimestamp = System.currentTimeMillis()
    }
}