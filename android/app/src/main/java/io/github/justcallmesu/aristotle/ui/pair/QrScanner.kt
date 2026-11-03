package io.github.justcallmesu.aristotle.ui.pair

import android.content.Context
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Reads one QR code and reports it on the main thread; a seam so the pair screen needs no camera in tests. */
fun interface QrScanner {
    fun scan(onOutcome: (ScanOutcome) -> Unit)
}

/**
 * The Google code scanner (T08): Play services shows the camera and hands back only the code's text, so the
 * app holds no CAMERA permission. QR codes only. Without Play services, or when its scanner module is
 * missing and cannot be installed, the outcome is [ScanOutcome.Unavailable].
 */
class GmsQrScanner(private val context: Context) : QrScanner {
    private val client: GmsBarcodeScanner by lazy {
        GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }

    override fun scan(onOutcome: (ScanOutcome) -> Unit) {
        val task = try {
            client.startScan()
        } catch (_: RuntimeException) {
            // No Play services at all can surface here rather than as a failed task.
            onOutcome(ScanOutcome.Unavailable)
            return
        }
        task.addOnSuccessListener { barcode -> onOutcome(ScanOutcome.Scanned(barcode.rawValue.orEmpty())) }
            .addOnCanceledListener { onOutcome(ScanOutcome.Cancelled) }
            .addOnFailureListener { e -> onOutcome(if (e.isUserCancel()) ScanOutcome.Cancelled else ScanOutcome.Unavailable) }
    }

    /** Back pressed in the scanner, or a second tap while one scan is open: nothing to tell the user. */
    private fun Exception.isUserCancel(): Boolean = this is MlKitException &&
        (errorCode == MlKitException.CODE_SCANNER_CANCELLED || errorCode == MlKitException.CODE_SCANNER_TASK_IN_PROGRESS)
}
