package gov.up.swasthbal4d.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaRecorder
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale
import java.util.UUID

/** Photo -> WebP, longest side 1280 px, quality ~75 (design 7.2: 150-250 KB each). */
object PhotoCompressor {
    fun compress(src: File, outDir: File): File {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 1280) sample *= 2
        val raw = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
        val rotated = rotate(raw, src)
        val scale = 1280f / maxOf(rotated.width, rotated.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true) else rotated
        val out = File(outDir, "${UUID.randomUUID()}.webp")
        out.outputStream().use {
            @Suppress("DEPRECATION")
            val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            bmp.compress(fmt, 75, it)
        }
        src.delete() // no gallery copies (design 12.3)
        return out
    }

    private fun rotate(b: Bitmap, f: File): Bitmap {
        val deg = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
    }
}

/** Audio: Opus, 16 kHz mono (design 7.2). Exposes amplitude for the live waveform. */
class AudioRecorder(private val context: Context) {
    private var rec: MediaRecorder? = null
    var file: File? = null
        private set

    fun start(): File {
        val f = File(mediaDir(context), "${UUID.randomUUID()}.ogg")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        r.setAudioSamplingRate(16_000)
        r.setAudioChannels(1)
        r.setAudioEncodingBitRate(24_000)
        r.setOutputFile(f.path)
        r.prepare()
        r.start()
        rec = r
        file = f
        return f
    }

    /** 0..1 loudness since the last call. */
    fun level(): Float = (rec?.maxAmplitude ?: 0) / 32767f

    fun stop(): File? {
        runCatching { rec?.stop() }
        rec?.release()
        rec = null
        return file
    }
}

fun mediaDir(context: Context): File = File(context.filesDir, "capture").apply { mkdirs() }

/** Hindi voice prompts on every step (design 1.3: Hindi and voice first). */
class Speaker(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale("hi", "IN")
    }

    fun say(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
    }

    fun shutdown() = tts.shutdown()
}
