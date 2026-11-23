package gov.up.swasthbal4d.ui.screens.screening

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gov.up.swasthbal4d.media.AV_PERMS
import gov.up.swasthbal4d.media.CAMERA_PERMS
import gov.up.swasthbal4d.media.CameraPreview
import gov.up.swasthbal4d.media.LiveChecks
import gov.up.swasthbal4d.media.WithPermissions
import gov.up.swasthbal4d.media.rememberCamera
import gov.up.swasthbal4d.ui.components.Badge
import gov.up.swasthbal4d.ui.components.OutlineButton
import gov.up.swasthbal4d.ui.components.PrimaryButton
import gov.up.swasthbal4d.ui.components.SbCard
import gov.up.swasthbal4d.ui.components.ScreenScaffold
import gov.up.swasthbal4d.ui.components.VoicePrompt
import gov.up.swasthbal4d.ui.theme.SB
import kotlinx.coroutines.delay

data class StepFrame(val title: String, val counter: String, val onBack: () -> Unit)

@Composable
fun FrameCorners(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val l = size.minDimension * 0.12f
        val sw = 4.dp.toPx()
        listOf(Offset(0f, 0f) to Offset(1f, 1f), Offset(size.width, 0f) to Offset(-1f, 1f),
            Offset(0f, size.height) to Offset(1f, -1f), Offset(size.width, size.height) to Offset(-1f, -1f)).forEach { (p, dir) ->
            drawLine(color, p, Offset(p.x + l * dir.x, p.y), sw, StrokeCap.Round)
            drawLine(color, p, Offset(p.x, p.y + l * dir.y), sw, StrokeCap.Round)
        }
    }
}

@Composable
private fun CheckChip(ok: Boolean, text: String) {
    Text((if (ok) "✓ " else "… ") + text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (ok) SB.Green else Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp))
}

/** Screen 7: camera opens by itself; frame guide with live checks; auto-capture; retake or skip with reason. */
@Composable
fun CaptureStep(vm: ScreeningViewModel, d: String, step: Step.Capture, frame: StepFrame, onNext: () -> Unit, onSkip: (String) -> Unit) {
    var front by remember { mutableStateOf(false) }
    var checks by remember { mutableStateOf(LiveChecks()) }
    var taken by remember(step.id) { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var secs by remember { mutableStateOf(0) }
    var skipAsk by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val isVideo = step.kind == "video"

    LaunchedEffect(step.id) { vm.speaker.say(step.textHi) }

    ScreenScaffold(title = frame.title, onBack = frame.onBack, scroll = false, headerColor = SB.Primary,
        trailing = { Text(frame.counter, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
        bottomBar = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("दोबारा लें", Modifier.weight(1f)) { taken = false; err = null }
                OutlineButton("कारण बताकर छोड़ें", Modifier.weight(1f)) { skipAsk = true }
                PrimaryButton("आगे ›", Modifier.weight(1f), color = SB.Green, enabled = taken, onClick = onNext)
            }
        }) {
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(SB.Navy)) {
            WithPermissions(if (isVideo) AV_PERMS else CAMERA_PERMS) {
                val cam = rememberCamera(front)
                LaunchedEffect(front, step.id) {
                    cam.flash(step.flash)
                    cam.analyzeQuality(step.needFace) { c, _ -> checks = c }
                }
                // auto-capture once all checks have held for ~1 s
                LaunchedEffect(checks.allOk, taken, step.id) {
                    if (!isVideo && checks.allOk && !taken) {
                        delay(1000)
                        if (checks.allOk && !taken) cam.takePhoto({ f -> vm.addMedia(d, "photo", step.id, f.path); taken = true; vm.speaker.say("फ़ोटो ली गई") },
                            { err = it })
                    }
                }
                LaunchedEffect(recording) {
                    if (recording) {
                        secs = 0
                        cam.startVideo { f -> vm.addMedia(d, "video", step.id, f.path); taken = true }
                        while (recording && secs < 10) { delay(1000); secs++ }
                        cam.stopVideo(); recording = false
                    }
                }
                CameraPreview(cam)
            }
            FrameCorners(Modifier.align(Alignment.Center).fillMaxWidth(0.8f).fillMaxSize(0.75f),
                if (checks.allOk || taken) Color(0xFF7CE38B) else Color.White)
            Text("● ${step.labelHi}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp))
            Text((if (front) "सामने का कैमरा" else "पीछे का कैमरा") + " 🔄", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(50)).background(Color.White)
                    .clickable { front = !front }.padding(horizontal = 8.dp, vertical = 3.dp))
            Row(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CheckChip(checks.light, "रोशनी ठीक"); CheckChip(checks.steady, "स्थिर")
                if (step.needFace) CheckChip(checks.subject, "पूरा चेहरा")
                if (taken) CheckChip(true, if (isVideo) "वीडियो लिया" else "फ़ोटो ली")
            }
        }
        VoicePrompt(step.textHi, step.textEn + if (isVideo) "" else " - it captures automatically") { vm.speaker.say(step.textHi) }
        if (isVideo && !taken) PrimaryButton(if (recording) "रिकॉर्डिंग… $secs / 10 s" else "● रिकॉर्ड शुरू (10 s)", color = SB.Red, enabled = !recording) { recording = true }
        if (!isVideo && !taken && !checks.allOk) Text("जाँच पूरी होने पर फ़ोटो अपने आप ली जाएगी", fontSize = 12.sp, color = SB.Muted)
        err?.let { Text(it, color = SB.Red, fontSize = 12.sp) }
    }
    if (skipAsk) SkipDialog(onDismiss = { skipAsk = false }) { reason -> skipAsk = false; onSkip(reason) }
}

@Composable
fun SkipDialog(onDismiss: () -> Unit, onSkip: (String) -> Unit) {
    val reasons = listOf("बच्चा रो रहा है / सहयोग नहीं", "उपकरण नहीं है", "रोशनी नहीं है", "माता-पिता ने मना किया")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("क्यों छोड़ रहे हैं?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                reasons.forEach { r -> OutlineButton(r, Modifier.fillMaxWidth()) { onSkip(r) } }
                Text("छोड़ी गई जाँच कभी “ठीक” नहीं मानी जाती - दोबारा लेने का काम बनेगा", fontSize = 11.sp, color = SB.Muted)
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("रद्द") } })
}

/** Screen 9: step-by-step device instructions, then a photo; OCR reads the value; manual entry as fallback. */
@Composable
fun DeviceStep(vm: ScreeningViewModel, d: String, step: Step.Device, frame: StepFrame, onNext: () -> Unit, onSkip: (String) -> Unit) {
    var phase by remember(step.id) { mutableStateOf(0) } // 0 instructions, 1 camera, 2 confirm value
    var value by remember(step.id) { mutableStateOf("") }
    var ocrHint by remember(step.id) { mutableStateOf<String?>(null) }
    var skipAsk by remember { mutableStateOf(false) }
    val title = "अब ${step.nameHi} लें"
    LaunchedEffect(step.id) { vm.speaker.say(title + "। " + step.stepsHi.joinToString("। ")) }

    when (phase) {
        0 -> ScreenScaffold(title = title, subtitle = "NEXT STEP · GUIDED · ${frame.title}", headerColor = SB.Navy, onBack = frame.onBack,
            trailing = { Text(frame.counter, color = Color.White, fontSize = 12.sp) },
            bottomBar = {
                PrimaryButton("तैयार · कैमरा खोलें") { phase = 1 }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton("हाथ से अंक डालें", Modifier.weight(1f)) { phase = 2 }
                    OutlineButton("कारण बताकर छोड़ें", Modifier.weight(1f)) { skipAsk = true }
                }
            }) {
            if (step.id == "muac_cm") MuacGraphic()
            step.stepsHi.forEachIndexed { i, s ->
                SbCard(border = if (i == step.stepsHi.lastIndex) SB.Primary else null, padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge("${i + 1}", Color.White, SB.Primary)
                        Spacer(Modifier.width(10.dp))
                        Text(s, fontSize = 15.sp)
                    }
                }
            }
        }
        1 -> ScreenScaffold(title = step.nameHi, subtitle = "अंक साफ़ दिखे - फ़ोटो लें", headerColor = SB.Navy, onBack = { phase = 0 }, scroll = false,
            bottomBar = {}) {
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(SB.Navy)) {
                WithPermissions(CAMERA_PERMS) { Box(Modifier.fillMaxSize()) {
                    val cam = rememberCamera()
                    CameraPreview(cam)
                    FrameCorners(Modifier.align(Alignment.Center).fillMaxWidth(0.7f).height(160.dp), Color.White)
                    PrimaryButton("📷 फ़ोटो लें", Modifier.align(Alignment.BottomCenter).padding(12.dp), color = SB.Green) {
                        cam.takePhoto({ f ->
                            vm.addMedia(d, "photo", "device_${step.id}", f.path)
                            cam.readNumbers(f) { nums ->
                                val inRange = nums.firstOrNull { it in step.min..step.max }
                                if (inRange != null) { value = inRange.toString(); ocrHint = "OCR ने पढ़ा: $inRange ${step.unit} - सही है तो पुष्टि करें" }
                                else ocrHint = "अंक नहीं पढ़ पाए - हाथ से डालें"
                                phase = 2
                            }
                        }, { ocrHint = it; phase = 2 })
                    }
                } }
            }
        }
        else -> ScreenScaffold(title = step.nameHi, subtitle = "अंक की पुष्टि करें", headerColor = SB.Navy, onBack = { phase = 0 },
            bottomBar = {
                val v = value.toDoubleOrNull()
                PrimaryButton("पुष्टि · आगे ›", color = SB.Green, enabled = v != null && v in step.min..step.max) {
                    vm.updateDomain(d) { it.copy(readings = it.readings + (step.id to v!!)) }
                    onNext()
                }
            }) {
            ocrHint?.let { Text(it, color = SB.Primary, fontSize = 13.sp) }
            OutlinedTextField(value, { value = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' }.take(6) },
                Modifier.fillMaxWidth(), singleLine = true, label = { Text("${step.nameHi} (${step.unit})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold))
            Text("सीमा: ${step.min} – ${step.max} ${step.unit}", fontSize = 12.sp, color = SB.Muted)
        }
    }
    if (skipAsk) SkipDialog(onDismiss = { skipAsk = false }) { r -> skipAsk = false; onSkip(r) }
}

@Composable
private fun MuacGraphic() {
    Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.width(220.dp).height(30.dp).clip(RoundedCornerShape(6.dp))) {
            Box(Modifier.weight(1f).fillMaxSize().background(SB.Red))
            Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFFF2C12E)))
            Box(Modifier.weight(1.4f).fillMaxSize().background(SB.Green))
        }
        Box(Modifier.size(70.dp).clip(RoundedCornerShape(50)).background(Color.Transparent)) {
            Canvas(Modifier.fillMaxSize()) { drawCircle(Color(0xFFD9B48F), style = androidx.compose.ui.graphics.drawscope.Stroke(6.dp.toPx())) }
        }
    }
    Text("लाल < 11.5 cm · पीला 11.5–12.4 · हरा ≥ 12.5", fontSize = 11.sp, color = SB.Muted, modifier = Modifier.fillMaxWidth())
}
