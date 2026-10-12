package com.mavislibrary.app.ui.scan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mavislibrary.app.data.model.Book
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScanScreen(
    resolver: ScanResultResolver,
    pending: PendingScans,
    online: Boolean,
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onSaveBook: suspend (Book) -> Unit,
    onSearch: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val activity = context as? Activity
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var requested by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }
    var currentIsbn by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ScanLookup?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savedPending by remember { mutableStateOf(false) }
    var savedBook by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf(false) }
    var flashAvailable by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; requested = true }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val permanentlyDenied = requested && !granted && activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
    val submit: (String) -> Unit = { raw ->
        val isbn = IsbnValidator.normalize(raw)
        if (isbn != null && !busy) {
            currentIsbn = isbn; result = null; busy = true; savedPending = false; savedBook = false
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            scope.launch {
                result = if (online) resolver.resolve(isbn) else ScanLookup.Offline
                busy = false
            }
        }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().testTag("scan_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Scan a book barcode", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (flashAvailable && granted && !cameraError && !busy && result == null) {
                TextButton(onClick = { torch = !torch }) { Text(if (torch) "Torch off" else "Torch on") }
            }
        }
        if (granted && !cameraError) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CameraBarcodePreview(
                    active = !busy && result == null,
                    torch = torch,
                    lifecycleOwner = lifecycleOwner,
                    onFlash = { flashAvailable = it },
                    onError = { cameraError = true },
                    onCode = submit,
                )
                Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                    drawRect(Color.Black.copy(alpha = .62f))
                    val width = size.width * .82f
                    val height = (size.height * .36f).coerceAtMost(width * .65f)
                    drawRoundRect(Color.Transparent, Offset((size.width - width) / 2, (size.height - height) / 2),
                        androidx.compose.ui.geometry.Size(width, height), CornerRadius(24.dp.toPx()), blendMode = BlendMode.Clear)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text(when {
                    cameraError -> "Camera isn't available. You can still type an ISBN below."
                    permanentlyDenied -> "Camera access is blocked. Open app settings to enable it, or type an ISBN."
                    else -> "Mavis uses the camera only on this screen to read a barcode. Nothing is recorded or uploaded."
                })
                Spacer(Modifier.height(12.dp))
                if (!granted && !permanentlyDenied) Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text(if (requested) "Try camera again" else "Continue to camera")
                }
                if (permanentlyDenied) OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("Open app settings") }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(when {
                busy -> "Looking up ${currentIsbn.orEmpty()}..."
                result is ScanLookup.Found -> (result as ScanLookup.Found).book.let { "Found: ${it.title} by ${it.authorLine}" }
                result == ScanLookup.NotFound -> "Not found — ISBN ${currentIsbn.orEmpty()}. Try Search."
                result == ScanLookup.Offline -> "No connection — the barcode was read (ISBN ${currentIsbn.orEmpty()}). Save it to find later?"
                result == ScanLookup.Error -> "The library couldn't answer. Try again in a moment."
                else -> "Point at a barcode"
            }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("scan_status"))
            when (val found = result) {
                is ScanLookup.Found -> {
                    Button(onClick = { onOpenBook(found.book) }) { Text("Open book") }
                    OutlinedButton(onClick = { scope.launch { onSaveBook(found.book); savedBook = true } }, enabled = !savedBook) {
                        Text(if (savedBook) "Saved to shelf" else "Save to shelf")
                    }
                }
                ScanLookup.NotFound -> TextButton(onClick = onSearch) { Text("Try Search") }
                ScanLookup.Offline -> Button(onClick = { scope.launch { currentIsbn?.let { pending.add(it) }; savedPending = true } }, enabled = !savedPending) {
                    Text(if (savedPending) "Saved for later" else "Save ISBN for later")
                }
                ScanLookup.Error -> TextButton(onClick = { currentIsbn?.let(submit) }) { Text("Try again") }
                null -> Unit
            }
            OutlinedTextField(value = manual, onValueChange = { manual = it }, label = { Text("Type ISBN instead") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { submit(manual) }, enabled = IsbnValidator.normalize(manual) != null && !busy) { Text("Look up ISBN") }
            if (manual.isNotBlank() && IsbnValidator.normalize(manual) == null) Text("Check the ISBN digits and checksum.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CameraBarcodePreview(
    active: Boolean,
    torch: Boolean,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onFlash: (Boolean) -> Unit,
    onError: () -> Unit,
    onCode: (String) -> Unit,
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    val callback by rememberUpdatedState(onCode)
    val errorCallback by rememberUpdatedState(onError)
    val flashCallback by rememberUpdatedState(onFlash)
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    DisposableEffect(active, lifecycleOwner) {
        if (!active) return@DisposableEffect onDispose { }
        val disposed = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val decoder = BarcodeFrameDecoder()
        val debouncer = ScanDebouncer()
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            if (!disposed.get()) try {
                provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    try {
                        if (!disposed.get()) {
                            val accepted = debouncer.offer(decoder.decode(proxy))
                            if (accepted != null) ContextCompat.getMainExecutor(context).execute {
                                if (!disposed.get()) callback(accepted)
                            }
                        }
                    } finally { proxy.close() }
                }
                val bound = provider!!.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = bound
                flashCallback(bound.cameraInfo.hasFlashUnit())
            } catch (_: Exception) { errorCallback() }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed.set(true)
            provider?.unbindAll()
            camera = null
            executor.shutdown()
        }
    }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
