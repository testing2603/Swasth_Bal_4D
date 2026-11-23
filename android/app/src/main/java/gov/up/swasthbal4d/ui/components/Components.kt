package gov.up.swasthbal4d.ui.components

import android.content.Intent
import android.graphics.Bitmap
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import gov.up.swasthbal4d.ui.theme.SB

/** Dark header + content + optional bottom action bar - the layout used by every screen in the frames. */
@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    headerColor: Color = SB.Primary,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    headerExtra: @Composable ColumnScope.() -> Unit = {},
    scroll: Boolean = true,
    bottomBar: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(SB.Page).imePadding()) {
        Column(Modifier.fillMaxWidth().background(headerColor).statusBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "वापस", tint = Color.White)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }
                trailing()
            }
            headerExtra()
        }
        val body = Modifier.weight(1f).fillMaxWidth().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }
        Column(body.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = bottomBar)
    }
}

@Composable
fun SbCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    border: Color? = null,
    onClick: (() -> Unit)? = null,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    var m = modifier.fillMaxWidth().clip(shape).background(SB.Card)
    if (border != null) m = m.border(1.5.dp, border, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(m.height(IntrinsicSize.Min)) {
        if (accent != null) Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.weight(1f).padding(padding), content = content)
    }
}

@Composable
fun Badge(text: String, fg: Color, bg: Color) {
    Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp))
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = SB.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = color)) {
        if (icon != null) { Icon(icon, null); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun OutlineButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = SB.Primary,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(48.dp), shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.5.dp, color), contentPadding = PaddingValues(horizontal = 10.dp)) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

/** Sync state is always visible (design screen 1). */
@Composable
fun SyncPill(online: Boolean, pending: Int) {
    val (icon, label) = when {
        !online -> Icons.Filled.CloudOff to "ऑफ़लाइन" + if (pending > 0) " · $pending" else ""
        pending > 0 -> Icons.Filled.CloudUpload to "भेज रहे · $pending"
        else -> Icons.Filled.CloudDone to "ऑनलाइन"
    }
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (online) Color(0xFF7CE38B) else Color(0xFFFFB74D)))
        Spacer(Modifier.width(5.dp))
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Domain circle used on the result screen ("D1" in a coloured disc). */
@Composable
fun DomainDot(domain: String, color: String?, size: Dp = 34.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(SB.of(color)), contentAlignment = Alignment.Center) {
        Text(domain, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun YesNoUnsure(selected: String?, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("yes" to "हाँ", "no" to "नहीं", "unsure" to "पता नहीं").forEach { (k, label) ->
            val sel = selected == k
            Text(label, color = if (sel) Color.White else SB.Primary, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) SB.Primary else Color.White)
                    .border(1.5.dp, SB.Primary, RoundedCornerShape(10.dp))
                    .clickable { onSelect(k) }.padding(vertical = 12.dp))
        }
    }
}

/** Voice prompt card with the speaker bubble (screens 5, 7, 9, 10, 12). Tapping replays the Hindi voice. */
@Composable
fun VoicePrompt(hi: String, en: String? = null, onSpeak: () -> Unit) {
    SbCard(border = SB.Primary, onClick = onSpeak) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(SB.Primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.GraphicEq, "सुनें", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(hi, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = SB.Text)
                if (en != null) Text(en, fontSize = 11.sp, fontStyle = FontStyle.Italic, color = SB.Muted)
            }
        }
    }
}

@Composable
fun Waveform(levels: List<Float>, color: Color = SB.Primary, modifier: Modifier = Modifier.fillMaxWidth().height(44.dp)) {
    Canvas(modifier) {
        val n = 48
        val w = size.width / n
        val pts = if (levels.isEmpty()) List(n) { 0.05f } else List(n) { i -> levels.getOrElse(levels.size - n + i) { 0.05f } }
        pts.forEachIndexed { i, v ->
            val h = (0.12f + v.coerceIn(0f, 1f) * 0.88f) * size.height
            drawRoundRect(color, Offset(i * w + w * 0.2f, (size.height - h) / 2), Size(w * 0.6f, h), CornerRadius(w / 2))
        }
    }
}

@Composable
fun Label(text: String, color: Color = SB.Muted) {
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun QrImage(content: String, size: Dp = 160.dp) {
    val bmp = remember(content) {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 400, 400)
        Bitmap.createBitmap(400, 400, Bitmap.Config.RGB_565).apply {
            for (x in 0 until 400) for (y in 0 until 400) setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }.asImageBitmap()
    }
    Image(bmp, "QR $content", Modifier.size(size))
}

/** Speech-to-text in Hindi (online or on-device recogniser). Returns a launcher function. */
@Composable
fun rememberSpeechToText(onText: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onText)
    }
    return {
        launcher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "बोलिए…")
        })
    }
}

@Composable
fun ErrorText(msg: String?) {
    if (msg != null) Text(msg, color = SB.Red, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
}
