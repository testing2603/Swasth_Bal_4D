package gov.up.swasthbal4d.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import gov.up.swasthbal4d.rules.Colors

/* Palette sampled from the design frames (Swasth Bal 4D demo). */
object SB {
    val Primary = Color(0xFF0E5A78)      // header teal-blue
    val PrimaryDark = Color(0xFF0A4660)
    val Navy = Color(0xFF14202E)         // QR scan button, camera background
    val Page = Color(0xFFF4F7FA)
    val Card = Color.White
    val Line = Color(0xFFE2E8EE)
    val Text = Color(0xFF1B2733)
    val Muted = Color(0xFF6B7785)
    val Green = Color(0xFF2E7D32)
    val GreenBg = Color(0xFFE7F3E8)
    val Amber = Color(0xFFB87A21)
    val AmberBg = Color(0xFFFBF0DC)
    val Red = Color(0xFFC0392B)
    val RedBg = Color(0xFFFBE5E2)
    val Blue = Color(0xFF2F6FDE)         // facility module accent
    val BlueBg = Color(0xFFE6EEFC)
    val Purple = Color(0xFF6A4FB6)       // decline actions
    val WaGreen = Color(0xFF075E54)

    fun of(color: String?): Color = when (color) {
        Colors.GREEN -> Green
        Colors.YELLOW -> Amber
        Colors.RED -> Red
        else -> Muted
    }

    fun bgOf(color: String?): Color = when (color) {
        Colors.GREEN -> GreenBg
        Colors.YELLOW -> AmberBg
        Colors.RED -> RedBg
        else -> Line
    }
}

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
    bodySmall = TextStyle(fontSize = 12.sp),
)

@Composable
fun SwasthBalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = SB.Primary, onPrimary = Color.White, secondary = SB.Green, error = SB.Red,
            background = SB.Page, surface = SB.Card, onSurface = SB.Text,
        ),
        typography = typography,
        content = content,
    )
}
