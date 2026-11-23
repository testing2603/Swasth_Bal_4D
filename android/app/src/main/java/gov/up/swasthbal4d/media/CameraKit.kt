package gov.up.swasthbal4d.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

/** Asks for runtime permissions once, then shows [content]. */
@Composable
fun WithPermissions(perms: List<String>, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    fun granted() = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var ok by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = granted() }
    LaunchedEffect(Unit) { if (!ok) launcher.launch(perms.toTypedArray()) }
    if (ok) content() else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("कैमरा / माइक की अनुमति दें", color = Color.White)
    }
}

val CAMERA_PERMS = listOf(Manifest.permission.CAMERA)
val AV_PERMS = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

/** Live quality checks shown as chips on the frame guide (design screen 7). */
data class LiveChecks(val light: Boolean = false, val steady: Boolean = false, val subject: Boolean = false) {
    val allOk get() = light && steady && subject
}

/**
 * One CameraX controller per capture screen. Handles still photos, video clips, QR scanning,
 * OCR and face tracking through the same preview.
 */
class CameraHandle(private val context: Context) {
    val controller = LifecycleCameraController(context).apply {
        setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS or CameraController.VIDEO_CAPTURE)
    }
    private val exec = Executors.newSingleThreadExecutor()
    private var recording: Recording? = null

    fun useFront(front: Boolean) {
        controller.cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    fun flash(on: Boolean) {
        controller.imageCaptureFlashMode = if (on) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    fun takePhoto(onFile: (File) -> Unit, onError: (String) -> Unit) {
        val f = File(context.cacheDir, "${UUID.randomUUID()}.jpg")
        controller.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onFile(PhotoCompressor.compress(f, mediaDir(context)))
                }
                override fun onError(exc: ImageCaptureException) = onError(exc.message ?: "camera error")
            })
    }

    @SuppressLint("MissingPermission") // AV_PERMS requested by WithPermissions before this is reachable
    fun startVideo(onSaved: (File) -> Unit) {
        val f = File(mediaDir(context), "${UUID.randomUUID()}.mp4")
        recording = controller.startRecording(FileOutputOptions.Builder(f).build(), AudioConfig.create(true),
            ContextCompat.getMainExecutor(context)) { ev ->
            if (ev is VideoRecordEvent.Finalize && !ev.hasError()) onSaved(f)
        }
    }

    fun stopVideo() {
        recording?.stop()
        recording = null
    }

    /** Frame guide checks: brightness, steadiness, and (for face captures) a face filling the frame. */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    fun analyzeQuality(needFace: Boolean, onChecks: (LiveChecks, Float?) -> Unit) {
        val faces = FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
        var lastLuma = -1f
        var lastCx = -1f
        controller.setImageAnalysisAnalyzer(exec) { proxy: ImageProxy ->
            val luma = averageLuma(proxy)
            val light = luma in 60f..220f
            val media = proxy.image
            if (media == null) { proxy.close(); return@setImageAnalysisAnalyzer }
            val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            faces.process(img).addOnCompleteListener { task ->
                val face = if (task.isSuccessful) task.result.maxByOrNull { it.boundingBox.width() } else null
                val rotated = proxy.imageInfo.rotationDegrees % 180 != 0
                val w = (if (rotated) img.height else img.width).toFloat()
                val h = (if (rotated) img.width else img.height).toFloat()
                val cx = face?.boundingBox?.exactCenterX() ?: -1f
                val steady = abs(luma - lastLuma) < 6f && (cx < 0 || lastCx < 0 || abs(cx - lastCx) < w * 0.03f)
                lastLuma = luma
                lastCx = cx
                val faceOk = face != null && face.boundingBox.width() > w * 0.25f &&
                    face.boundingBox.left > 0 && face.boundingBox.right < w && face.boundingBox.top > 0 && face.boundingBox.bottom < h
                onChecks(LiveChecks(light, steady, if (needFace) faceOk else true), face?.headEulerAngleY)
                proxy.close()
            }
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    fun scanQr(onCode: (String) -> Unit) {
        val scanner = BarcodeScanning.getClient()
        controller.setImageAnalysisAnalyzer(exec) { proxy ->
            val media = proxy.image
            if (media == null) { proxy.close(); return@setImageAnalysisAnalyzer }
            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener { codes -> codes.firstOrNull()?.rawValue?.let(onCode) }
                .addOnCompleteListener { proxy.close() }
        }
    }

    /** Device reading from a photo (ML Kit Text Recognition v2) - returns all numbers found. */
    fun readNumbers(photo: File, onNumbers: (List<Double>) -> Unit) {
        val img = InputImage.fromFilePath(context, android.net.Uri.fromFile(photo))
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img)
            .addOnSuccessListener { text ->
                val nums = Regex("""\d{1,3}(?:[.,]\d)?""").findAll(text.text)
                    .mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }.toList()
                onNumbers(nums)
            }
            .addOnFailureListener { onNumbers(emptyList()) }
    }

    fun clearAnalyzer() = controller.clearImageAnalysisAnalyzer()

    private fun averageLuma(p: ImageProxy): Float {
        val buf = p.planes[0].buffer
        var sum = 0L
        var n = 0
        var i = 0
        while (i < buf.remaining()) { sum += (buf.get(i).toInt() and 0xFF); n++; i += 97 }
        return if (n == 0) 0f else sum.toFloat() / n
    }
}

@Composable
fun rememberCamera(front: Boolean = false): CameraHandle {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val cam = remember { CameraHandle(ctx) }
    LaunchedEffect(front) { cam.useFront(front) }
    DisposableEffect(owner) {
        cam.controller.bindToLifecycle(owner)
        onDispose { cam.clearAnalyzer(); cam.controller.unbind() }
    }
    return cam
}

@Composable
fun CameraPreview(cam: CameraHandle, modifier: Modifier = Modifier.fillMaxSize()) {
    AndroidView(factory = { c ->
        PreviewView(c).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            controller = cam.controller
        }
    }, modifier = modifier)
}
