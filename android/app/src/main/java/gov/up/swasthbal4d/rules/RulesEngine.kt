package gov.up.swasthbal4d.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * On-phone copy of the server rules engine (backend/app/rules/engine.py). It reads the same
 * ruleset.json from the offline pack and gives the PROVISIONAL result when there is no network.
 * The server recomputes the FINAL result after sync.
 */
object Colors {
    const val GREEN = "GREEN"
    const val YELLOW = "YELLOW"
    const val INCOMPLETE = "INCOMPLETE"
    const val RED = "RED"
    private val rank = mapOf(GREEN to 0, YELLOW to 1, INCOMPLETE to 1, RED to 2)

    fun worse(a: String, b: String): String {
        val ra = rank.getValue(a)
        val rb = rank.getValue(b)
        return if (rb > ra || (rb == ra && b == INCOMPLETE)) b else a
    }
}

data class Evidence(val ruleId: String, val color: String, val textHi: String, val source: String, val family: String? = null)

data class DomainInput(
    val domain: String,
    val answers: Map<String, String> = emptyMap(),
    val readings: Map<String, Double> = emptyMap(),
    val tasks: Map<String, Any> = emptyMap(),
    val worried: Boolean = false,
    val worriedReason: String? = null,
    val skippedReason: String? = null,
)

data class DomainOutcome(val domain: String, val color: String, val evidence: List<Evidence>) {
    val reasonHi: String? get() = evidence.firstOrNull { it.color != Colors.GREEN }?.textHi
}

class RulesEngine(private val rs: JsonObject) {

    val version: String = rs["version"]?.jsonPrimitive?.content ?: "?"

    private fun JsonObject.ageOk(age: Int): Boolean {
        val a = this["age_months"]?.jsonArray ?: return true
        return age >= a[0].jsonPrimitive.int() && age <= a[1].jsonPrimitive.int()
    }

    private fun JsonPrimitive.int(): Int = intOrNull ?: doubleOrNull?.toInt() ?: 0

    private fun derived(age: Int): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        val d = rs["derived"]?.jsonObject ?: return out
        for ((name, steps) in d) {
            var v = 0.0
            for (step in steps.jsonArray) {
                val pair = step.jsonArray
                if (age >= pair[0].jsonPrimitive.int()) v = pair[1].jsonPrimitive.doubleOrNull ?: 0.0
            }
            out[name] = v
        }
        return out
    }

    private fun lookup(ctx: Map<String, Map<String, Any>>, path: String): Any? {
        val (group, key) = path.split(".", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return ctx[group]?.get(key)
    }

    private fun prim(p: JsonPrimitive): Any? = when {
        p.isString -> p.content
        p.booleanOrNull != null -> p.booleanOrNull
        else -> p.doubleOrNull
    }

    private fun num(x: Any?): Double? = when (x) {
        is Number -> x.toDouble()
        else -> null
    }

    fun evalCondition(cond: JsonObject, ctx: Map<String, Map<String, Any>>): Boolean {
        cond["all"]?.let { arr -> return arr.jsonArray.all { evalCondition(it.jsonObject, ctx) } }
        cond["any"]?.let { arr -> return arr.jsonArray.any { evalCondition(it.jsonObject, ctx) } }
        val left = lookup(ctx, cond["var"]!!.jsonPrimitive.content) ?: return false
        val right: Any = if (cond.containsKey("value_var")) {
            val r = num(lookup(ctx, cond["value_var"]!!.jsonPrimitive.content)) ?: return false
            r * (cond["factor"]?.jsonPrimitive?.doubleOrNull ?: 1.0)
        } else prim(cond["value"]!!.jsonPrimitive) ?: return false
        val op = cond["op"]!!.jsonPrimitive.content
        if (op == "==") return eq(left, right)
        if (op == "!=") return !eq(left, right)
        val l = num(left) ?: return false
        val r = num(right) ?: return false
        return when (op) {
            "<" -> l < r
            "<=" -> l <= r
            ">" -> l > r
            ">=" -> l >= r
            else -> false
        }
    }

    private fun eq(a: Any, b: Any): Boolean {
        val na = num(a)
        val nb = num(b)
        return if (na != null && nb != null) na == nb else a == b
    }

    fun questions(domain: String, age: Int): List<JsonObject> =
        rs["questions"]!!.jsonArray.map { it.jsonObject }
            .filter { it["domain"]!!.jsonPrimitive.content == domain && it.ageOk(age) }

    fun captures(domain: String, age: Int): List<JsonObject> =
        rs["captures"]!!.jsonArray.map { it.jsonObject }
            .filter { it["domain"]!!.jsonPrimitive.content == domain && it.ageOk(age) }

    fun devices(domain: String, age: Int): List<JsonObject> =
        rs["devices"]!!.jsonArray.map { it.jsonObject }
            .filter { it["domain"]!!.jsonPrimitive.content == domain && it.ageOk(age) }

    fun dangerSigns(): List<JsonObject> = rs["danger_signs"]!!.jsonArray.map { it.jsonObject }

    fun domainNameHi(d: String): String = rs["domains"]!!.jsonObject[d]!!.jsonObject["name_hi"]!!.jsonPrimitive.content

    fun wordsExpected(age: Int): Int = (derived(age)["words_expected"] as? Double)?.toInt() ?: 0

    fun evaluate(inp: DomainInput, age: Int): DomainOutcome {
        val d = inp.domain
        val ctx: Map<String, Map<String, Any>> = mapOf(
            "answers" to inp.answers, "readings" to inp.readings, "tasks" to inp.tasks, "derived" to derived(age),
        )
        var color = Colors.GREEN
        val ev = mutableListOf<Evidence>()

        if (inp.skippedReason != null) {
            ev += Evidence("SKIP", Colors.INCOMPLETE, "छोड़ा गया: ${inp.skippedReason}", "skipped")
            color = Colors.worse(color, Colors.INCOMPLETE)
        } else {
            (rs["required"] as? JsonArray)?.map { it.jsonObject }?.forEach { req ->
                if (req["domain"]!!.jsonPrimitive.content == d && req.ageOk(age)) {
                    req["vars"]!!.jsonArray.forEach { v ->
                        if (lookup(ctx, v.jsonPrimitive.content) == null) {
                            ev += Evidence("REQ-${v.jsonPrimitive.content}", Colors.INCOMPLETE, "ज़रूरी जाँच छूटी - दोबारा लें", "missing")
                            color = Colors.worse(color, Colors.INCOMPLETE)
                        }
                    }
                }
            }
            for (q in questions(d, age)) {
                val a = inp.answers[q["id"]!!.jsonPrimitive.content] ?: continue
                val qColor = q["color"]!!.jsonPrimitive.content
                val flagUnsure = q["flag_unsure"]?.jsonPrimitive?.booleanOrNull ?: (qColor == Colors.RED)
                if (a == q["risk_on"]!!.jsonPrimitive.content) {
                    ev += Evidence(q["id"]!!.jsonPrimitive.content, qColor, q["text_hi"]!!.jsonPrimitive.content, "question")
                    color = Colors.worse(color, qColor)
                } else if (a == "unsure" && flagUnsure) {
                    ev += Evidence(q["id"]!!.jsonPrimitive.content, Colors.YELLOW, "पक्का नहीं - " + q["text_hi"]!!.jsonPrimitive.content, "question")
                    color = Colors.worse(color, Colors.YELLOW)
                }
            }
            for (rule in rs["rules"]!!.jsonArray.map { it.jsonObject }) {
                if (rule["domain"]!!.jsonPrimitive.content != d || !rule.ageOk(age)) continue
                val whenObj = rule["when"]!!.jsonObject
                if (evalCondition(whenObj, ctx)) {
                    val rc = rule["color"]!!.jsonPrimitive.content
                    ev += Evidence(rule["id"]!!.jsonPrimitive.content, rc, rule["text_hi"]!!.jsonPrimitive.content, "rule",
                        whenObj["var"]?.jsonPrimitive?.content ?: rule["id"]!!.jsonPrimitive.content)
                    color = Colors.worse(color, rc)
                }
            }
        }
        if (inp.worried) {
            ev += Evidence("WORRIED", Colors.YELLOW, "आशा को चिंता है: ${inp.worriedReason.orEmpty()}".trim(), "worker")
            color = Colors.worse(color, Colors.YELLOW)
        }
        return DomainOutcome(d, color, dedupe(ev))
    }

    private fun dedupe(ev: List<Evidence>): List<Evidence> {
        val best = LinkedHashMap<String, Evidence>()
        val rank = mapOf(Colors.GREEN to 0, Colors.YELLOW to 1, Colors.INCOMPLETE to 1, Colors.RED to 2)
        for (e in ev) {
            val key = e.family ?: (e.ruleId + e.source)
            val cur = best[key]
            if (cur == null || rank.getValue(e.color) > rank.getValue(cur.color)) best[key] = e
        }
        return best.values.toList()
    }

    fun combine(outcomes: List<DomainOutcome>, worried: Boolean): String {
        var c = Colors.GREEN
        outcomes.forEach { c = Colors.worse(c, it.color) }
        if (worried) c = Colors.worse(c, Colors.YELLOW)
        return c
    }
}
