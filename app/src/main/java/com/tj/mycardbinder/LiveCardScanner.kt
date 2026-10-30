package com.tj.mycardbinder

import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val cameraBlack = Color(0xFF0C1120)
private val scannerGold = Color(0xFFFFCD50)

@Composable
internal fun LiveCardScannerScreen(
    isMatching: Boolean,
    status: String,
    onTextDetected: (String) -> Unit,
    onClose: () -> Unit,
    onError: (String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(cameraBlack).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE CARD SCANNER", Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            TextButton(onClick = onClose) { Text("Close") }
        }
        Spacer(Modifier.height(6.dp))
        Text("Hold ONE card flat inside the guide. No picture button needed.",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LiveCardCamera(
                onTextDetected = onTextDetected,
                onError = onError,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 46.dp)
                    .border(3.dp, scannerGold, RoundedCornerShape(19.dp))
            )
            Text(
                if (isMatching) "MATCHING CARD…" else "ALIGN CARD IN FRAME",
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
                color = scannerGold, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(16.dp))
        if (isMatching) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = scannerGold)
            Spacer(Modifier.height(8.dp))
        }
        Text(status, color = Color(0xFFE6E6EA), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Back to my binder")
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun LiveCardCamera(
    onTextDetected: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val textCallback by rememberUpdatedState(onTextDetected)
    val errorCallback by rememberUpdatedState(onError)
    AndroidView(factory = { previewView }, modifier = modifier)

    DisposableEffect(context, owner, previewView) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analyzerExecutor = Executors.newSingleThreadExecutor()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val active = AtomicBoolean(true)
        val busy = AtomicBoolean(false)
        val lastProcessed = AtomicLong(0L)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (active.get()) {
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analyzerExecutor) { frame ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!active.get() || busy.get() || now - lastProcessed.get() < 900L) {
                            frame.close()
                        } else {
                            lastProcessed.set(now)
                            val media = frame.image
                            if (media == null) {
                                frame.close()
                            } else {
                                busy.set(true)
                                val image = InputImage.fromMediaImage(
                                    media, frame.imageInfo.rotationDegrees
                                )
                                recognizer.process(image)
                                    .addOnSuccessListener(mainExecutor) { recognized ->
                                        if (active.get()) {
                                            val text = recognized.text.trim()
                                            // Skip short text fragments that cannot identify a card.
                                            if (text.length >= 10 && text.any { it.isLetter() }) {
                                                textCallback(text)
                                            }
                                        }
                                    }
                                    .addOnFailureListener(mainExecutor) { error ->
                                        if (active.get()) errorCallback(
                                            "Hold the card steady in good light: " +
                                                (error.localizedMessage ?: "reading failed")
                                        )
                                    }
                                    .addOnCompleteListener {
                                        busy.set(false)
                                        frame.close()
                                    }
                            }
                        }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                    )
                } catch (error: Exception) {
                    errorCallback("Could not start camera: " +
                        (error.localizedMessage ?: "camera unavailable"))
                }
            }
        }, mainExecutor)
        onDispose {
            active.set(false)
            if (providerFuture.isDone) {
                try { providerFuture.get().unbindAll() } catch (_: Exception) { }
            }
            recognizer.close()
            analyzerExecutor.shutdown()
        }
    }
}