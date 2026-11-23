package gov.up.swasthbal4d.ui.screens.screening

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gov.up.swasthbal4d.data.net.WorkerResult
import gov.up.swasthbal4d.data.net.userMessage
import gov.up.swasthbal4d.rules.Colors
import gov.up.swasthbal4d.ui.components.DomainDot
import gov.up.swasthbal4d.ui.components.ErrorText
import gov.up.swasthbal4d.ui.components.Label
import gov.up.swasthbal4d.ui.components.OutlineButton
import gov.up.swasthbal4d.ui.components.PrimaryButton
import gov.up.swasthbal4d.ui.components.SbCard
import gov.up.swasthbal4d.ui.components.ScreenScaffold
import gov.up.swasthbal4d.ui.components.VoicePrompt
import gov.up.swasthbal4d.ui.components.YesNoUnsure
import gov.up.swasthbal4d.ui.components.rememberSpeechToText
import gov.up.swasthbal4d.ui.theme.SB
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

/** Runs the guided steps of one D. D1 open, D2-D4 unlock in order; autosave + resume via stepIndex. */
@Composable
fun DomainScreen(vm: ScreeningViewModel, d: String, onBack: () -> Unit, onDomainDone: (next: String?) -> Unit) {
    val draft by vm.draft.collectAsState()
    val v = draft ?: return
    val rules = vm.ruleset()
    if (rules == null) { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val steps = remember(d, v.ageMonths) { buildSteps(rules, d, v.ageMonths) }
    var idx by remember(d) { mutableIntStateOf(vm.stepIndex[d] ?: 0) }
    fun go(i: Int) { idx = i; vm.stepIndex[d] = i }
    val title = "$d · ${rules.domainNameHi(d)}"
    val captureCount = steps.count { it is Step.Capture }
    val step = steps[idx.coerceIn(0, steps.lastIndex)]
    val counter = when (step) {
        is Step.Capture -> "फोटो ${steps.take(idx + 1).count { it is Step.Capture }} / $captureCount"
        else -> "काम ${idx + 1} / ${steps.size}"
    }
    val frame = StepFrame(title, counter) { if (idx > 0) go(idx - 1) else onBack() }
    val next = { if (idx < steps.lastIndex) go(idx + 1) }
    val skipAll: (String) -> Unit = { reason ->
        vm.skipDomain(d, reason)
        onDomainDone(v.order.getOrNull(v.order.indexOf(d) + 1))
    }
    val skipOne: (String) -> Unit = { reason ->
        vm.updateDomain(d) { it.copy(comment = listOfNotNull(it.comment, "छोड़ा: ${step.key} - $reason").joinToString("; "),
            tasks = it.tasks + ("skipped_${step.key}" to true)) }
        next()
    }

    when (step) {
        is Step.Capture -> CaptureStep(vm, d, step, frame, next, skipOne)
        is Step.Device -> DeviceStep(vm, d, step, frame, next, skipOne)
        Step.NameCall -> NameCallTask(vm, frame, next, skipOne)
        Step.PictureNaming -> PictureNamingTask(vm, frame, next)
        Step.StarFollow -> StarFollowTask(vm, frame, next)
        Step.EyeChart -> EyeChartTask(vm, frame, next)
        Step.ToneTest -> ToneTestTask(vm, frame, next)
        Step.Questions -> QuestionsStep(vm, d, frame, onSkipDomain = skipAll) {
            vm.finishDomain(d)
            onDomainDone(v.order.getOrNull(v.order.indexOf(d) + 1))
        }
    }
}

/** Screen 8: questions read aloud with yes / no / unsure; worker comment by typing or voice; "I am worried" on every D. */
@Composable
private fun QuestionsStep(vm: ScreeningViewModel, d: String, frame: StepFrame, onSkipDomain: (String) -> Unit, onDone: () -> Unit) {
    val v = vm.draft.collectAsState().value ?: return
    val dd = v.domains[d] ?: return
    val qs = vm.ruleset()?.questions(d, v.ageMonths).orEmpty()
    var typing by remember { mutableStateOf(false) }
    var skipAsk by remember { mutableStateOf(false) }
    val speak = rememberSpeechToText { text -> vm.updateDomain(d) { it.copy(comment = listOfNotNull(it.comment, text).joinToString(" ")) } }
    val allAnswered = qs.all { dd.answers.containsKey(it["id"]!!.jsonPrimitive.content) }
    LaunchedEffect(d) { qs.firstOrNull()?.let { vm.speaker.say(it["text_hi"]!!.jsonPrimitive.content) } }

    ScreenScaffold(title = frame.title, subtitle = "सवाल + टिप्पणी (वैकल्पिक · लिखकर या बोलकर)", onBack = frame.onBack,
        trailing = { Text(frame.counter, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
        bottomBar = {
            PrimaryButton("$d पूरा करें ›", color = SB.Green, enabled = allAnswered, onClick = onDone)
            OutlineButton("पूरा $d कारण बताकर छोड़ें", Modifier.fillMaxWidth(), color = SB.Muted) { skipAsk = true }
        }) {
        qs.forEach { q ->
            val id = q["id"]!!.jsonPrimitive.content
            val text = q["text_hi"]!!.jsonPrimitive.content
            SbCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.VolumeUp, "सुनें", tint = SB.Primary, modifier = Modifier.clickable { vm.speaker.say(text) })
                }
                Spacer(Modifier.height(8.dp))
                YesNoUnsure(dd.answers[id]) { a -> vm.updateDomain(d) { it.copy(answers = it.answers + (id to a)) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("✎ लिखें", Modifier.weight(1f), icon = Icons.Filled.Edit) { typing = !typing }
            PrimaryButton("बोलें", Modifier.weight(1f), color = SB.Red, icon = Icons.Filled.Mic, onClick = speak)
        }
        if (typing || dd.comment != null) {
            OutlinedTextField(dd.comment ?: "", { t -> vm.updateDomain(d) { it.copy(comment = t.ifBlank { null }) } },
                Modifier.fillMaxWidth(), label = { Text("टिप्पणी (डॉक्टर सारांश में जाएगी)") })
        }
        SbCard(border = if (dd.worried) SB.Red else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("मुझे चिंता है", fontWeight = FontWeight.Bold, color = SB.Red)
                    Text("नतीजा कम से कम पीला · डॉक्टर को नोट दिखेगा", fontSize = 11.sp, color = SB.Muted)
                }
                Switch(dd.worried, { on -> vm.updateDomain(d) { it.copy(worried = on) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = SB.Red))
            }
        }
        SbCard {
            Label("$d में लिया गया")
            val photos = dd.captured.size
            Text(if (dd.captured.isEmpty()) "कोई फ़ोटो / वीडियो नहीं" else dd.captured.joinToString(" · ") { CAPTURE_LABEL[it] ?: it } + " — $photos",
                fontSize = 13.sp)
            if (dd.readings.isNotEmpty()) Text(dd.readings.entries.joinToString(" · ") { "${it.key}: ${it.value}" }, fontSize = 13.sp)
        }
    }
    if (skipAsk) SkipDialog(onDismiss = { skipAsk = false }) { r -> skipAsk = false; onSkipDomain(r) }
}

/** Screen 11: all four Ds ticked; "I am worried" raises to at least yellow and sends a note to the doctor. */
@Composable
fun BeforeSubmitScreen(vm: ScreeningViewModel, onBack: () -> Unit, onOpenDomain: (String) -> Unit, onResult: (WorkerResult) -> Unit) {
    val v = vm.draft.collectAsState().value ?: return
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var typing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val speak = rememberSpeechToText { t -> vm.update { it.copy(worried = true, worriedReason = listOfNotNull(it.worriedReason, t).joinToString(" ")) } }
    val allDone = v.order.all { v.domains[it]?.done == true }

    ScreenScaffold(title = "जाँच पूरी · सबमिट से पहले", subtitle = "${v.card.child.name} · ${v.card.child.ageHi}", onBack = onBack,
        bottomBar = {
            PrimaryButton(if (busy) "भेज रहे हैं…" else "सबमिट करें", color = SB.Green, enabled = allDone && !busy) {
                busy = true; err = null
                scope.launch {
                    try { onResult(vm.submit()) } catch (e: Exception) { err = e.userMessage() }
                    busy = false
                }
            }
        }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            v.order.forEach { d ->
                val done = v.domains[d]?.done == true
                Text("$d ${if (done) "✓" else "…"}", fontWeight = FontWeight.Bold, color = if (done) SB.Green else SB.Amber,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (done) SB.GreenBg else SB.AmberBg)
                        .clickable { onOpenDomain(d) }.padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Text("क्या आपको इस बच्चे के बारे में कोई चिंता है?", fontSize = 14.sp)
        PrimaryButton(if (v.worried) "मुझे चिंता है ✓" else "मुझे चिंता है", color = SB.Red) { vm.update { it.copy(worried = !it.worried) } }
        if (v.worried) SbCard {
            Text("कारण: " + (v.worriedReason ?: "—"), fontWeight = FontWeight.Bold)
            Text("नतीजा कम से कम पीला · डॉक्टर को नोट दिखेगा", fontSize = 12.sp, color = SB.Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("✎ टिप्पणी", Modifier.weight(1f)) { typing = !typing }
            OutlineButton("🎤 आवाज़ नोट", Modifier.weight(1f), onClick = speak)
        }
        if (typing) OutlinedTextField(v.worriedReason ?: "", { t -> vm.update { it.copy(worriedReason = t.ifBlank { null }) } },
            Modifier.fillMaxWidth(), label = { Text("चिंता का कारण / टिप्पणी") })
        if (!allDone) Text("सभी D पूरे करें (छोड़ने के लिए कारण दें)", color = SB.Amber, fontSize = 12.sp)
        ErrorText(err)
    }
}

/** Screen 12: colour per D with a plain reason, then the final result (worst D decides); spoken in Hindi. */
@Composable
fun ResultScreen(vm: ScreeningViewModel, onNextChild: () -> Unit) {
    val r = vm.result ?: return
    val name = vm.draft.collectAsState().value?.card?.child?.name?.split(" ")?.first() ?: ""
    val final = r.final ?: Colors.INCOMPLETE
    LaunchedEffect(r.visitId) { r.tellParentHi?.let { vm.speaker.say("$name का नतीजा: ${r.finalLabelHi}। $it") } }

    ScreenScaffold(title = "नतीजा · $name",
        subtitle = if (r.isFinal) "अंतिम नतीजा ✓ · India AI से" else "अस्थायी नतीजा · अंतिम नतीजा सिंक के बाद",
        headerColor = SB.of(final),
        bottomBar = { PrimaryButton("अगला बच्चा ›", onClick = onNextChild) }) {
        r.domains.forEach { dv ->
            SbCard(padding = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DomainDot(dv.domain, dv.color)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(dv.nameHi, fontSize = 12.sp, color = SB.Muted)
                        Text(dv.labelHi, fontWeight = FontWeight.Bold, color = SB.of(dv.color))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SB.of(final)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("अंतिम नतीजा · Final result", color = Color.White.copy(alpha = .85f), fontSize = 11.sp)
                Text(r.finalLabelHi ?: "", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Text("worst D\ndecides", color = Color.White.copy(alpha = .8f), fontSize = 10.sp)
        }
        r.tellParentHi?.let { VoicePrompt(it, "Spoken to the parent in Hindi") { vm.speaker.say(it) } }
        r.actions.forEach { a -> Text("✓ ${a.textHi}", color = SB.Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        if (!r.isFinal) Text("नेटवर्क आने पर अंतिम नतीजा आपको (सूचना) और माता-पिता को (WhatsApp) भेजा जाएगा।", fontSize = 12.sp, color = SB.Amber)
    }
}
