package gov.up.swasthbal4d.ui.screens.worker

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gov.up.swasthbal4d.data.Repository
import gov.up.swasthbal4d.data.net.AbsentOut
import gov.up.swasthbal4d.data.net.DueItemDto
import gov.up.swasthbal4d.data.net.HomeOut
import gov.up.swasthbal4d.data.net.userMessage
import gov.up.swasthbal4d.ui.components.Badge
import gov.up.swasthbal4d.ui.components.ErrorText
import gov.up.swasthbal4d.ui.components.Label
import gov.up.swasthbal4d.ui.components.PrimaryButton
import gov.up.swasthbal4d.ui.components.SbCard
import gov.up.swasthbal4d.ui.components.ScreenScaffold
import gov.up.swasthbal4d.ui.components.SyncPill
import gov.up.swasthbal4d.ui.components.rememberSpeechToText
import gov.up.swasthbal4d.ui.theme.SB
import kotlinx.coroutines.launch

fun kindColor(kind: String): Pair<Color, Color> = when (kind) {
    "missed", "overdue", "urgent" -> SB.Red to SB.RedBg
    "newborn", "retake" -> SB.Amber to SB.AmberBg
    "followup" -> SB.Blue to SB.BlueBg
    else -> SB.Primary to SB.BlueBg
}

/** Screen 1: day list built before login, priority badges, counts, sync state, map + QR. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerHomeScreen(
    repo: Repository,
    onOpenChild: (childId: Int) -> Unit,
    onScanQr: () -> Unit,
    onOffList: () -> Unit,
    onLogout: () -> Unit,
) {
    val online by repo.online.collectAsState()
    val pendingV by repo.pendingVisits.collectAsState(initial = 0)
    val pendingM by repo.pendingMedia.collectAsState(initial = 0)
    var home by remember { mutableStateOf<HomeOut?>(null) }
    var fromCache by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var absentFor by remember { mutableStateOf<DueItemDto?>(null) }
    val ctx = LocalContext.current

    LaunchedEffect(reload, online) {
        loading = true
        val (h, cached) = repo.home()
        home = h; fromCache = cached; loading = false
    }

    val w = home?.worker ?: repo.session.user
    ScreenScaffold(
        title = "नमस्ते, ${w?.name ?: ""}",
        subtitle = w?.subtitleHi,
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                SyncPill(online, pendingV + pendingM)
                Spacer(Modifier.height(6.dp))
                Text("लॉगआउट", color = Color.White.copy(alpha = .7f), fontSize = 11.sp, modifier = Modifier.clickable(onClick = onLogout))
            }
        },
        bottomBar = {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SB.GreenBg)
                .border(BorderStroke(1.5.dp, SB.Green), RoundedCornerShape(10.dp)).clickable(onClick = onOffList).padding(10.dp),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AddCircle, null, tint = SB.Green, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("सूची से बाहर का बच्चा जाँचें", color = SB.Green, fontWeight = FontWeight.Bold)
                    }
                    Text("कोई भी बच्चा · खोजें या नया जोड़ें · सूची अपने आप अपडेट", fontSize = 11.sp, color = SB.Muted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("मार्ग देखें", Modifier.weight(1f), icon = Icons.Filled.Map) {
                    val names = home?.items?.mapNotNull { it.child.awc }?.firstOrNull() ?: "Rampur"
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(names))))
                }
                PrimaryButton("QR स्कैन", Modifier.weight(1f), color = SB.Navy, icon = Icons.Filled.QrCodeScanner, onClick = onScanQr)
            }
        },
    ) {
        val c = home?.counts
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountTile("${c?.today ?: "–"}", "आज", Modifier.weight(1f))
            CountTile("${c?.followup ?: "–"}", "फॉलो-अप", Modifier.weight(1f))
            CountTile("${c?.overdue ?: "–"}", "देरी", Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("आज की सूची · प्राथमिकता से", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { reload++ }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Refresh, "ताज़ा करें", tint = SB.Primary) }
        }
        if (fromCache) Text("ऑफ़लाइन सूची (पिछला सिंक) दिखा रहे हैं", fontSize = 12.sp, color = SB.Amber)
        home?.items?.forEach { item ->
            val (fg, bg) = kindColor(item.kind)
            SbCard(accent = fg, onClick = { onOpenChild(item.child.id) }, padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.child.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            if (item.finalResultReady) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Filled.CheckCircle, "अंतिम नतीजा तैयार", tint = SB.Green, modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(item.subtitleHi, fontSize = 12.sp, color = SB.Muted)
                    }
                    Badge(item.badgeHi, fg, bg)
                    IconButton(onClick = { absentFor = item }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.MoreVert, "बच्चा नहीं मिला", tint = SB.Muted)
                    }
                }
            }
        }
        if (home?.items?.isEmpty() == true) SbCard { Text("आज की सूची पूरी हो गई ✔", color = SB.Green, fontWeight = FontWeight.Bold) }
    }

    absentFor?.let { item ->
        NotPresentSheet(repo, item, onClose = { absentFor = null; reload++ })
    }
}

@Composable
private fun CountTile(n: String, label: String, modifier: Modifier) {
    SbCard(modifier, padding = 10.dp) {
        Text(n, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(label, fontSize = 12.sp, color = SB.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

private val REASONS = listOf(
    "not_home" to "घर पर नहीं है",
    "not_at_site" to "आँगनवाड़ी / स्कूल नहीं आया",
    "unwell" to "बीमार / अस्पताल में",
    "family_away" to "परिवार गाँव से बाहर",
    "other" to "अन्य · बोलकर बताएँ",
)

/** Screens 2-3: child not present -> reason -> schedule engine rebooks, parent told by WhatsApp + SMS. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotPresentSheet(repo: Repository, item: DueItemDto, onClose: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf<AbsentOut?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    fun send(reason: String) = scope.launch {
        try { done = repo.markAbsent(item.dueItemId, reason, note) } catch (e: Exception) { err = e.userMessage() }
    }
    val speak = rememberSpeechToText { note = it; send("other") }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = Color.White) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val r = done
            if (r == null) {
                Text("बच्चा अभी नहीं मिला?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Label("${item.child.name} · ${item.child.ageHi} · कारण चुनें")
                REASONS.forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SB.Page)
                        .clickable { if (k == "other") speak() else send(k) }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = false, onClick = { if (k == "other") speak() else send(k) })
                        Text(label, modifier = Modifier.weight(1f))
                        if (k == "other") Icon(Icons.Filled.Mic, null, tint = SB.Muted)
                    }
                }
                ErrorText(err)
            } else {
                Text("✓ “${r.reasonHi}” दर्ज", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = SB.Green)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SB.BlueBg).padding(12.dp)) {
                    Text("शेड्यूल इंजन · नई तारीख", fontSize = 11.sp, color = SB.Muted)
                    Text(r.nextWhenHi, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = SB.Primary)
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SB.GreenBg).padding(12.dp)) {
                    Text("📩 माता-पिता को भेजा", fontWeight = FontWeight.Bold, color = SB.Green)
                    Text(r.parentNotified.joinToString(" · ") { "$it ✓" })
                }
                Text(if (r.escalatedToSupervisor) "3 बार अनुपस्थित → पर्यवेक्षक को सूचना भेजी गई" else "3 बार अनुपस्थित → पर्यवेक्षक को सूचना",
                    fontSize = 11.sp, color = if (r.escalatedToSupervisor) SB.Red else SB.Muted)
                PrimaryButton("ठीक है", onClick = onClose)
            }
        }
    }
}
