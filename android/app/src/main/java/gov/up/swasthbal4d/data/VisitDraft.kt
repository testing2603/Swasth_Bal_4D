package gov.up.swasthbal4d.data

import gov.up.swasthbal4d.data.net.ChildCard
import gov.up.swasthbal4d.data.net.ConsentIn
import gov.up.swasthbal4d.data.net.DomainIn
import gov.up.swasthbal4d.data.net.FullVisitIn
import gov.up.swasthbal4d.data.net.SubmitIn
import gov.up.swasthbal4d.rules.DomainInput
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

/** Everything captured for one D. Autosaved to the draft as the worker moves on. */
data class DomainDraft(
    val domain: String,
    val answers: Map<String, String> = emptyMap(),
    val readings: Map<String, Double> = emptyMap(),
    val tasks: Map<String, Any> = emptyMap(),
    val comment: String? = null,
    val worried: Boolean = false,
    val skippedReason: String? = null,
    val captured: List<String> = emptyList(), // capture tags taken (photos / videos / audio)
    val done: Boolean = false,
) {
    fun toInput() = DomainInput(domain, answers, readings, tasks, worried, comment, skippedReason)

    fun toWire() = DomainIn(
        answers = answers,
        readings = readings,
        tasks = tasks.mapValues { (_, v) -> v.toJson() },
        comment = comment,
        worried = worried,
        skippedReason = skippedReason,
    )
}

private fun Any.toJson(): JsonElement = when (this) {
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

data class VisitDraft(
    val id: String = UUID.randomUUID().toString(),
    val card: ChildCard,
    val startedAt: String = Instant.now().toString(),
    val consentItems: Map<String, Boolean> = mapOf("photo" to true, "video" to true, "voice" to true, "whatsapp" to true),
    val consentMethod: String? = null,
    val voiceTranscript: String? = null,
    val refusedReason: String? = null,
    val dangerSigns: Map<String, Boolean> = emptyMap(),
    val domains: Map<String, DomainDraft> = card.domains.associateWith { DomainDraft(it) },
    val worried: Boolean = false,
    val worriedReason: String? = null,
    val comment: String? = null,
    val serverKnowsVisit: Boolean = false,
) {
    val ageMonths: Int get() = card.child.ageMonths
    val order: List<String> get() = listOf("D1", "D2", "D3", "D4").filter { it in domains }

    /** D1 open, D2-D4 unlock in order (design 3.2). */
    fun unlocked(d: String): Boolean {
        val idx = order.indexOf(d)
        return idx <= 0 || domains[order[idx - 1]]?.done == true
    }

    fun toWire(mode: String, provisional: String?) = FullVisitIn(
        id = id,
        childId = card.child.id,
        dueItemId = card.dueItemId,
        mode = mode,
        startedAt = startedAt.removeSuffix("Z"),
        consent = ConsentIn(consentItems, consentMethod, voiceTranscript = voiceTranscript, refusedReason = refusedReason),
        dangerSigns = dangerSigns,
        domains = domains.mapValues { it.value.toWire() },
        submit = SubmitIn(worried, worriedReason, comment),
        provisionalColor = provisional,
    )
}
