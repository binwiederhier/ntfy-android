package io.heckel.ntfy.util

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

class QrCodeAnalyzer(private val onBarcodeScanned: (String?) -> Unit): ImageAnalysis.Analyzer {

    @OptIn(ExperimentalGetImage::class) override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image

        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = BarcodeScanning.getClient(options);

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        Log.d("QR Analyzer", "${barcode.valueType}")
                        val valueType = barcode.valueType
                        when (valueType) {
                            Barcode.TYPE_TEXT -> {
                                onBarcodeScanned(barcode.rawValue)
                                break
                            }
                        }
                    }
                }
                .addOnFailureListener {}
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }
}