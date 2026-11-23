package gov.up.swasthbal4d

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import gov.up.swasthbal4d.data.Repository
import gov.up.swasthbal4d.ui.screens.auth.LoginScreen
import gov.up.swasthbal4d.ui.screens.auth.PinScreen
import gov.up.swasthbal4d.ui.screens.department.DashboardScreen
import gov.up.swasthbal4d.ui.screens.facility.FacilityInboxScreen
import gov.up.swasthbal4d.ui.screens.facility.OutcomeScreen
import gov.up.swasthbal4d.ui.screens.facility.ReferralDetailScreen
import gov.up.swasthbal4d.ui.screens.facility.facilityCheckin
import gov.up.swasthbal4d.ui.screens.screening.BeforeSubmitScreen
import gov.up.swasthbal4d.ui.screens.screening.ConsentScreen
import gov.up.swasthbal4d.ui.screens.screening.DangerSignsScreen
import gov.up.swasthbal4d.ui.screens.screening.DomainScreen
import gov.up.swasthbal4d.ui.screens.screening.ResultScreen
import gov.up.swasthbal4d.ui.screens.screening.ScreeningViewModel
import gov.up.swasthbal4d.ui.screens.screening.VoiceConsentScreen
import gov.up.swasthbal4d.ui.screens.worker.ChildFoundScreen
import gov.up.swasthbal4d.ui.screens.worker.FindChildScreen
import gov.up.swasthbal4d.ui.screens.worker.QrScanScreen
import gov.up.swasthbal4d.ui.screens.worker.RegisterChildScreen
import gov.up.swasthbal4d.ui.screens.worker.WorkerHomeScreen
import gov.up.swasthbal4d.ui.theme.SwasthBalTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val repo = (application as SwasthBalApp).repo
        val start = if (repo.session.token != null && runBlocking { repo.session.hasPin() }) "pin" else "login"
        setContent { SwasthBalTheme { AppNav(repo, start) } }
    }
}

private fun homeFor(role: String) = when (role) {
    "worker" -> "worker/home"
    "facility" -> "facility/inbox"
    else -> "dept"
}

private fun NavHostController.goRoot(route: String) = navigate(route) { popUpTo(0) { inclusive = true } }

@Composable
fun AppNav(repo: Repository, start: String) {
    val nav = rememberNavController()
    val vm: ScreeningViewModel = viewModel()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val logout: () -> Unit = { scope.launch { repo.session.logout(); nav.goRoot("login") } }

    NavHost(nav, startDestination = start) {
        // ---------------- auth
        composable("login") { LoginScreen(repo) { role -> nav.goRoot(homeFor(role)) } }
        composable("pin") { PinScreen(repo, onUnlocked = { role -> nav.goRoot(homeFor(role)) }, onLogout = { nav.goRoot("login") }) }

        // ---------------- field worker
        composable("worker/home") {
            WorkerHomeScreen(repo,
                onOpenChild = { id -> nav.navigate("worker/child?childId=$id") },
                onScanQr = { nav.navigate("worker/scan") },
                onOffList = { nav.navigate("worker/find") },
                onLogout = logout)
        }
        composable("worker/scan") {
            QrScanScreen("QR स्कैन", onBack = { nav.popBackStack() }) { code ->
                nav.navigate("worker/child?qr=${Uri.encode(code)}") { popUpTo("worker/scan") { inclusive = true } }
            }
        }
        composable("worker/find") {
            FindChildScreen(repo, onBack = { nav.popBackStack() }, onScan = { nav.navigate("worker/scan") },
                onFound = { id -> nav.navigate("worker/child?childId=$id") }, onRegister = { nav.navigate("worker/register") })
        }
        composable("worker/register") {
            RegisterChildScreen(repo, onBack = { nav.popBackStack() }) { id ->
                nav.navigate("worker/child?childId=$id") { popUpTo("worker/find") }
            }
        }
        composable("worker/child?childId={childId}&qr={qr}", arguments = listOf(
            navArgument("childId") { type = NavType.IntType; defaultValue = -1 },
            navArgument("qr") { type = NavType.StringType; nullable = true; defaultValue = null },
        )) { e ->
            val id = e.arguments?.getInt("childId")?.takeIf { it > 0 }
            ChildFoundScreen(repo, id, e.arguments?.getString("qr"), onBack = { nav.popBackStack() }) { card ->
                vm.start(card)
                nav.navigate("consent")
            }
        }

        // ---------------- guided screening
        composable("consent") {
            ConsentScreen(vm, onBack = { nav.popBackStack() }, onVoice = { nav.navigate("consent/voice") },
                onConsented = { nav.navigate("danger") },
                onRefused = {
                    Toast.makeText(ctx, "सहमति नहीं - कारण दर्ज, ANM / पर्यवेक्षक को सूचना", Toast.LENGTH_LONG).show()
                    nav.goRoot("worker/home")
                })
        }
        composable("consent/voice") { VoiceConsentScreen(vm, onBack = { nav.popBackStack() }) { nav.navigate("danger") } }
        composable("danger") {
            DangerSignsScreen(vm, onBack = { nav.popBackStack() }) {
                val first = vm.draft.value?.order?.firstOrNull() ?: "D1"
                nav.navigate("domain/$first")
            }
        }
        composable("domain/{d}") { e ->
            val d = e.arguments?.getString("d") ?: "D1"
            DomainScreen(vm, d, onBack = { nav.popBackStack() }) { next ->
                if (next != null) nav.navigate("domain/$next") else nav.navigate("review")
            }
        }
        composable("review") {
            BeforeSubmitScreen(vm, onBack = { nav.popBackStack() }, onOpenDomain = { d -> nav.navigate("domain/$d") }) {
                nav.navigate("result") { popUpTo("worker/home") }
            }
        }
        composable("result") { ResultScreen(vm) { nav.goRoot("worker/home") } }

        // ---------------- facility (same app, facility login)
        composable("facility/inbox") {
            FacilityInboxScreen(repo, onOpen = { id -> nav.navigate("facility/ref/$id") },
                onScan = { nav.navigate("facility/scan") }, onLogout = logout)
        }
        composable("facility/scan") {
            var busy by remember { mutableStateOf(false) }
            QrScanScreen("QR चेक-इन", onBack = { nav.popBackStack() }) { code ->
                if (!busy) {
                    busy = true
                    scope.launch {
                        Toast.makeText(ctx, facilityCheckin(repo, code), Toast.LENGTH_LONG).show()
                        nav.popBackStack()
                    }
                }
            }
        }
        composable("facility/ref/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { e ->
            val id = e.arguments!!.getInt("id")
            ReferralDetailScreen(repo, id, onBack = { nav.popBackStack() }, onOutcome = { nav.navigate("facility/outcome/$it") })
        }
        composable("facility/outcome/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { e ->
            OutcomeScreen(repo, e.arguments!!.getInt("id"), onBack = { nav.popBackStack() }, onDone = {
                Toast.makeText(ctx, "रेफ़रल बंद ✓ · माता-पिता को डॉक्टर की सलाह भेजी गई", Toast.LENGTH_LONG).show()
                nav.goRoot("facility/inbox")
            })
        }

        // ---------------- department (official login)
        composable("dept") { DashboardScreen(repo, onLogout = logout) }
    }
}
