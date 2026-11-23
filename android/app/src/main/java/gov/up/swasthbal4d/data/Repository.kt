package gov.up.swasthbal4d.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import gov.up.swasthbal4d.data.local.Kv
import gov.up.swasthbal4d.data.local.LocalDb
import gov.up.swasthbal4d.data.local.OutboxVisit
import gov.up.swasthbal4d.data.local.PendingMedia
import gov.up.swasthbal4d.data.local.SessionStore
import gov.up.swasthbal4d.data.net.AbsentIn
import gov.up.swasthbal4d.data.net.AbsentOut
import gov.up.swasthbal4d.data.net.ApiClient
import gov.up.swasthbal4d.data.net.AppJson
import gov.up.swasthbal4d.data.net.ChildCard
import gov.up.swasthbal4d.data.net.ChildIn
import gov.up.swasthbal4d.data.net.ConsentIn
import gov.up.swasthbal4d.data.net.DomainView
import gov.up.swasthbal4d.data.net.ActionView
import gov.up.swasthbal4d.data.net.HomeOut
import gov.up.swasthbal4d.data.net.OtpRequest
import gov.up.swasthbal4d.data.net.OtpVerify
import gov.up.swasthbal4d.data.net.PinLogin
import gov.up.swasthbal4d.data.net.PinSet
import gov.up.swasthbal4d.data.net.SearchHit
import gov.up.swasthbal4d.data.net.SyncIn
import gov.up.swasthbal4d.data.net.TokenOut
import gov.up.swasthbal4d.data.net.VisitCreate
import gov.up.swasthbal4d.data.net.WorkerResult
import gov.up.swasthbal4d.rules.Colors
import gov.up.swasthbal4d.rules.RulesEngine
import gov.up.swasthbal4d.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class Repository(
    private val context: Context,
    val session: SessionStore,
    val client: ApiClient,
    val db: LocalDb,
) {
    private val api get() = client.api
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------------------ connectivity
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online
    val pendingVisits = db.outbox().pendingCount()
    val pendingMedia = db.media().pendingCount()

    init {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        _online.value = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _online.value = true
                    SyncScheduler.syncNow(context)
                }
                override fun onLost(network: Network) { _online.value = false }
            },
        )
    }

    // ------------------------------------------------------------ auth
    suspend fun requestOtp(mobile: String) = api.requestOtp(OtpRequest(mobile))
    suspend fun verifyOtp(mobile: String, otp: String): TokenOut =
        api.verifyOtp(OtpVerify(mobile, otp)).also { session.save(it.token, it.user) }

    suspend fun setPin(pin: String) {
        api.setPin(PinSet(pin))
        session.savePin(pin)
    }

    /** Offline-capable unlock: local PIN check first, refresh token from server when online. */
    suspend fun unlock(pin: String): Boolean {
        if (!session.checkPin(pin)) return false
        val u = session.user ?: return false
        runCatching { api.pinLogin(PinLogin(u.mobile, pin)) }.getOrNull()?.let { session.save(it.token, it.user) }
        return true
    }

    // ------------------------------------------------------------ rules / offline pack
    @Volatile private var engine: RulesEngine? = null

    suspend fun rules(): RulesEngine {
        engine?.let { return it }
        val json = db.kv().get("ruleset")?.json
            ?: context.assets.open("ruleset.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        return RulesEngine(AppJson.parseToJsonElement(json).jsonObject).also { engine = it }
    }

    suspend fun refreshOfflinePack() {
        val pack = api.offlinePack()
        db.kv().put(Kv("ruleset", AppJson.encodeToString(JsonObject.serializer(), pack.ruleset)))
        db.kv().put(Kv("home", AppJson.encodeToString(HomeOut.serializer(), pack.home)))
        engine = RulesEngine(pack.ruleset)
    }

    /** Due list: live when online, cached copy otherwise. Second value = true if from cache. */
    suspend fun home(): Pair<HomeOut?, Boolean> = try {
        val h = api.home()
        db.kv().put(Kv("home", AppJson.encodeToString(HomeOut.serializer(), h)))
        h to false
    } catch (e: Exception) {
        db.kv().get("home")?.let { AppJson.decodeFromString(HomeOut.serializer(), it.json) } to true
    }

    suspend fun markAbsent(dueItemId: Int, reason: String, note: String?): AbsentOut =
        api.markAbsent(dueItemId, AbsentIn(reason, note))

    // ------------------------------------------------------------ identify
    suspend fun byQr(code: String): ChildCard = try {
        api.childByQr(code)
    } catch (e: java.io.IOException) {
        cachedCard { it.child.qrCode == code || it.child.rbskId == code } ?: throw e
    }

    suspend fun card(childId: Int): ChildCard = try {
        api.childCard(childId)
    } catch (e: java.io.IOException) {
        cachedCard { it.child.id == childId } ?: throw e
    }

    private suspend fun cachedCard(match: (gov.up.swasthbal4d.data.net.DueItemDto) -> Boolean): ChildCard? {
        val h = db.kv().get("home")?.let { AppJson.decodeFromString(HomeOut.serializer(), it.json) } ?: return null
        val it = h.items.firstOrNull(match) ?: return null
        return ChildCard(it.child, "पिछली जाँच: ऑफ़लाइन - सर्वर से जाँच बाद में", "आज: " + it.domains.joinToString(), it.dueItemId,
            it.domains, if (it.child.ageMonths < 2) "newborn" else "child")
    }

    suspend fun search(q: String?, mobile: String?, wide: Boolean): List<SearchHit> = api.search(q, mobile, wide)
    suspend fun register(body: ChildIn): ChildCard = api.register(body)

    // ------------------------------------------------------------ visit (hybrid)
    /** Best effort: tell the server about the visit so per-D data and media can upload while the
     *  worker screens the next D. Failure is fine - the outbox carries everything later. */
    fun ensureVisitOnline(d: VisitDraft, onDone: (Boolean) -> Unit) {
        appScope.launch {
            onDone(runCatching { client.quick.startVisit(VisitCreate(d.id, d.card.child.id, d.card.dueItemId)) }.isSuccess)
        }
    }

    fun pushConsent(d: VisitDraft) {
        appScope.launch {
            runCatching { client.quick.consent(d.id, ConsentIn(d.consentItems, d.consentMethod, voiceTranscript = d.voiceTranscript)) }
        }
    }

    suspend fun sendConsentOtp(visitId: String) = api.consentOtp(visitId)

    suspend fun verifyConsentOtp(d: VisitDraft, otp: String) {
        api.consent(d.id, ConsentIn(d.consentItems, "otp", otp = otp))
    }

    fun pushDanger(visitId: String, answers: Map<String, Boolean>) {
        appScope.launch { runCatching { client.quick.dangerSigns(visitId, gov.up.swasthbal4d.data.net.DangerIn(answers)) } }
    }

    fun pushDomain(visitId: String, dd: DomainDraft) {
        appScope.launch { runCatching { client.quick.saveDomain(visitId, dd.domain, dd.toWire()) } }
    }

    suspend fun refuseConsent(d: VisitDraft, reason: String) {
        val payload = d.copy(refusedReason = reason, domains = emptyMap()).toWire("offline", null).copy(submit = null)
        db.outbox().put(OutboxVisit(d.id, d.card.child.id, d.card.child.name,
            AppJson.encodeToString(gov.up.swasthbal4d.data.net.FullVisitIn.serializer(), payload), null))
        SyncScheduler.syncNow(context)
    }

    suspend fun queueMedia(visitId: String, domain: String, kind: String, tag: String, path: String) {
        val prio = when (kind) { "photo" -> 1; "audio" -> 2; else -> 3 }
        db.media().add(PendingMedia(visitId = visitId, domain = domain, kind = kind, tag = tag, path = path, priority = prio))
        SyncScheduler.uploadMedia(context)
    }

    /** Submit: FINAL result on a stable connection, otherwise PROVISIONAL + outbox. */
    suspend fun submit(d: VisitDraft): WorkerResult {
        val rules = rules()
        val outcomes = d.order.map { rules.evaluate(d.domains.getValue(it).toInput(), d.ageMonths) }
        val provisional = rules.combine(outcomes, d.worried)
        val wire = d.toWire("online", provisional)
        val res = runCatching { client.quick.sync(SyncIn(listOf(wire))).results.first() }.getOrNull()
        if (res != null && res.status in setOf("synced", "already_synced")) {
            SyncScheduler.uploadMedia(context)
            return res
        }
        val offline = wire.copy(mode = "offline")
        db.outbox().put(OutboxVisit(d.id, d.card.child.id, d.card.child.name,
            AppJson.encodeToString(gov.up.swasthbal4d.data.net.FullVisitIn.serializer(), offline), provisional))
        SyncScheduler.syncNow(context)
        return WorkerResult(
            visitId = d.id, final = provisional, finalLabelHi = labelHi(provisional), isFinal = false,
            domains = outcomes.map { o ->
                DomainView(o.domain, rules.domainNameHi(o.domain), o.color,
                    labelHi(o.color) + (o.reasonHi?.let { " · $it" } ?: ""))
            },
            tellParentHi = "यह अस्थायी नतीजा है। अंतिम नतीजा नेटवर्क मिलने पर आपको और माता-पिता को भेजा जाएगा।",
            actions = buildList {
                if (provisional == Colors.RED) add(ActionView("referral", "रेफ़रल कार्ड सिंक होते ही माता-पिता को भेजा जाएगा"))
                add(ActionView("outbox", "डेटा सुरक्षित रखा - नेटवर्क आते ही अपने आप भेजा जाएगा"))
            },
        )
    }

    companion object {
        fun labelHi(color: String?) = when (color) {
            Colors.GREEN -> "ठीक"
            Colors.YELLOW -> "ध्यान दें"
            Colors.RED -> "डॉक्टर को दिखाएँ"
            else -> "दोबारा जाँच"
        }
    }
}
