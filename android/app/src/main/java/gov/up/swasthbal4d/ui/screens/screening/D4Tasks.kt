package gov.up.swasthbal4d.ui.screens.screening

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gov.up.swasthbal4d.media.AV_PERMS
import gov.up.swasthbal4d.media.AudioRecorder
import gov.up.swasthbal4d.media.CAMERA_PERMS
import gov.up.swasthbal4d.media.CameraPreview
import gov.up.swasthbal4d.media.WithPermissions
import gov.up.swasthbal4d.media.rememberCamera
import gov.up.swasthbal4d.ui.components.Label
import gov.up.swasthbal4d.ui.components.OutlineButton
import gov.up.swasthbal4d.ui.components.PrimaryButton
import gov.up.swasthbal4d.ui.components.SbCard
import gov.up.swasthbal4d.ui.components.ScreenScaffold
import gov.up.swasthbal4d.ui.components.VoicePrompt
import gov.up.swasthbal4d.ui.theme.SB
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Name-call video: call the name from behind; record whether the child turns (screen 10). */
@Composable
fun NameCallTask(vm: ScreeningViewModel, frame: StepFrame, onNext: () -> Unit, onSkip: (String) -> Unit) {
    val trials = remember { mutableStateListOf<Boolean>() }
    var recording by remember { mutableStateOf(false) }
    var skipAsk by remember { mutableStateOf(false) }
    val name = vm.draft.value?.card?.child?.name?.split(" ")?.first() ?: ""
    val prompt = "बच्चे के पीछे से धीरे से \"$name\" पुकारें। देखें बच्चा मुड़ता है या नहीं।"
    LaunchedEffect(Unit) { vm.speaker.say(prompt) }

    ScreenScaffold(title = frame.title, onBack = frame.onBack, scroll = false,
        trailing = { Text(frame.counter, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
        bottomBar = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("मुड़ा ✓", Modifier.weight(1f), color = SB.Green) { if (trials.size < 3) trials.add(true) }
                OutlineButton("नहीं मुड़ा ✗", Modifier.weight(1f), color = SB.Red) { if (trials.size < 3) trials.add(false) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("कारण बताकर छोड़ें", Modifier.weight(1f)) { skipAsk = true }
                PrimaryButton("आगे ›", Modifier.weight(1f), color = SB.Green, enabled = trials.size >= 2) {
                    recording = false
                    vm.updateDomain("D4") { it.copy(tasks = it.tasks + mapOf("name_call_trials" to trials.size, "name_call_turns" to trials.count { t -> t })) }
                    onNext()
                }
            }
        }) {
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(SB.Navy)) {
            WithPermissions(AV_PERMS) {
                val cam = rememberCamera()
                LaunchedEffect(Unit) {
                    recording = true
                    cam.startVideo { f -> vm.addMedia("D4", "video", "name_call", f.path) }
                }
                DisposableEffect(Unit) { onDispose { cam.stopVideo() } }
                LaunchedEffect(recording) { if (!recording) cam.stopVideo() }
                CameraPreview(cam)
            }
            Text(if (recording) "● रिकॉर्डिंग · नाम पुकारें" else "रुका", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(50)).background(SB.Red).padding(horizontal = 8.dp, vertical = 3.dp))
            Text("प्रयास ${trials.size} / 2–3 · मुड़ा ${trials.count { it }}", color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp))
        }
        VoicePrompt(prompt, "Call the name from behind - does the child turn?") { vm.speaker.say(prompt) }
    }
    if (skipAsk) SkipDialog(onDismiss = { skipAsk = false }) { r -> skipAsk = false; onSkip(r) }
}

private val PICTURES = listOf("⚽" to "गेंद", "🐟" to "मछली", "🐕" to "कुत्ता", "🐈" to "बिल्ली", "🍌" to "केला",
    "🥭" to "आम", "🐄" to "गाय", "🚗" to "गाड़ी", "🌸" to "फूल", "🐦" to "चिड़िया")

/** Picture naming: the child names 10 pictures; audio is recorded; the worker taps each one named. */
@Composable
fun PictureNamingTask(vm: ScreeningViewModel, frame: StepFrame, onNext: () -> Unit) {
    val ctx = LocalContext.current
    val rec = remember { AudioRecorder(ctx) }
    val named = remember { mutableStateListOf<String>() }
    val age = vm.draft.value?.ageMonths ?: 24
    val expected = vm.ruleset()?.wordsExpected(age) ?: 6
    val prompt = "एक-एक चित्र दिखाएँ और पूछें - यह क्या है? बच्चा जो सही बोले उस पर टैप करें।"
    LaunchedEffect(Unit) { vm.speaker.say(prompt) }

    ScreenScaffold(title = frame.title, onBack = frame.onBack,
        trailing = { Text(frame.counter, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
        bottomBar = {
            PrimaryButton("आगे › (${named.size} शब्द)", color = SB.Green) {
                rec.stop()?.let { vm.addMedia("D4", "audio", "picture_naming", it.path) }
                vm.updateDomain("D4") { it.copy(tasks = it.tasks + ("words_named" to named.size)) }
                onNext()
            }
        }) {
        WithPermissions(listOf(android.Manifest.permission.RECORD_AUDIO)) {
            DisposableEffect(Unit) { rec.start(); onDispose { rec.stop() } }
        }
        VoicePrompt(prompt, "Child names the pictures · expected for age: $expected+") { vm.speaker.say(prompt) }
        PICTURES.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (emoji, word) ->
                    val sel = word in named
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) SB.GreenBg else Color.White)
                        .border(1.5.dp, if (sel) SB.Green else SB.Line, RoundedCornerShape(10.dp))
                        .clickable { if (sel) named.remove(word) else named.add(word) }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(emoji, fontSize = 30.sp)
                        Text(word, fontSize = 12.sp, color = if (sel) SB.Green else SB.Muted)
                    }
                }
            }
        }
        Text("बोले गए शब्द: ${named.size} · उम्र के हिसाब से अपेक्षित: $expected+", fontWeight = FontWeight.Bold,
            color = if (named.size >= expected) SB.Green else SB.Amber)
    }
}

/** Front camera; the child follows a moving star; head turn is tracked on the device (6-18 y). */
@Composable
fun StarFollowTask(vm: ScreeningViewModel, frame: StepFrame, onNext: () -> Unit) {
    var t by remember { mutableFloatStateOf(0f) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Boolean?>(null) }
    val samples = remember { mutableListOf<Pair<Float, Float>>() }
    val prompt = "सामने का कैमरा अपने आप चालू - बच्चा तारे को आँखों से देखे"
    LaunchedEffect(Unit) { vm.speaker.say(prompt) }
    LaunchedEffect(running) {
        if (running) {
            samples.clear()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 8000) { t = (System.currentTimeMillis() - start) / 1000f; delay(33) }
            running = false
            result = correlation(samples) > 0.5
        }
    }
    fun starPos() = sin(t * 2f * PI.toFloat() / 4f) // read from state each time (analyzer runs off-composition)
    val starX = starPos()

    ScreenScaffold(title = frame.title, onBack = frame.onBack, scroll = false,
        trailing = { Text(frame.counter, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
        bottomBar = {
            if (result == null) PrimaryButton(if (running) "चल रहा है…" else "शुरू करें", enabled = !running) { running = true }
            else PrimaryButton("आगे ›", color = SB.Green) {
                vm.updateDomain("D4") { it.copy(tasks = it.tasks + ("star_follow_ok" to (result == true))) }
                onNext()
            }
        }) {
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(SB.Navy)) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2 + starX * size.width * 0.38f
                val cy = size.height * 0.3f
                drawPath(star(Offset(cx, cy), 28.dp.toPx()), Color(0xFFF2C12E))
            }
            Text("gaze tracked on device · 6–18 y task", color = Color.White.copy(alpha = .6f), fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterStart).padding(8.dp))
            Box(Modifier.align(Alignment.BottomCenter).padding(10.dp).size(130.dp, 150.dp).clip(RoundedCornerShape(10.dp))) {
                WithPermissions(CAMERA_PERMS) {
                    val cam = rememberCamera(front = true)
                    LaunchedEffect(Unit) {
                        cam.analyzeQuality(needFace = true) { _, yaw -> if (running && yaw != null) samples.add(starPos() to yaw) }
                    }
                    CameraPreview(cam)
                }
            }
            result?.let {
                Text(if (it) "✓ तारे का पीछा किया" else "✗ पीछा नहीं किया", color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopCenter).padding(10.dp).clip(RoundedCornerShape(50))
                        .background(if (it) SB.Green else SB.Amber).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        VoicePrompt(prompt, "Front camera: the child follows the star (attention / vision)") { vm.speaker.say(prompt) }
    }
}

private fun star(c: Offset, r: Float) = Path().apply {
    for (i in 0 until 10) {
        val rad = if (i % 2 == 0) r else r * 0.45f
        val a = -PI / 2 + i * PI / 5
        val p = Offset(c.x + (rad * cos(a)).toFloat(), c.y + (rad * sin(a)).toFloat())
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

private fun correlation(s: List<Pair<Float, Float>>): Double {
    if (s.size < 20) return 0.0
    val mx = s.map { it.first }.average()
    val my = s.map { it.second }.average()
    var num = 0.0; var dx = 0.0; var dy = 0.0
    s.forEach { (x, y) -> num += (x - mx) * (y - my); dx += (x - mx) * (x - mx); dy += (y - my) * (y - my) }
    return if (dx == 0.0 || dy == 0.0) 0.0 else abs(num / sqrt(dx * dy))
}

/** Tumbling-E eye chart; the worker marks how many rows the child read correctly. */
@Composable
fun EyeChartTask(vm: ScreeningViewModel, frame: StepFrame, onNext: () -> Unit) {
    var rows by remember { mutableStateOf(0) }
    val sizes = listOf(64, 48, 36, 26, 18, 12)
    val dirs = remember { List(6) { List(4) { listOf(0f, 90f, 180f, 270f).random() } } }
    LaunchedEffect(Unit) { vm.speaker.say("फ़ोन को 3 मीटर दूर रखें। बच्चा हाथ से E की दिशा बताए।") }
    ScreenScaffold(title = frame.title, onBack = frame.onBack,
        trailing = { Text(frame.counter, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
        bottomBar = {
            PrimaryButton("आगे › ($rows पंक्ति)", color = SB.Green) {
                vm.updateDomain("D4") { it.copy(tasks = it.tasks + ("vision_rows" to rows)) }
                onNext()
            }
        }) {
        Label("आख़िरी सही पढ़ी पंक्ति पर टैप करें")
        sizes.forEachIndexed { i, sz ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (i < rows) SB.GreenBg else Color.White)
                .clickable { rows = i + 1 }.padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                dirs[i].forEach { deg -> Text("E", fontSize = sz.sp, fontWeight = FontWeight.Black, modifier = Modifier.rotate(deg)) }
            }
        }
    }
}

/** Tone test with headphones: 1, 2 and 4 kHz in each ear; the child raises a hand when they hear it. */
@Composable
fun ToneTestTask(vm: ScreeningViewModel, frame: StepFrame, onNext: () -> Unit) {
    val plan = remember { listOf("बायाँ" to 1000, "बायाँ" to 2000, "बायाँ" to 4000, "दायाँ" to 1000, "दायाँ" to 2000, "दायाँ" to 4000) }
    val heard = remember { mutableStateListOf<Boolean>() }
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.speaker.say("बच्चे को हेडफ़ोन पहनाएँ। आवाज़ सुनाई दे तो बच्चा हाथ उठाए।") }
    val i = heard.size
    ScreenScaffold(title = frame.title, onBack = frame.onBack,
        trailing = { Text(frame.counter, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
        bottomBar = {
            if (i < plan.size) {
                PrimaryButton(if (playing) "बज रहा है…" else "▶ ${plan[i].first} कान · ${plan[i].second} Hz", enabled = !playing) {
                    scope.launch { playing = true; playTone(plan[i].second, plan[i].first == "बायाँ"); playing = false }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton("सुना ✓", Modifier.weight(1f), color = SB.Green) { heard.add(true) }
                    OutlineButton("नहीं सुना ✗", Modifier.weight(1f), color = SB.Red) { heard.add(false) }
                }
            } else PrimaryButton("आगे ›", color = SB.Green) {
                val failL = heard.take(3).any { !it }
                val failR = heard.drop(3).any { !it }
                vm.updateDomain("D4") { it.copy(tasks = it.tasks + ("tone_fail_ears" to (if (failL) 1 else 0) + (if (failR) 1 else 0))) }
                onNext()
            }
        }) {
        plan.forEachIndexed { k, (ear, hz) ->
            SbCard(padding = 10.dp, border = if (k == i) SB.Primary else null) {
                Row {
                    Text("$ear कान · $hz Hz", modifier = Modifier.weight(1f))
                    Text(heard.getOrNull(k)?.let { if (it) "✓" else "✗" } ?: "–", fontWeight = FontWeight.Bold,
                        color = when (heard.getOrNull(k)) { true -> SB.Green; false -> SB.Red; else -> SB.Muted })
                }
            }
        }
        Box(Modifier.height(4.dp))
    }
}

private suspend fun playTone(hz: Int, left: Boolean) {
    val rate = 44_100
    val n = rate // 1 second
    val buf = ShortArray(n * 2)
    for (k in 0 until n) {
        val env = minOf(1.0, k / 2000.0, (n - k) / 2000.0)
        val v = (sin(2 * PI * hz * k / rate) * env * 0.3 * Short.MAX_VALUE).toInt().toShort()
        buf[2 * k] = if (left) v else 0
        buf[2 * k + 1] = if (left) 0 else v
    }
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setBufferSizeInBytes(buf.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
    track.write(buf, 0, buf.size)
    track.play()
    delay(1100)
    track.release()
}
