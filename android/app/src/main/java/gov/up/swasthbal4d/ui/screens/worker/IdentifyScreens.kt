package gov.up.swasthbal4d.ui.screens.worker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gov.up.swasthbal4d.data.Repository
import gov.up.swasthbal4d.data.net.ChildCard
import gov.up.swasthbal4d.data.net.ChildIn
import gov.up.swasthbal4d.data.net.SearchHit
import gov.up.swasthbal4d.data.net.userMessage
import gov.up.swasthbal4d.media.CAMERA_PERMS
import gov.up.swasthbal4d.media.CameraPreview
import gov.up.swasthbal4d.media.WithPermissions
import gov.up.swasthbal4d.media.rememberCamera
import gov.up.swasthbal4d.ui.components.ErrorText
import gov.up.swasthbal4d.ui.components.Label
import gov.up.swasthbal4d.ui.components.OutlineButton
import gov.up.swasthbal4d.ui.components.PrimaryButton
import gov.up.swasthbal4d.ui.components.QrImage
import gov.up.swasthbal4d.ui.components.SbCard
import gov.up.swasthbal4d.ui.components.ScreenScaffold
import gov.up.swasthbal4d.ui.theme.SB
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** Full-screen QR scanner (child QR, RBSK 2.0 card, referral QR at the facility). */
@Composable
fun QrScanScreen(title: String, onBack: () -> Unit, onCode: (String) -> Unit) {
    var handled by remember { mutableStateOf(false) }
    ScreenScaffold(title = title, subtitle = "QR को फ़्रेम में रखें", headerColor = SB.Navy, onBack = onBack, scroll = false) {
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(SB.Navy)) {
            WithPermissions(CAMERA_PERMS) {
                val cam = rememberCamera()
                LaunchedEffect(Unit) { cam.scanQr { code -> if (!handled) { handled = true; onCode(code) } } }
                CameraPreview(cam)
            }
            Box(Modifier.align(Alignment.Center).size(220.dp).border(3.dp, Color(0xFF7CE38B), RoundedCornerShape(16.dp)))
        }
    }
}

/** Screen 4: identification ladder - QR -> RBSK card / any ID -> search -> wider mobile search -> register. */
@Composable
fun FindChildScreen(repo: Repository, onBack: () -> Unit, onScan: () -> Unit, onFound: (Int) -> Unit, onRegister: () -> Unit) {
    var idText by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var wideTried by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ScreenScaffold(title = "बच्चा पहचानें", subtitle = "QR → RBSK कार्ड / ID → खोज → नया पंजीकरण", onBack = onBack) {
        PrimaryButton("① QR स्कैन करें", color = SB.Navy, icon = Icons.Filled.QrCodeScanner, onClick = onScan)
        SbCard {
            Label("② RBSK 2.0 कार्ड / ABHA / RCH ID")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(idText, { idText = it.trim() }, Modifier.weight(1f), singleLine = true)
                Spacer(Modifier.width(8.dp))
                OutlineButton("खोजें") {
                    scope.launch {
                        err = null
                        try { onFound(repo.byQr(idText).child.id) } catch (e: Exception) { err = e.userMessage() }
                    }
                }
            }
        }
        SbCard {
            Label("③ नाम / माँ / पिता का नाम, या ④ मोबाइल")
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("जैसे: अमन, रीना देवी") })
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(mobile, { mobile = it.filter(Char::isDigit).take(10) }, Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), placeholder = { Text("माता-पिता का मोबाइल") })
            Spacer(Modifier.height(8.dp))
            PrimaryButton("खोजें", icon = Icons.Filled.Search, enabled = name.isNotBlank() || mobile.length == 10) {
                scope.launch {
                    err = null
                    try {
                        val wide = name.isBlank() && mobile.length == 10
                        hits = repo.search(name.ifBlank { null }, mobile.ifBlank { null }, wide)
                        wideTried = wide
                        if (hits.isEmpty()) err = "कोई बच्चा नहीं मिला" + if (!wide && mobile.length == 10) " - सिर्फ़ मोबाइल से पूरे ज़िले में खोजें" else ""
                    } catch (e: Exception) { err = e.userMessage() }
                }
            }
            ErrorText(err)
        }
        hits.forEach { h ->
            SbCard(onClick = { onFound(h.id) }, accent = SB.Primary) {
                Text(h.name, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(h.ageHi, h.motherName?.let { "माँ: $it" }, h.village).joinToString(" · "), fontSize = 12.sp, color = SB.Muted)
            }
        }
        OutlineButton("⑤ नया बच्चा पंजीकृत करें", Modifier.fillMaxWidth(), color = SB.Green, icon = Icons.Filled.PersonAdd, onClick = onRegister)
        if (wideTried) Text("दूसरे क्षेत्र के बच्चे की सिर्फ़ न्यूनतम पहचान दिखती है", fontSize = 11.sp, color = SB.Muted)
    }
}

/** Screen 4 (match): minimum identity + last screening + today's plan. */
@Composable
fun ChildFoundScreen(repo: Repository, childId: Int?, qr: String?, onBack: () -> Unit, onStart: (ChildCard) -> Unit) {
    var card by remember { mutableStateOf<ChildCard?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(childId, qr) {
        try { card = if (qr != null) repo.byQr(qr) else repo.card(childId!!) } catch (e: Exception) { err = e.userMessage() }
    }
    val c = card
    ScreenScaffold(title = if (c != null) "बच्चा मिल गया ✓" else "खोज रहे हैं…", headerColor = if (c != null) SB.Green else SB.Primary,
        onBack = onBack,
        bottomBar = { if (c != null) PrimaryButton("स्क्रीनिंग शुरू करें") { onStart(c) } }) {
        ErrorText(err)
        if (c != null) {
            SbCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(SB.AmberBg), contentAlignment = Alignment.Center) {
                        Text(if (c.child.gender == "F") "👧" else "👦", fontSize = 30.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(c.child.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("${c.child.ageHi} · ${if (c.child.gender == "F") "लड़की" else "लड़का"}", fontSize = 13.sp, color = SB.Muted)
                        Text(listOfNotNull(c.child.motherName, c.child.fatherName).joinToString(", ").let { if (it.isBlank()) "" else "माता-पिता: $it" } +
                            (c.child.rbskId?.let { " · RBSK ID ✓" } ?: ""), fontSize = 12.sp, color = SB.Muted)
                    }
                }
            }
            SbCard {
                Text(c.lastScreeningHi, fontSize = 14.sp, color = SB.Text)
                Text(c.todayPlanHi, fontSize = 14.sp, color = SB.Text)
            }
            SbCard {
                Label("बच्चे का QR (माता-पिता के WhatsApp पर भी भेजा गया)")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrImage(c.child.qrCode, 120.dp) }
            }
        }
    }
}

/** ⑤ Register a new child with a duplicate check; QR goes to the parent's WhatsApp on save. */
@Composable
fun RegisterChildScreen(repo: Repository, onBack: () -> Unit, onDone: (Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("M") }
    var dob by remember { mutableStateOf("") }
    var mother by remember { mutableStateOf("") }
    var father by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var dupWarned by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val valid = name.isNotBlank() && Regex("""\d{4}-\d{2}-\d{2}""").matches(dob) && (mobile.isEmpty() || mobile.length == 10)

    ScreenScaffold(title = "नया बच्चा पंजीकृत करें", subtitle = "डुप्लिकेट जाँच के साथ", onBack = onBack,
        bottomBar = {
            PrimaryButton(if (dupWarned) "फिर भी नया पंजीकरण करें" else "सेव करें", color = SB.Green, enabled = valid) {
                scope.launch {
                    err = null
                    try {
                        val card = repo.register(ChildIn(name.trim(), gender, dob, mother.ifBlank { null }, father.ifBlank { null },
                            mobile.ifBlank { null }, force = dupWarned))
                        onDone(card.child.id)
                    } catch (e: HttpException) {
                        if (e.code() == 409) { dupWarned = true; err = "मिलते-जुलते बच्चे मिले - पहले खोज में देखें" } else err = e.userMessage()
                    } catch (e: Exception) { err = e.userMessage() }
                }
            }
        }) {
        SbCard {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("बच्चे का नाम") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                listOf("M" to "लड़का", "F" to "लड़की", "O" to "अन्य").forEach { (k, l) ->
                    FilterChip(selected = gender == k, onClick = { gender = k }, label = { Text(l) })
                }
            }
            OutlinedTextField(dob, { dob = it.take(10) }, Modifier.fillMaxWidth(), label = { Text("जन्म तिथि (YYYY-MM-DD)") }, singleLine = true)
            OutlinedTextField(mother, { mother = it }, Modifier.fillMaxWidth(), label = { Text("माँ का नाम") }, singleLine = true)
            OutlinedTextField(father, { father = it }, Modifier.fillMaxWidth(), label = { Text("पिता का नाम") }, singleLine = true)
            OutlinedTextField(mobile, { mobile = it.filter(Char::isDigit).take(10) }, Modifier.fillMaxWidth(),
                label = { Text("माता-पिता का WhatsApp नंबर") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            ErrorText(err)
        }
    }
}
