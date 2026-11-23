package gov.up.swasthbal4d.ui.screens.facility

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import gov.up.swasthbal4d.data.Repository
import gov.up.swasthbal4d.data.net.AcceptIn
import gov.up.swasthbal4d.data.net.CheckinIn
import gov.up.swasthbal4d.data.net.DeclineIn
import gov.up.swasthbal4d.data.net.InboxOut
import gov.up.swasthbal4d.data.net.MediaRef
import gov.up.swasthbal4d.data.net.OutcomeIn
import gov.up.swasthbal4d.data.net.ReferralDetail
import gov.up.swasthbal4d.data.net.userMessage
import gov.up.swasthbal4d.ui.components.Badge
import gov.up.swasthbal4d.ui.components.ErrorText
import gov.up.swasthbal4d.ui.components.Label
import gov.up.swasthbal4d.ui.components.OutlineButton
import gov.up.swasthbal4d.ui.components.PrimaryButton
import gov.up.swasthbal4d.ui.components.SbCard
import gov.up.swasthbal4d.ui.components.ScreenScaffold
import gov.up.swasthbal4d.ui.components.rememberSpeechToText
import gov.up.swasthbal4d.ui.theme.SB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private fun chipColors(chip: String, urgency: String) = when {
    urgency == "emergency" -> SB.Red to SB.RedBg
    chip == "नया" -> SB.Red to SB.RedBg
    chip == "कल" -> SB.Amber to SB.AmberBg
    chip == "पहुँचे" -> SB.Green to SB.GreenBg
    else -> SB.Blue to SB.BlueBg
}

/** Screen 17: the referral goes to the facility (shared inbox), not one doctor; urgency chips; QR check-in. */
@Composable
fun FacilityInboxScreen(repo: Repository, onOpen: (Int) -> Unit, onScan: () -> Unit, onLogout: () -> Unit) {
    var inbox by remember { mutableStateOf<InboxOut?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { try { inbox = repo.client.api.inbox(); err = null } catch (e: Exception) { err = e.userMessage() } }
    ScreenScaffold(title = "सुविधा रेफ़रल इनबॉक्स", subtitle = repo.session.user?.subtitleHi, headerColor = SB.Blue,
        trailing = {
            IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, "ताज़ा", tint = androidx.compose.ui.graphics.Color.White) }
            Text("लॉगआउट", color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onLogout))
        },
        bottomBar = { PrimaryButton("QR चेक-इन (बच्चा पहुँचा)", color = SB.Navy, icon = Icons.Filled.QrCodeScanner, onClick = onScan) }) {
        ErrorText(err)
        inbox?.items?.forEach { r ->
            val (fg, bg) = chipColors(r.chipHi, r.urgency)
            SbCard(accent = fg, onClick = { onOpen(r.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.childName} · ${r.ageHi}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(r.reasonHi + if (r.status == "REACHED") " · पहुँचे ✓" else "", fontSize = 12.sp, color = SB.Muted)
                    }
                    Badge(r.chipHi, fg, bg)
                }
            }
        }
        if (inbox?.items?.isEmpty() == true) Text("कोई खुला रेफ़रल नहीं", color = SB.Muted)
    }
}

private val DECLINE = listOf(
    "not_enough_evidence" to "पर्याप्त सबूत नहीं (Not enough evidence)",
    "not_indicated" to "जाँच में संकेत नहीं (Not indicated)",
    "wrong_facility" to "गलत सुविधा → दूसरी जगह भेजें",
    "already_treated" to "पहले से इलाज चल रहा है",
    "duplicate" to "डुप्लिकेट रेफ़रल",
)

/** Screens 18-19: AI summary for confirmation (not a diagnosis) with evidence; accept + slot, or decline with reason. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReferralDetailScreen(repo: Repository, id: Int, onBack: () -> Unit, onOutcome: (Int) -> Unit) {
    var r by remember { mutableStateOf<ReferralDetail?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var declineOpen by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id, reload) { try { r = repo.client.api.referral(id) } catch (e: Exception) { err = e.userMessage() } }
    val ref = r
    fun act(block: suspend () -> Unit) = scope.launch { try { block(); reload++ } catch (e: Exception) { err = e.userMessage() } }

    ScreenScaffold(title = ref?.let { "${it.childName} · ${it.ageEn} · ${it.gender}" } ?: "…",
        subtitle = ref?.let { "${it.referredBy} · ${it.reasonEn}" }, headerColor = SB.Blue, onBack = onBack,
        bottomBar = {
            if (ref != null) when (ref.status) {
                "NOTIFIED", "CREATED" -> {
                    Label("समय चुनकर स्वीकार करें")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ref.suggestedSlots.forEach { s ->
                            val dt = LocalDateTime.parse(s)
                            OutlineButton(dt.format(DateTimeFormatter.ofPattern("d MMM · HH:mm"))) { act { repo.client.api.accept(id, AcceptIn(s)) } }
                        }
                    }
                    PrimaryButton("कारण के साथ अस्वीकार", color = SB.Purple) { declineOpen = true }
                }
                "BOOKED" -> {
                    Text("स्वीकृत ✓ · ${ref.slot?.replace('T', ' ')?.take(16)}", color = SB.Green, fontWeight = FontWeight.Bold)
                    PrimaryButton("पहुँचे · हाथ से दर्ज करें") { act { repo.client.api.reached(id) } }
                    OutlineButton("कारण के साथ अस्वीकार", Modifier.fillMaxWidth(), color = SB.Purple) { declineOpen = true }
                }
                "REACHED", "SEEN" -> PrimaryButton("नतीजा दर्ज करें ›", color = SB.Green) { onOutcome(id) }
                else -> Text("स्थिति: ${ref.status}", color = SB.Muted)
            }
        }) {
        ErrorText(err)
        if (ref == null) return@ScreenScaffold
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge("${if (ref.urgency == "emergency") "EMERGENCY" else "RED"} · ${ref.domains.joinToString()}", SB.Red, SB.RedBg)
            Spacer(Modifier.width(8.dp))
            Badge(ref.status, SB.Blue, SB.BlueBg)
        }
        Text(ref.aiSummary.label.ifBlank { "AI SUMMARY (for confirmation, not a diagnosis)" }, color = SB.Primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        ref.aiSummary.items.forEach { item ->
            SbCard(padding = 10.dp) {
                Text(item.title, fontWeight = FontWeight.Bold)
                if (item.detail.isNotBlank()) Text(item.detail, fontSize = 12.sp, color = SB.Muted)
            }
        }
        if (ref.aiSummary.modelsAgree == true || ref.aiSummary.rulesNote != null) SbCard(padding = 10.dp) {
            if (ref.aiSummary.modelsAgree == true) Text("Both AI models agree", fontWeight = FontWeight.Bold)
            ref.aiSummary.rulesNote?.let { Text("Rules: $it", fontSize = 12.sp, color = SB.Muted) }
        }
        if (ref.media.isNotEmpty()) {
            Text("EVIDENCE", color = SB.Primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            ref.media.forEach { m -> EvidenceTile(repo, m) { f -> playing = f } }
        }
        ref.aiSummary.suggestedNext?.let { Text("Suggested next: $it", fontSize = 12.sp, color = SB.Muted) }
        Text("डेटा सिर्फ़ रेफ़रल खुला रहने तक दिखता है", fontSize = 11.sp, color = SB.Muted)
    }

    if (declineOpen) DeclineDialog(onDismiss = { declineOpen = false }) { reason, note, reroute ->
        declineOpen = false
        act { repo.client.api.decline(id, DeclineIn(reason, note, reroute)); onBack() }
    }
    playing?.let { f ->
        AlertDialog(onDismissRequest = { playing = null }, confirmButton = { TextButton(onClick = { playing = null }) { Text("बंद") } },
            text = {
                AndroidView({ c -> VideoView(c).apply { setVideoURI(Uri.fromFile(f)); setMediaController(MediaController(c)); start() } },
                    Modifier.fillMaxWidth().height(260.dp))
            })
    }
}

@Composable
private fun EvidenceTile(repo: Repository, m: MediaRef, onPlay: (File) -> Unit) {
    val ctx = LocalContext.current
    var img by remember(m.id) { mutableStateOf<ImageBitmap?>(null) }
    var file by remember(m.id) { mutableStateOf<File?>(null) }
    LaunchedEffect(m.id) {
        runCatching {
            withContext(Dispatchers.IO) {
                val bytes = repo.client.api.download(repo.client.absolute(m.url)).bytes()
                if (m.kind == "photo") img = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                else file = File(ctx.cacheDir, "ev_${m.id}.${if (m.kind == "video") "mp4" else "ogg"}").apply { writeBytes(bytes) }
            }
        }
    }
    SbCard(padding = 0.dp, onClick = { file?.let(onPlay) }) {
        Box(Modifier.fillMaxWidth().height(if (m.kind == "photo") 180.dp else 64.dp).background(SB.Navy)) {
            img?.let { Image(it, m.tag, Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Crop) }
            Text("${if (m.kind == "photo") "" else "▶ "}${m.tag} · ${m.kind} ${m.evidenceCode}",
                color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
    }
}

@Composable
private fun DeclineDialog(onDismiss: () -> Unit, onSubmit: (String, String?, String?) -> Unit) {
    var reason by remember { mutableStateOf("not_enough_evidence") }
    var note by remember { mutableStateOf("") }
    var reroute by remember { mutableStateOf("DH") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("अस्वीकार क्यों? *") },
        text = {
            Column {
                DECLINE.forEach { (k, l) ->
                    Row(Modifier.fillMaxWidth().clickable { reason = k }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(reason == k, { reason = k }); Text(l, fontSize = 14.sp)
                    }
                }
                if (reason == "wrong_facility") Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("DH", "NRC", "PHC", "NCD").forEach { t -> OutlineButton(t + if (reroute == t) " ✓" else "") { reroute = t } }
                }
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), placeholder = { Text("जैसे: हथेली की फ़ोटो धुंधली; Hb नहीं मापा") })
                Text("मामला AI समीक्षा और प्रशिक्षण सेट में जाएगा", fontSize = 11.sp, color = SB.Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(reason, note.ifBlank { null }, if (reason == "wrong_facility") reroute else null) }) {
                Text("अस्वीकार भेजें", color = SB.Purple, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("रद्द") } })
}

/** Screen 20: record outcome - one screen, ~30 s; Save closes the referral. */
@Composable
fun OutcomeScreen(repo: Repository, id: Int, onDone: () -> Unit, onBack: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var diag by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(true) }
    var findings by remember { mutableStateOf("") }
    var treatment by remember { mutableStateOf("") }
    var medicine by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var next by remember { mutableStateOf(LocalDate.now().plusWeeks(4).toString()) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val speak = rememberSpeechToText { comment = listOf(comment, it).filter(String::isNotBlank).joinToString(" ") }

    ScreenScaffold(title = "नतीजा दर्ज करें", subtitle = "REACHED ✓ → OUTCOME RECORDED → CLOSED", headerColor = SB.Blue, onBack = onBack,
        bottomBar = {
            PrimaryButton("Save & close referral ⏎", color = SB.Blue, enabled = code.isNotBlank() && diag.isNotBlank()) {
                scope.launch {
                    try {
                        repo.client.api.outcome(id, OutcomeIn(code, diag, confirmed, findings.ifBlank { null }, treatment.ifBlank { null },
                            medicine.ifBlank { null }, comment.ifBlank { null }, next.ifBlank { null }))
                        onDone()
                    } catch (e: Exception) { err = e.userMessage() }
                }
            }
        }) {
        SbCard(border = SB.Blue) {
            Label("Diagnosis (RBSK code)")
            OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("D4-HI") })
            OutlinedTextField(diag, { diag = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Hearing impairment") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (confirmed) "Confirmed ✓" else "Not confirmed", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Switch(confirmed, { confirmed = it })
            }
        }
        SbCard(border = SB.Blue) { Label("Findings"); OutlinedTextField(findings, { findings = it }, Modifier.fillMaxWidth()) }
        SbCard(border = SB.Blue) { Label("Treatment / plan"); OutlinedTextField(treatment, { treatment = it }, Modifier.fillMaxWidth()) }
        SbCard(border = SB.Blue) { Label("Medicine"); OutlinedTextField(medicine, { medicine = it }, Modifier.fillMaxWidth()) }
        SbCard(border = SB.Blue) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Doctor's comment · 🎤 voice → text"); Spacer(Modifier.weight(1f))
                TextButton(onClick = speak) { Text("🎤 बोलें") }
            }
            OutlinedTextField(comment, { comment = it }, Modifier.fillMaxWidth())
        }
        SbCard { Label("Next follow-up (YYYY-MM-DD)"); OutlinedTextField(next, { next = it.take(10) }, Modifier.fillMaxWidth(), singleLine = true) }
        ErrorText(err)
    }
}

/** QR check-in at the facility desk: the referral moves to REACHED. */
suspend fun facilityCheckin(repo: Repository, code: String): String =
    try { repo.client.api.checkin(CheckinIn(code)).let { "${it.childName} पहुँचे ✓" } } catch (e: Exception) { e.userMessage() }
