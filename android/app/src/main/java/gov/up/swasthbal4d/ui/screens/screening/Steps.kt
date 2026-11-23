package gov.up.swasthbal4d.ui.screens.screening

import gov.up.swasthbal4d.rules.RulesEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** One guided step inside a D. The age band decides which steps appear (UC-1). */
sealed class Step(val key: String) {
    class Capture(val id: String, val kind: String, val textHi: String, val textEn: String) : Step("cap_$id") {
        val flash get() = id.endsWith("_flash")
        val needFace get() = id == "face_front" || id == "eyes_flash"
        val labelHi get() = CAPTURE_LABEL[id] ?: textHi
    }
    class Device(val id: String, val nameHi: String, val unit: String, val stepsHi: List<String>, val min: Double, val max: Double) : Step("dev_$id")
    object NameCall : Step("name_call")
    object PictureNaming : Step("picture_naming")
    object StarFollow : Step("star_follow")
    object EyeChart : Step("eye_chart")
    object ToneTest : Step("tone_test")
    object Questions : Step("questions")
}

val CAPTURE_LABEL = mapOf(
    "face_front" to "चेहरा · सामने से", "eyes_flash" to "आँखें · फ़्लैश", "palate_flash" to "तालू · फ़्लैश",
    "back" to "पीठ", "palms_nails" to "हथेलियाँ · नाखून", "legs_feet" to "पैर · तलवे", "walking_video" to "चलने का वीडियो",
)

private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content ?: ""

fun buildSteps(rules: RulesEngine, d: String, age: Int): List<Step> {
    val out = mutableListOf<Step>()
    // name-call video and picture naming are D4 tasks, not plain captures
    rules.captures(d, age).filter { it.s("id") !in setOf("name_call", "picture_naming") }.forEach {
        out += Step.Capture(it.s("id"), it.s("kind"), it.s("text_hi"), it.s("text_en"))
    }
    rules.devices(d, age).forEach { dev ->
        out += Step.Device(dev.s("id"), dev.s("name_hi"), dev.s("unit"),
            dev["steps_hi"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            dev["min"]?.jsonPrimitive?.doubleOrNull ?: 0.0, dev["max"]?.jsonPrimitive?.doubleOrNull ?: 999.0)
    }
    if (d == "D4") {
        if (age in 6..72) out += Step.NameCall
        if (age in 18..72) out += Step.PictureNaming
        if (age > 72) { out += Step.StarFollow; out += Step.EyeChart; out += Step.ToneTest }
    }
    out += Step.Questions // questions + worker comment always close the D (screen 8)
    return out
}
