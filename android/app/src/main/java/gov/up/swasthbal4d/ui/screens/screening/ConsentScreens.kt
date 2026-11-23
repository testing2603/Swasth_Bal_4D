package gov.up.swasthbal4d.ui.screens.screening

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gov.up.swasthbal4d.data.net.userMessage
import gov.up.swasthbal4d.media.AudioRecorder
import gov.up.swasthbal4d.media.WithPermissions
import gov.up.swasthbal4d.ui.components.ErrorText
import gov.up.swasthbal4d.ui.components.Label
import gov.up.swasthbal4d.ui.components.OutlineButton
import gov.up.swasthbal4d.ui.components.PrimaryButton
import gov.up.swasthbal4d.ui.components.SbCard
import gov.up.swasthbal4d.ui.components.ScreenScaffold
import gov.up.swasthbal4d.ui.components.VoicePrompt
import gov.up.swasthbal4d.ui.components.Waveform
import gov.up.swasthbal4d.ui.components.YesNoUnsure
import gov.up.swasthbal4d.ui.components.rememberSpeechToText
import gov.up.swasthbal4d.ui.theme.SB
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CONSENT_ITEMS = listOf("photo" to "फ़ोटो", "video" to "वीडियो", "voice" to "आवाज़ रिकॉर्डिंग", "whatsapp" to "WhatsApp पर संदेश")

/** Screen 5: consent item by item; OTP or recorded voice; refusal saves a reason and records nothing. */
@Composable
fun ConsentScreen(vm: ScreeningViewModel, onBack: () -> Unit, onVoice: () -> Unit, onConsented: () -> Unit, onRefused: () -> Unit) {
    val draft by vm.draft.collectAsState()
    val d = draft ?: return
    val first = d.card.child.name.split(" ").first()
    val prompt = "हम $first की जाँच के लिए फ़ोटो, वीडियो और आवाज़ लेंगे। ये सिर्फ़ जाँच और इलाज के लिए हैं।"
    var otpDialog by remember { mutableStateOf(false) }
    var refuseDialog by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.prepareOnline(); vm.speaker.say(prompt) }

    ScreenScaffold(title = "माता-पिता की सहमति", subtitle = "${d.card.child.name} · ${d.card.child.ageHi}", onBack = onBack,
        bottomBar = {
            Text("सहमति कैसे लें?", fontSize = 12.sp, color = SB.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("OTP से", Modifier.weight(1f), icon = Icons.Filled.Dialpad) {
                    if (!d.serverKnowsVisit) err = "OTP के लिए नेटवर्क चाहिए - आवाज़ से सहमति लें" else otpDialog = true
                }
                PrimaryButton("आवाज़ से", Modifier.weight(1f), icon = Icons.Filled.Mic, onClick = onVoice)
            }
            OutlineButton("✋ सहमति नहीं · कारण दर्ज करें", Modifier.fillMaxWidth(), color = SB.Red) { refuseDialog = true }
        }) {
        VoicePrompt(prompt, "Photos, video and voice are used only for the check-up and care.") { vm.speaker.say(prompt) }
        CONSENT_ITEMS.forEach { (k, label) ->
            SbCard(padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp)
                    Switch(checked = d.consentItems[k] == true,
                        onCheckedChange = { on -> vm.update { it.copy(consentItems = it.consentItems + (k to on)) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = SB.Green))
                }
            }
        }
        Text("“ना” कहना ठीक है - उस हिस्से के बिना जाँच जारी रहेगी", fontSize = 12.sp, color = SB.Muted)
        ErrorText(err)
    }

    if (otpDialog) OtpConsentDialog(vm, onDismiss = { otpDialog = false }, onOk = { otpDialog = false; vm.consentGiven("otp", null); onConsented() })
    if (refuseDialog) RefuseDialog(vm, onDismiss = { refuseDialog = false }, onDone = onRefused)
}

@Composable
private fun OtpConsentDialog(vm: ScreeningViewModel, onDismiss: () -> Unit, onOk: () -> Unit) {
    val d = vm.draft.collectAsState().value ?: return
    var code by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try {
            val r = vm.repo.sendConsentOtp(d.id)
            info = "OTP भेजा: ${r.sentTo}" + (r.devOtp?.let { " (डेव: $it)" } ?: "")
        } catch (e: Exception) { err = e.userMessage() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("माता-पिता का OTP") },
        text = {
            androidx.compose.foundation.layout.Column {
                info?.let { Text(it, fontSize = 12.sp, color = SB.Muted) }
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                ErrorText(err)
            }
        },
        confirmButton = {
            TextButton(enabled = code.length == 6, onClick = {
                scope.launch {
                    try { vm.repo.verifyConsentOtp(d, code); onOk() } catch (e: Exception) { err = e.userMessage() }
                }
            }) { Text("पुष्टि करें") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("रद्द") } })
}

@Composable
private fun RefuseDialog(vm: ScreeningViewModel, onDismiss: () -> Unit, onDone: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val speak = rememberSpeechToText { reason = it }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("सहमति नहीं - कारण") },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(reason, { reason = it }, placeholder = { Text("जैसे: पिता घर पर नहीं हैं") })
                TextButton(onClick = speak) { Text("🎤 बोलकर बताएँ") }
                Text("कुछ भी रिकॉर्ड नहीं होगा। ANM / पर्यवेक्षक को फॉलो-अप के लिए सूचना जाएगी।", fontSize = 12.sp, color = SB.Muted)
            }
        },
        confirmButton = {
            TextButton(enabled = reason.isNotBlank(), onClick = { scope.launch { vm.refuse(reason); onDone() } }) { Text("दर्ज करें") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("रद्द") } })
}

/** Screen 6: consent by recorded voice. The recording is kept with the consent record. */
@Composable
fun VoiceConsentScreen(vm: ScreeningViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val d = vm.draft.collectAsState().value ?: return
    val first = d.card.child.name.split(" ").first()
    val phrase = "हाँ, मैं $first की जाँच के लिए सहमत हूँ।"
    val ctx = LocalContext.current
    val rec = remember { AudioRecorder(ctx) }
    val levels = remember { mutableStateListOf<Float>() }
    var seconds by remember { mutableStateOf(0) }
    var recording by remember { mutableStateOf(false) }

    ScreenScaffold(title = "आवाज़ से सहमति", subtitle = "माता-पिता बोलें · रिकॉर्ड हो रहा है", onBack = onBack,
        bottomBar = {
            PrimaryButton("आगे बढ़ें ›", color = SB.Green, enabled = seconds >= 2) {
                val f = rec.stop()
                recording = false
                vm.consentGiven("voice", phrase)
                if (f != null) vm.addMedia("CO", "audio", "consent_voice", f.path)
                onDone()
            }
        }) {
        WithPermissions(listOf(android.Manifest.permission.RECORD_AUDIO)) {
            DisposableEffect(Unit) {
                rec.start(); recording = true
                onDispose { if (recording) rec.stop() }
            }
            LaunchedEffect(recording) {
                while (recording) { levels.add(rec.level()); if (levels.size % 10 == 0) seconds++; delay(100) }
            }
            SbCard {
                Text("● रिकॉर्डिंग 00:%02d".format(seconds), color = SB.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Waveform(levels.toList(), SB.Green)
                Spacer(Modifier.height(8.dp))
                Label("माता-पिता यह बोलें:")
                Text("“$phrase”", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}

/** Four danger-sign questions before screening; any "yes" opens the emergency flow. */
@Composable
fun DangerSignsScreen(vm: ScreeningViewModel, onBack: () -> Unit, onContinue: () -> Unit) {
    val d = vm.draft.collectAsState().value ?: return
    val signs = vm.ruleset()?.dangerSigns().orEmpty()
    val ctx = LocalContext.current
    val emergency = d.dangerSigns.values.any { it }
    ScreenScaffold(title = "ख़तरे के लक्षण", subtitle = "${d.card.child.name} · जाँच से पहले 4 सवाल", onBack = onBack,
        headerColor = if (emergency) SB.Red else SB.Primary,
        bottomBar = {
            if (emergency) {
                PrimaryButton("108 पर कॉल करें", color = SB.Red, icon = Icons.Filled.Call) {
                    vm.dangerAnswered() // server sends the emergency SMS to the parent
                    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:108")))
                }
                OutlineButton("जाँच फिर भी जारी रखें", Modifier.fillMaxWidth()) { vm.dangerAnswered(); onContinue() }
            } else {
                PrimaryButton("आगे · D1 शुरू करें ›", enabled = d.dangerSigns.size == signs.size && signs.isNotEmpty()) {
                    vm.dangerAnswered(); onContinue()
                }
            }
        }) {
        if (emergency) SbCard(border = SB.Red) {
            Text("🚨 आपातकाल: बच्चे को तुरंत ज़िला अस्पताल भेजें", color = SB.Red, fontWeight = FontWeight.Bold)
            Text("एम्बुलेंस 108 / 102 · माता-पिता को SMS भेजा जाएगा", fontSize = 12.sp, color = SB.Muted)
        }
        signs.forEach { s ->
            val id = s["id"].toString().trim('"')
            val text = s["text_hi"].toString().trim('"')
            SbCard {
                Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                YesNoUnsure(d.dangerSigns[id]?.let { if (it) "yes" else "no" }) { a ->
                    // "unsure" on a danger sign is treated as yes - when unsure, refer
                    vm.update { it.copy(dangerSigns = it.dangerSigns + (id to (a != "no"))) }
                }
            }
        }
    }
}
