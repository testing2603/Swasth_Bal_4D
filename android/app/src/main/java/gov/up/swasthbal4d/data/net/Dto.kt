package gov.up.swasthbal4d.data.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/* Wire models. JSON uses snake_case; ApiClient configures JsonNamingStrategy.SnakeCase. */

@Serializable data class OtpRequest(val mobile: String)
@Serializable data class OtpRequestOut(val sent: Boolean, val devOtp: String? = null)
@Serializable data class OtpVerify(val mobile: String, val otp: String)
@Serializable data class PinSet(val pin: String)
@Serializable data class PinLogin(val mobile: String, val pin: String)

@Serializable
data class UserDto(
    val id: Int,
    val name: String,
    val role: String,
    val workerType: String? = null,
    val mobile: String,
    val subtitleHi: String? = null,
    val facilityId: Int? = null,
)

@Serializable data class TokenOut(val token: String, val user: UserDto, val pinSet: Boolean)

@Serializable
data class ChildBrief(
    val id: Int,
    val name: String,
    val gender: String,
    val dob: String,
    val ageHi: String,
    val ageEn: String,
    val ageMonths: Int,
    val motherName: String? = null,
    val fatherName: String? = null,
    val rbskId: String? = null,
    val qrCode: String,
    val awc: String? = null,
    val careStatus: String = "none",
    val lastScreenedAt: String? = null,
    val lastScreenedWhere: String? = null,
)

@Serializable
data class DueItemDto(
    val dueItemId: Int,
    val kind: String,
    val badgeHi: String,
    val subtitleHi: String,
    val domains: List<String>,
    val child: ChildBrief,
    val finalResultReady: Boolean = false,
)

@Serializable data class Counts(val today: Int, val followup: Int, val overdue: Int)
@Serializable data class HomeOut(val worker: UserDto, val counts: Counts, val items: List<DueItemDto>)
@Serializable data class OfflinePack(val ruleset: JsonObject, val home: HomeOut)

@Serializable data class AbsentIn(val reason: String, val note: String? = null)

@Serializable
data class AbsentOut(
    val reasonHi: String,
    val nextWhenHi: String,
    val nextDueItemId: Int,
    val parentNotified: List<String>,
    val absentCount: Int,
    val escalatedToSupervisor: Boolean,
)

@Serializable
data class ChildCard(
    val child: ChildBrief,
    val lastScreeningHi: String,
    val todayPlanHi: String,
    val dueItemId: Int? = null,
    val domains: List<String>,
    val ageBand: String,
)

@Serializable data class SearchHit(val id: Int, val name: String, val ageHi: String, val motherName: String? = null, val village: String? = null)

@Serializable
data class ChildIn(
    val name: String,
    val gender: String,
    val dob: String,
    val motherName: String? = null,
    val fatherName: String? = null,
    val parentMobile: String? = null,
    val rbskId: String? = null,
    val force: Boolean = false,
)

@Serializable data class VisitCreate(val id: String, val childId: Int, val dueItemId: Int? = null, val mode: String = "online")

@Serializable
data class ConsentIn(
    val items: Map<String, Boolean> = emptyMap(),
    val method: String? = null,
    val otp: String? = null,
    val voiceTranscript: String? = null,
    val refusedReason: String? = null,
)

@Serializable data class DangerIn(val answers: Map<String, Boolean>)
@Serializable data class ConsentOtpOut(val sentTo: String, val devOtp: String? = null)

@Serializable
data class DomainIn(
    val answers: Map<String, String> = emptyMap(),
    val readings: Map<String, Double> = emptyMap(),
    val tasks: Map<String, JsonElement> = emptyMap(),
    val comment: String? = null,
    val worried: Boolean = false,
    val skippedReason: String? = null,
)

@Serializable data class SubmitIn(val worried: Boolean = false, val worriedReason: String? = null, val comment: String? = null)

@Serializable
data class FullVisitIn(
    val id: String,
    val childId: Int,
    val dueItemId: Int? = null,
    val mode: String,
    val startedAt: String? = null,
    val consent: ConsentIn? = null,
    val dangerSigns: Map<String, Boolean> = emptyMap(),
    val domains: Map<String, DomainIn> = emptyMap(),
    val submit: SubmitIn? = null,
    val provisionalColor: String? = null,
)

@Serializable data class SyncIn(val visits: List<FullVisitIn>)

@Serializable data class DomainView(val domain: String, val nameHi: String, val color: String? = null, val labelHi: String)
@Serializable data class ActionView(val type: String, val textHi: String)

@Serializable
data class WorkerResult(
    val clientId: String? = null,
    val status: String? = null,
    val visitId: String? = null,
    val final: String? = null,
    val finalLabelHi: String? = null,
    val isFinal: Boolean = false,
    val domains: List<DomainView> = emptyList(),
    val tellParentHi: String? = null,
    val actions: List<ActionView> = emptyList(),
    val changedFromProvisional: Boolean = false,
)

@Serializable data class SyncOut(val results: List<WorkerResult>, val serverTime: String)
@Serializable data class MediaOut(val mediaId: String, val evidenceCode: String, val duplicate: Boolean)

/* ---------------- facility */
@Serializable data class FacilityDto(val id: Int, val name: String, val nameHi: String? = null, val type: String)

@Serializable
data class ReferralBrief(
    val id: Int,
    val childName: String,
    val ageHi: String,
    val ageEn: String,
    val gender: String,
    val domains: List<String>,
    val reasonHi: String,
    val reasonEn: String,
    val urgency: String,
    val status: String,
    val chipHi: String,
    val slot: String? = null,
    val createdAt: String,
)

@Serializable data class InboxOut(val facility: FacilityDto, val items: List<ReferralBrief>)
@Serializable data class SummaryItem(val title: String, val detail: String = "", val ruleId: String = "", val domain: String = "")

@Serializable
data class AiSummary(
    val label: String = "",
    val items: List<SummaryItem> = emptyList(),
    val modelsAgree: Boolean? = null,
    val rulesNote: String? = null,
    val suggestedNext: String? = null,
)

@Serializable data class MediaRef(val id: String, val kind: String, val tag: String, val evidenceCode: String, val url: String)

@Serializable
data class ReferralDetail(
    val id: Int,
    val childName: String,
    val ageHi: String,
    val ageEn: String,
    val gender: String,
    val domains: List<String>,
    val reasonHi: String,
    val reasonEn: String,
    val urgency: String,
    val status: String,
    val chipHi: String,
    val slot: String? = null,
    val referredBy: String,
    val qrCode: String,
    val aiSummary: AiSummary = AiSummary(),
    val media: List<MediaRef> = emptyList(),
    val suggestedSlots: List<String> = emptyList(),
)

@Serializable data class AcceptIn(val slot: String)
@Serializable data class DeclineIn(val reason: String, val note: String? = null, val rerouteTo: String? = null)
@Serializable data class DeclineOut(val status: String, val reroutedReferralId: Int? = null)
@Serializable data class CheckinIn(val qrCode: String)

@Serializable
data class OutcomeIn(
    val diagnosisCode: String,
    val diagnosisText: String,
    val confirmed: Boolean = true,
    val findings: String? = null,
    val treatment: String? = null,
    val medicine: String? = null,
    val comment: String? = null,
    val nextFollowup: String? = null,
)

/* ---------------- department */
@Serializable data class Kpis(val childrenScreened: Int, val coveragePct: Int, val referralsReachedPct: Int, val yellowImprovingPct: Int)
@Serializable data class BlockCov(val block: String, val blockHi: String? = null, val expected: Int, val screened: Int, val coveragePct: Int? = null, val missed: Int)
@Serializable data class Funnel(val referred: Int, val reached: Int, val underTreatment: Int, val overdueWeek: Int)
@Serializable data class Timing(val referralToArrival: Double? = null, val arrivalToOutcome: Double? = null)

@Serializable
data class DashboardOut(
    val month: String,
    val kpis: Kpis,
    val coverageByBlock: List<BlockCov>,
    val funnel: Funnel,
    val timingDays: Timing,
    val aiDoctorAgreementPct: Int? = null,
    val flaggedPerDPct: Map<String, Double> = emptyMap(),
)

@Serializable data class AskIn(val q: String)
@Serializable data class AskOut(val answer: String)
