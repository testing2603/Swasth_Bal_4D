package gov.up.swasthbal4d.ui.screens.screening

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gov.up.swasthbal4d.SwasthBalApp
import gov.up.swasthbal4d.data.DomainDraft
import gov.up.swasthbal4d.data.VisitDraft
import gov.up.swasthbal4d.data.net.ChildCard
import gov.up.swasthbal4d.data.net.WorkerResult
import gov.up.swasthbal4d.media.Speaker
import gov.up.swasthbal4d.rules.DomainOutcome
import gov.up.swasthbal4d.rules.RulesEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Holds the visit being screened. Survives navigation between steps (autosave + resume). */
class ScreeningViewModel(app: Application) : AndroidViewModel(app) {
    val repo = (app as SwasthBalApp).repo
    val speaker = Speaker(app)

    private val _draft = MutableStateFlow<VisitDraft?>(null)
    val draft: StateFlow<VisitDraft?> = _draft
    var rules by mutableStateOf<RulesEngine?>(null) // Compose state: screens recompose once the ruleset loads
        private set
    var result: WorkerResult? = null
        private set
    /** Step index inside each D, so the worker resumes where they left off. */
    val stepIndex = mutableMapOf<String, Int>()

    init {
        viewModelScope.launch { rules = repo.rules() }
    }

    fun start(card: ChildCard) {
        _draft.value = VisitDraft(card = card)
        result = null
        stepIndex.clear()
        viewModelScope.launch { rules = repo.rules() }
    }

    fun update(f: (VisitDraft) -> VisitDraft) {
        _draft.value = _draft.value?.let(f)
    }

    fun updateDomain(d: String, f: (DomainDraft) -> DomainDraft) = update { v ->
        v.copy(domains = v.domains + (d to f(v.domains[d] ?: DomainDraft(d))))
    }

    /** Called when the consent screen opens: create the visit on the server if we are online. */
    fun prepareOnline() {
        val d = _draft.value ?: return
        if (!d.serverKnowsVisit) repo.ensureVisitOnline(d) { ok -> update { it.copy(serverKnowsVisit = ok) } }
    }

    fun consentGiven(method: String, transcript: String?) {
        update { it.copy(consentMethod = method, voiceTranscript = transcript) }
        val d = _draft.value ?: return
        if (method == "voice" && d.serverKnowsVisit) repo.pushConsent(d) // OTP path already verified on the server
    }

    suspend fun refuse(reason: String) {
        _draft.value?.let { repo.refuseConsent(it, reason) }
        _draft.value = null
    }

    /** A D is finished: upload it in the background while the next D is screened (design 7.2). */
    fun finishDomain(d: String) {
        updateDomain(d) { it.copy(done = true) }
        val v = _draft.value ?: return
        if (v.serverKnowsVisit) repo.pushDomain(v.id, v.domains.getValue(d))
    }

    fun dangerAnswered() {
        val v = _draft.value ?: return
        if (v.serverKnowsVisit) repo.pushDanger(v.id, v.dangerSigns)
    }

    fun skipDomain(d: String, reason: String) {
        updateDomain(d) { it.copy(skippedReason = reason, done = true) }
    }

    fun addMedia(d: String, kind: String, tag: String, path: String) {
        val v = _draft.value ?: return
        val allowed = when (kind) {
            "photo" -> v.consentItems["photo"] == true
            "video" -> v.consentItems["video"] == true
            else -> v.consentItems["voice"] == true
        }
        if (!allowed) return // consent said no: nothing of that kind is recorded
        if (d in v.domains) updateDomain(d) { it.copy(captured = it.captured + tag) }
        viewModelScope.launch { repo.queueMedia(v.id, d, kind, tag, path) }
    }

    fun localOutcome(d: String): DomainOutcome? {
        val v = _draft.value ?: return null
        val r = rules ?: return null
        return r.evaluate(v.domains[d]?.toInput() ?: return null, v.ageMonths)
    }

    suspend fun submit(): WorkerResult {
        val v = _draft.value ?: error("no visit")
        return repo.submit(v).also { result = it }
    }

    fun ruleset(): RulesEngine? = rules

    override fun onCleared() {
        speaker.shutdown()
    }
}
