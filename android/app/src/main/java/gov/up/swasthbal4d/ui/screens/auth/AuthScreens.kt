package gov.up.swasthbal4d.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.sp
import gov.up.swasthbal4d.data.Repository
import gov.up.swasthbal4d.data.net.userMessage
import gov.up.swasthbal4d.ui.components.ErrorText
import gov.up.swasthbal4d.ui.components.Label
import gov.up.swasthbal4d.ui.components.PrimaryButton
import gov.up.swasthbal4d.ui.components.SbCard
import gov.up.swasthbal4d.ui.components.ScreenScaffold
import gov.up.swasthbal4d.ui.theme.SB
import kotlinx.coroutines.launch

/** One app, role-based login (design 2.1): mobile OTP once -> 4-digit PIN. The login decides the screens. */
@Composable
fun LoginScreen(repo: Repository, onLoggedIn: (role: String) -> Unit) {
    var step by remember { mutableStateOf(0) } // 0 mobile, 1 otp, 2 set pin
    var mobile by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var devHint by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var role by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun run(block: suspend () -> Unit) {
        busy = true; err = null
        scope.launch {
            try { block() } catch (e: Exception) { err = e.userMessage() }
            busy = false
        }
    }

    ScreenScaffold(title = "स्वस्थ बाल 4D", subtitle = "UP AI Mission · RBSK · लॉगिन") {
        SbCard {
            when (step) {
                0 -> {
                    Label("मोबाइल नंबर (ब्लॉक एडमिन द्वारा पंजीकृत)")
                    OutlinedTextField(mobile, { mobile = it.filter(Char::isDigit).take(10) }, Modifier.fillMaxWidth(),
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        placeholder = { Text("10 अंकों का नंबर") })
                    Text("डेमो: आशा 9000000001 · डॉक्टर 9000000002 · अधिकारी 9000000003", fontSize = 11.sp, color = SB.Muted)
                }
                1 -> {
                    Label("OTP डालें - $mobile पर भेजा गया")
                    OutlinedTextField(otp, { otp = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    devHint?.let { Text("डेव OTP: $it", fontSize = 12.sp, color = SB.Muted) }
                }
                else -> {
                    Label("4 अंकों का PIN बनाएँ - बिना नेटवर्क के लॉगिन के लिए")
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), label = { Text("PIN") })
                    OutlinedTextField(pin2, { pin2 = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), label = { Text("PIN दोबारा") })
                }
            }
            ErrorText(err)
        }
        when (step) {
            0 -> PrimaryButton("OTP भेजें", enabled = mobile.length == 10 && !busy) {
                run { devHint = repo.requestOtp(mobile).devOtp; step = 1 }
            }
            1 -> PrimaryButton("आगे बढ़ें", enabled = otp.length == 6 && !busy) {
                run {
                    val t = repo.verifyOtp(mobile, otp)
                    role = t.user.role
                    step = 2
                }
            }
            else -> PrimaryButton("PIN सेव करें", color = SB.Green, enabled = pin.length == 4 && pin == pin2 && !busy) {
                run {
                    repo.setPin(pin)
                    if (role == "worker") runCatching { repo.refreshOfflinePack() } // ruleset + due list for offline work
                    onLoggedIn(role)
                }
            }
        }
        if (step == 1) TextButton(onClick = { step = 0; otp = "" }) { Text("नंबर बदलें") }
    }
}

@Composable
fun PinScreen(repo: Repository, onUnlocked: (role: String) -> Unit, onLogout: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val user = repo.session.user
    ScreenScaffold(title = "नमस्ते, ${user?.name ?: ""}", subtitle = user?.subtitleHi) {
        SbCard {
            Label("PIN डालें")
            OutlinedTextField(pin, {
                pin = it.filter(Char::isDigit).take(4)
                if (pin.length == 4) scope.launch {
                    if (repo.unlock(pin)) onUnlocked(repo.session.user?.role ?: "worker") else { err = "PIN गलत है"; pin = "" }
                }
            }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold))
            ErrorText(err)
            Text("PIN बिना नेटवर्क के भी काम करता है", fontSize = 12.sp, color = SB.Muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { scope.launch { repo.session.logout(); onLogout() } }) { Text("दूसरे नंबर से लॉगिन") }
        }
    }
}
