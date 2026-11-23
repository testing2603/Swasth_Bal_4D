package gov.up.swasthbal4d.ui.screens.department

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gov.up.swasthbal4d.data.Repository
import gov.up.swasthbal4d.data.net.AskIn
import gov.up.swasthbal4d.data.net.DashboardOut
import gov.up.swasthbal4d.data.net.userMessage
import gov.up.swasthbal4d.ui.components.Badge
import gov.up.swasthbal4d.ui.components.ErrorText
import gov.up.swasthbal4d.ui.components.Label
import gov.up.swasthbal4d.ui.components.SbCard
import gov.up.swasthbal4d.ui.components.ScreenScaffold
import gov.up.swasthbal4d.ui.theme.SB
import kotlinx.coroutines.launch

/** Screen 21: KPI tiles, coverage by block, referral funnel, timings, AI-doctor agreement, Hindi questions. Anonymous. */
@Composable
fun DashboardScreen(repo: Repository, onLogout: () -> Unit) {
    var data by remember { mutableStateOf<DashboardOut?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("किस ब्लॉक में सबसे ज़्यादा बच्चे छूटे?") }
    var answer by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { try { data = repo.client.api.dashboard() } catch (e: Exception) { err = e.userMessage() } }

    ScreenScaffold(title = "डैशबोर्ड", subtitle = repo.session.user?.subtitleHi, headerColor = SB.Navy,
        trailing = { Text("लॉगआउट", color = Color.White, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onLogout)) }) {
        ErrorText(err)
        val d = data ?: return@ScreenScaffold
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Badge(d.month, SB.Primary, SB.BlueBg); Badge("Anonymous view", SB.Muted, SB.Line) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("Children screened", "%,d".format(d.kpis.childrenScreened), SB.Text, Modifier.weight(1f))
            Kpi("Coverage of due", "${d.kpis.coveragePct}%", SB.Primary, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("Referrals reached", "${d.kpis.referralsReachedPct}%", SB.Green, Modifier.weight(1f))
            Kpi("Yellow improving", "${d.kpis.yellowImprovingPct}%", SB.Amber, Modifier.weight(1f))
        }
        SbCard {
            Text("Coverage by block", fontWeight = FontWeight.Bold)
            d.coverageByBlock.forEach { b -> Bar(b.blockHi ?: b.block, b.coveragePct, b.coveragePct?.let { "$it%" } ?: "<5") }
        }
        SbCard {
            Text("Referral funnel", fontWeight = FontWeight.Bold)
            val max = maxOf(1, d.funnel.referred)
            Bar("Referred", 100 * d.funnel.referred / max, "${d.funnel.referred}", SB.Primary)
            Bar("Reached facility", 100 * d.funnel.reached / max, "${d.funnel.reached}", SB.Blue)
            Bar("Under treatment", 100 * d.funnel.underTreatment / max, "${d.funnel.underTreatment}", SB.Green)
            Bar("Overdue > 7 days", 100 * d.funnel.overdueWeek / max, "${d.funnel.overdueWeek}", SB.Red)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("Referral → arrival", d.timingDays.referralToArrival?.let { "$it days" } ?: "–", SB.Text, Modifier.weight(1f))
            Kpi("Arrival → outcome", d.timingDays.arrivalToOutcome?.let { "$it days" } ?: "–", SB.Text, Modifier.weight(1f))
        }
        Kpi("AI–doctor agreement", d.aiDoctorAgreementPct?.let { "$it%" } ?: "–", SB.Text, Modifier.fillMaxWidth())
        SbCard {
            Label("Flagged per D")
            Text(d.flaggedPerDPct.entries.joinToString(" · ") { "${it.key} ${it.value}%" }, fontSize = 13.sp)
        }
        SbCard {
            Label("हिंदी / English में पूछें")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f))
                IconButton(onClick = { scope.launch { answer = runCatching { repo.client.api.ask(AskIn(q)).answer }.getOrElse { it.userMessage() } } }) {
                    Icon(Icons.AutoMirrored.Filled.Send, "पूछें", tint = SB.Primary)
                }
            }
            answer?.let { Text(it, fontWeight = FontWeight.SemiBold, color = SB.Primary) }
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, color: Color, modifier: Modifier) {
    SbCard(modifier, padding = 12.dp) {
        Text(label, fontSize = 12.sp, color = SB.Muted)
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun Bar(label: String, pct: Int?, value: String, color: Color = SB.Primary) {
    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(SB.Line)) {
            Box(Modifier.fillMaxWidth(((pct ?: 0) / 100f).coerceIn(0f, 1f)).fillMaxHeight().background(color))
        }
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
