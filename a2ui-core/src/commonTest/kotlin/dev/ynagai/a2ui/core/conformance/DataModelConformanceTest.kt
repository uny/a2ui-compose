package dev.ynagai.a2ui.core.conformance

import dev.ynagai.a2ui.core.protocol.A2uiFormatException
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.surface.A2uiStateException
import dev.ynagai.a2ui.core.surface.JsonPointer
import dev.ynagai.a2ui.core.surface.MessageProcessor
import dev.ynagai.a2ui.core.surface.RendererState
import dev.ynagai.a2ui.core.surface.resolve
import dev.ynagai.a2ui.core.surface.write
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Upstream's cross-SDK data model suites, run against [write] and [MessageProcessor].
 *
 * `data_model.yaml` drives a data model directly (`action: data_model`); `data_deletion.yaml` and
 * `data_model_pointers.yaml` stream `createSurface` and `updateDataModel` messages and compare the
 * surface's data model afterwards (`action: validate`). Together they pin every point where 0.3.0
 * disagreed with the other SDKs: array auto-vivification, `-`, padding, null deletes that keep an
 * array's length, and writes through a primitive.
 *
 * A case or a step is skipped only for a reason listed in [SKIPPED], and
 * [every_skip_is_still_needed] fails once a skipped case would pass -- so the list cannot outlive
 * the gap it records.
 */
class DataModelConformanceTest {
    @Test
    fun the_suites_are_the_size_upstream_publishes() {
        assertEquals(52, DATA_MODEL_CASES.size)
        assertEquals(14, MESSAGE_CASES.size)
    }

    @Test
    fun every_data_model_case_holds() {
        val failures = DATA_MODEL_CASES
            .filter { it.name !in SKIPPED }
            .mapNotNull { case -> runDataModelCase(case)?.let { "${case.name}: $it" } }
        assertTrue(failures.isEmpty(), "${failures.size} case(s) failed:\n" + failures.joinToString("\n"))
    }

    @Test
    fun every_message_case_holds() {
        val failures = MESSAGE_CASES
            .filter { it.name !in SKIPPED }
            .mapNotNull { case -> runMessageCase(case)?.let { "${case.name}: $it" } }
        assertTrue(failures.isEmpty(), "${failures.size} case(s) failed:\n" + failures.joinToString("\n"))
    }

    @Test
    fun every_skip_is_still_needed() {
        val names = (DATA_MODEL_CASES + MESSAGE_CASES).map { it.name }.toSet()
        assertEquals(emptySet(), SKIPPED.keys - names, "a skip names a case upstream no longer has")
        val passing = (DATA_MODEL_CASES.map { it to runDataModelCase(it) } +
            MESSAGE_CASES.map { it to runMessageCase(it) })
            .filter { (case, failure) -> case.name in SKIPPED && failure == null }
            .map { it.first.name }
        assertEquals(emptyList(), passing, "these skipped cases now pass; drop them from SKIPPED")
    }

    @Test
    fun observer_assertions_are_counted_rather_than_silently_dropped() {
        // a2ui-core's data model is an immutable value with no observers -- Compose recomposition
        // is the renderer's notification mechanism -- so `expect_notified` has nothing to measure.
        // The count is pinned so a new observer assertion upstream shows up here.
        val notified = DATA_MODEL_CASES.sumOf { case ->
            case.steps.count { it.containsKey("expect_notified") }
        }
        assertEquals(22, notified)
    }
}

/** Cases this implementation does not run, each with the reason. */
private val SKIPPED: Map<String, String> = mapOf(
    "test_data_model_rejects_write_through_primitive_root" to
        "starts from a primitive root; a surface's data model is typed as an object, so a " +
        "primitive root cannot be constructed here",
    "test_data_model_set_defensive_copy_isolates_caller_mutation" to
        "mutates the caller's input after `set`; JsonElement is immutable, so there is nothing " +
        "to mutate",
)

private class Case(val name: String, val raw: JsonObject) {
    @Suppress("UNCHECKED_CAST")
    val steps: List<JsonObject> get() = (raw["steps"] as? JsonArray).orEmpty() as List<JsonObject>
}

private fun suite(file: String): List<Case> {
    val source = CoreConformanceSources.ALL[file] ?: error("`$file` is not vendored")
    return (Json.parseToJsonElement(source) as JsonArray).map { element ->
        val case = element as JsonObject
        Case((case["name"] as JsonPrimitive).content, case)
    }
}

private val DATA_MODEL_CASES: List<Case> = suite("data_model.json")

private val MESSAGE_CASES: List<Case> =
    suite("data_deletion.json") + suite("data_model_pointers.json")

/** Runs one `action: data_model` case, returning why it failed or null when it held. */
private fun runDataModelCase(case: Case): String? {
    val initial = case.raw["initial"] ?: JsonObject(emptyMap())
    var model = initial as? JsonObject ?: return "initial model is not an object"
    case.steps.forEachIndexed { index, step ->
        val op = (step["op"] as JsonPrimitive).content
        val path = (step["path"] as? JsonPrimitive)?.content
        val expectsError = step.containsKey("expect_error")
        val where = "step $index ($op ${path.orEmpty()})"
        when (op) {
            "get" -> {
                val value = try {
                    model.resolve(JsonPointer.parse(path!!))
                } catch (e: A2uiFormatException) {
                    if (expectsError) return@forEachIndexed
                    return "$where raised ${e.message}"
                }
                if (expectsError) return "$where read `$value` where an error was expected"
                checkRead(step, value)?.let { return "$where: $it" }
            }
            "set", "delete" -> {
                val value = if (op == "delete") JsonNull else step["value"] ?: JsonNull
                model = try {
                    model.write(JsonPointer.parse(path!!), value)
                } catch (e: A2uiStateException) {
                    if (expectsError) return@forEachIndexed
                    return "$where raised ${e.message}"
                } catch (e: A2uiFormatException) {
                    if (expectsError) return@forEachIndexed
                    return "$where raised ${e.message}"
                }
                if (expectsError) return "$where succeeded where an error was expected"
                (step["expect_values"] as? JsonObject)?.forEach { (watchedPath, expected) ->
                    val actual = model.resolve(JsonPointer.parse(watchedPath))
                    if (!sameJson(expected, actual)) {
                        return "$where: `$watchedPath` holds `$actual`, expected `$expected`"
                    }
                }
            }
            "dispose" -> Unit
            else -> return "$where: no harness for op `$op`"
        }
    }
    return null
}

/** Checks a `get` step's `expect`, `expect_absent` and `expect_type` against [value]. */
private fun checkRead(step: JsonObject, value: JsonElement?): String? {
    // A JSON null in an array is a hole left by a delete, which the other SDKs read back as
    // `undefined` / `None`; "absent" covers both.
    val absent = value == null || value is JsonNull
    if ((step["expect_absent"] as? JsonPrimitive)?.content == "true" && !absent) {
        return "read `$value`, expected nothing"
    }
    step["expect"]?.let { expected ->
        if (!sameJson(expected, value)) return "read `$value`, expected `$expected`"
    }
    (step["expect_type"] as? JsonPrimitive)?.content?.let { type ->
        val ok = when (type) {
            "list" -> value is JsonArray
            "object" -> value is JsonObject
            else -> return "unknown expect_type `$type`"
        }
        if (!ok) return "read `$value`, expected a $type"
    }
    return null
}

/** Runs one `action: validate` case by streaming its steps through [MessageProcessor]. */
private fun runMessageCase(case: Case): String? {
    var state = RendererState()
    case.steps.forEachIndexed { index, step ->
        val expectsError = step.containsKey("expectError")
        val payload = (step["payload"] as JsonArray)
        state = try {
            val messages = payload.map {
                A2uiJson.strict.decodeFromJsonElement(AgentToRendererMessage.serializer(), it)
            }
            MessageProcessor.applyAll(state, messages).state
        } catch (e: A2uiStateException) {
            if (expectsError) return@forEachIndexed
            return "step $index raised ${e.message}"
        } catch (e: A2uiFormatException) {
            if (expectsError) return@forEachIndexed
            return "step $index raised ${e.message}"
        }
        if (expectsError) return "step $index succeeded where an error was expected"
    }
    val surfaces = (case.raw["expect"] as? JsonObject)?.get("surfaces") as? JsonObject ?: return null
    surfaces.forEach { (surfaceId, expected) ->
        val dataModel = (expected as JsonObject)["dataModel"] ?: return@forEach
        val actual = state.surface(surfaceId)?.dataModel ?: return "surface `$surfaceId` does not exist"
        if (!sameJson(dataModel, actual)) return "`$surfaceId` holds `$actual`, expected `$dataModel`"
    }
    return null
}

/**
 * JSON equality, with numbers compared by value.
 *
 * The suites were converted from YAML, where `1` and `1.0` are both written; the data model keeps
 * whichever spelling the payload carried. Neither difference is the data model's to decide.
 */
private fun sameJson(expected: JsonElement?, actual: JsonElement?): Boolean = when {
    expected == null || actual == null -> expected == actual
    expected is JsonObject && actual is JsonObject ->
        expected.keys == actual.keys && expected.all { (k, v) -> sameJson(v, actual[k]) }
    expected is JsonArray && actual is JsonArray ->
        expected.size == actual.size && expected.indices.all { sameJson(expected[it], actual[it]) }
    expected is JsonPrimitive && actual is JsonPrimitive && !expected.isString && !actual.isString &&
        expected.doubleOrNull != null && actual.doubleOrNull != null ->
        expected.doubleOrNull == actual.doubleOrNull
    else -> expected == actual
}
