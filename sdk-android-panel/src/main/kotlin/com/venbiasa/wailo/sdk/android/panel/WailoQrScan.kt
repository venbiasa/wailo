package com.venbiasa.wailo.sdk.android.panel

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Opens ZXing's capture activity and hands back what it read, asking for the camera first.
 *
 * The permission is requested on tap rather than on open: prompting every developer who opens the panel
 * to look at something else is how a prompt gets denied for good. A denial is reported to the model
 * instead of retried, since the typed code is a complete alternative path.
 *
 * ZXing lives in this artifact only. `sdk-android` ships inside third-party apps and must stay
 * dependency-light (invariant #3), and a camera permission in a release manifest is not a footprint a
 * network interceptor gets to have.
 */
@Composable
internal fun rememberQrScanner(model: WailoPanelModel): () -> Unit {
    val context = LocalContext.current
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) model.cancelScanning() else model.scanned(contents)
    }
    val options = remember {
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Point at the QR in Studio's Settings")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanner.launch(options) else model.cameraDenied()
    }
    return {
        model.startScanning()
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanner.launch(options)
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }
}
