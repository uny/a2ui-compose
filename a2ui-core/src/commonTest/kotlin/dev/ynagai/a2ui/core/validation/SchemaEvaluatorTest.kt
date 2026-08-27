package dev.ynagai.a2ui.core.validation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The evaluator against the shapes A2UI v1.0 actually publishes.
 *
 * The two documents below are trimmed from `common_types.json` and `catalogs/basic/catalog.json`
 * with their structure kept exactly: the cross-document cycle
 * (`FunctionCall` -> `catalog.json#/$defs/anyFunction` -> a function -> its args ->
 * `DynamicString` -> `FunctionCall`), the `allOf` + `unevaluatedProperties: false` shape every
 * function definition uses, and the `catalog.json` placeholder filename. Those three are what the
 * checker's design is answering, so a test on a schema of the author's own invention would not
 * exercise it.
 */
private val COMMON_TYPES = """
{
  "${'$'}id": "https://a2ui.org/specification/v1_0/common_types.json",
  "${'$'}defs": {
    "DataBinding": {
      "type": "object",
      "properties": { "path": { "type": "string" } },
      "required": ["path"],
      "additionalProperties": false
    },
    "DynamicString": {
      "oneOf": [
        { "type": "string" },
        { "${'$'}ref": "#/${'$'}defs/DataBinding" },
        { "${'$'}ref": "#/${'$'}defs/FunctionCall" }
      ]
    },
    "FunctionCommon": {
      "type": "object",
      "properties": { "catalogId": { "type": "string" } }
    },
    "FunctionCall": {
      "type": "object",
      "properties": {
        "call": { "type": "string" },
        "catalogId": { "type": "string" },
        "args": { "type": "object" }
      },
      "required": ["call"],
      "oneOf": [{ "${'$'}ref": "catalog.json#/${'$'}defs/anyFunction" }],
      "unevaluatedProperties": false
    }
  }
}
""".trimIndent()

private val CATALOG = """
{
  "${'$'}id": "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json",
  "catalogId": "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json",
  "${'$'}defs": {
    "anyFunction": {
      "oneOf": [{ "${'$'}ref": "#/functions/regex" }, { "${'$'}ref": "#/functions/openUrl" }]
    }
  },
  "functions": {
    "regex": {
      "type": "object",
      "returnType": "validationResult",
      "allOf": [
        { "${'$'}ref": "https://a2ui.org/specification/v1_0/common_types.json#/${'$'}defs/FunctionCommon" },
        {
          "type": "object",
          "properties": {
            "call": { "const": "regex" },
            "args": {
              "type": "object",
              "properties": {
                "value": { "${'$'}ref": "https://a2ui.org/specification/v1_0/common_types.json#/${'$'}defs/DynamicString" },
                "pattern": { "type": "string" }
              },
              "required": ["value", "pattern"],
              "unevaluatedProperties": false
            }
          },
          "required": ["call", "args"]
        }
      ],
      "unevaluatedProperties": false
    },
    "openUrl": {
      "type": "object",
      "returnType": "void",
      "requiresUserActivation": true,
      "allOf": [
        { "${'$'}ref": "https://a2ui.org/specification/v1_0/common_types.json#/${'$'}defs/FunctionCommon" },
        {
          "type": "object",
          "properties": {
            "call": { "const": "openUrl" },
            "args": {
              "type": "object",
              "properties": {
                "url": {
                  "oneOf": [
                    { "type": "string", "format": "uri" },
                    { "${'$'}ref": "https://a2ui.org/specification/v1_0/common_types.json#/${'$'}defs/DataBinding" }
                  ]
                }
              },
              "required": ["url"],
              "unevaluatedProperties": false
            }
          },
          "required": ["call", "args"]
        }
      ],
      "unevaluatedProperties": false
    }
  }
}
""".trimIndent()

private fun parse(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

private fun evaluator(limits: ValidationLimits = ValidationLimits.DEFAULT): SchemaEvaluator =
    SchemaEvaluator(
        SchemaRegistry.of(listOf(parse(COMMON_TYPES)), activeCatalog = parse(CATALOG)),
        limits,
    )

private val FUNCTION_CALL = SchemaLocation(
    "https://a2ui.org/specification/v1_0/common_types.json",
    "/\$defs/FunctionCall",
)

private fun check(payload: String, limits: ValidationLimits = ValidationLimits.DEFAULT): SchemaValidation {
    val common = parse(COMMON_TYPES)
    val schema = common.pointer("/\$defs/FunctionCall")!!
    return evaluator(limits).validate(schema, FUNCTION_CALL, Json.parseToJsonElement(payload))
}

class SchemaEvaluatorTest {
    @Test
    fun accepts_a_well_formed_call() {
        val result = check("""{"call": "regex", "args": {"value": "abc", "pattern": "^a"}}""")
        assertTrue(result.isValid, result.violations.toString())
        assertEquals(emptySet(), result.unsupportedKeywords)
    }

    @Test
    fun rejects_a_call_no_function_in_the_catalog_defines() {
        assertFalse(check("""{"call": "nope", "args": {}}""").isValid)
    }

    @Test
    fun rejects_a_missing_required_argument() {
        // `function_catalog_validation` #4: regex without a pattern.
        val result = check("""{"call": "regex", "args": {"value": "abc"}}""")
        assertFalse(result.isValid)
    }

    @Test
    fun rejects_an_argument_of_the_wrong_type() {
        // #26: a numeric pattern.
        assertFalse(check("""{"call": "regex", "args": {"value": "a", "pattern": 7}}""").isValid)
    }

    // --- unevaluatedProperties ------------------------------------------------------------

    @Test
    fun rejects_an_extra_key_on_the_call() {
        // `dynamic_value_validation` #8. The property names that make this pass for a conformant
        // call are contributed by three different subschemas — the enclosing object's own
        // `properties`, `FunctionCommon` through a `$ref`, and the matching `oneOf` branch's
        // `allOf` — so a checker that did not carry annotations across all three would either
        // reject every call or accept this one.
        val result = check("""{"call": "regex", "args": {"value": "a", "pattern": "^a"}, "extra": "field"}""")
        assertFalse(result.isValid)
        assertTrue(result.violations.any { "extra" in it.message }, result.violations.toString())
    }

    @Test
    fun accepts_a_key_only_a_referenced_schema_evaluates() {
        // `catalogId` is named by `FunctionCommon`, reached through `$ref` from inside `allOf`,
        // inside the `oneOf` branch. It is three applicators away from the
        // `unevaluatedProperties` that has to know about it.
        val result = check(
            """{"call": "regex", "catalogId": "x", "args": {"value": "a", "pattern": "^a"}}""",
        )
        assertTrue(result.isValid, result.violations.toString())
    }

    @Test
    fun rejects_an_extra_key_nested_inside_args() {
        val result = check(
            """{"call": "regex", "args": {"value": "a", "pattern": "^a", "extra": 1}}""",
        )
        assertFalse(result.isValid)
    }

    // --- the catalog.json placeholder -----------------------------------------------------

    @Test
    fun resolves_the_catalog_placeholder_to_the_active_catalog() {
        // `catalog.json#/$defs/anyFunction` resolved as a URI names a document that does not
        // exist — no catalog is published under `.../v1_0/catalog.json`, and the basic catalog's
        // own `$id` is `.../catalogs/basic/catalog.json`. Reaching a function definition at all
        // proves the placeholder was bound rather than resolved.
        assertTrue(check("""{"call": "regex", "args": {"value": "a", "pattern": "^a"}}""").isValid)
    }

    @Test
    fun reports_a_reference_it_cannot_resolve() {
        val registry = SchemaRegistry.of(listOf(parse(COMMON_TYPES)), activeCatalog = null)
        val schema = parse(COMMON_TYPES).pointer("/\$defs/FunctionCall")!!
        val result = SchemaEvaluator(registry)
            .validate(schema, FUNCTION_CALL, Json.parseToJsonElement("""{"call": "regex"}"""))
        // Not silently valid: an unresolvable reference means nothing checked the call.
        assertFalse(result.isValid)
        assertTrue(
            result.violations.any { "not a schema this renderer holds" in it.message },
            result.violations.toString(),
        )
    }

    // --- format ---------------------------------------------------------------------------

    @Test
    fun rejects_a_url_that_is_not_a_uri() {
        // #32. `format` is an annotation by default in 2020-12; treating it as one here would
        // accept this, because the alternative that catches it is `{"type": "string"}`.
        val result = check("""{"call": "openUrl", "args": {"url": "not a uri"}}""")
        assertFalse(result.isValid)
    }

    @Test
    fun accepts_a_url_that_is_a_uri() {
        assertTrue(
            check("""{"call": "openUrl", "args": {"url": "https://example.com/x"}}""").isValid,
        )
    }

    @Test
    fun accepts_a_binding_where_a_uri_is_allowed() {
        assertTrue(
            check("""{"call": "openUrl", "args": {"url": {"path": "/form/url"}}}""").isValid,
        )
    }

    @Test
    fun rejects_a_binding_carrying_an_extra_key() {
        // `dynamic_value_validation` #7, through `additionalProperties: false`.
        assertFalse(
            check("""{"call": "openUrl", "args": {"url": {"path": "/x", "extra": 1}}}""").isValid,
        )
    }

    // --- recursion and bounds ---------------------------------------------------------------

    @Test
    fun follows_a_call_nested_in_an_argument() {
        val nested = """
            {"call": "regex", "args": {
              "value": {"call": "regex", "args": {"value": "a", "pattern": "^a"}},
              "pattern": "^a"
            }}
        """.trimIndent()
        assertTrue(check(nested).isValid, check(nested).violations.toString())
    }

    @Test
    fun stops_on_an_instance_that_nests_past_the_depth_bound() {
        var payload = """{"call": "regex", "args": {"value": "a", "pattern": "^a"}}"""
        repeat(40) {
            payload = """{"call": "regex", "args": {"value": $payload, "pattern": "^a"}}"""
        }
        val result = check(payload, ValidationLimits(maxDepth = 16))
        assertFalse(result.isValid)
        assertTrue(result.truncated)
    }

    @Test
    fun stops_on_a_payload_that_outgrows_the_step_budget() {
        var payload = """{"call": "regex", "args": {"value": "a", "pattern": "^a"}}"""
        repeat(20) {
            payload = """{"call": "regex", "args": {"value": $payload, "pattern": "^a"}}"""
        }
        val result = check(payload, ValidationLimits(maxDepth = 512, maxSteps = 200))
        assertFalse(result.isValid)
        assertTrue(result.truncated)
        assertContains(result.violations.first().message, "steps")
    }

    @Test
    fun keeps_the_violation_when_explaining_a_deep_failure_runs_out() {
        // Explaining a failed `oneOf` re-runs its branches collecting, and collecting is what
        // turns the short-circuit off -- so the pass that produces the message costs what the
        // first pass avoids. Left to spend the whole budget it takes the answer with it: the
        // exhaustion unwinds past the violation it was about to report. What must survive is a
        // violation the agent can locate, rather than one about the renderer's own budget.
        var payload = """{"call": "regex", "args": {"value": "a", "pattern": 7}}"""
        repeat(3) {
            payload = """{"call": "regex", "args": {"value": $payload, "pattern": "^a"}}"""
        }
        val result = check(payload)
        assertFalse(result.isValid)
        assertTrue(
            result.violations.none { "steps" in it.message },
            "the budget answered instead of the payload: ${result.violations}",
        )
        assertTrue(
            result.violations.any { it.location.startsWith("/args") },
            "no violation names a place in the payload: ${result.violations}",
        )
    }

    // --- what the messages may carry ----------------------------------------------------------

    @Test
    fun never_quotes_a_value_read_from_the_instance() {
        // A renderer turns these into the `error` it sends the agent, so a quoted value goes back
        // over the wire. The failing value here is what a card number would be.
        val result = check("""{"call": "regex", "args": {"value": "a", "pattern": 4111111111111111}}""")
        assertFalse(result.isValid)
        assertTrue(
            result.violations.none { "4111111111111111" in it.message },
            result.violations.toString(),
        )
    }

    @Test
    fun locates_a_violation_by_json_pointer() {
        val result = check("""{"call": "regex", "args": {"value": "a", "pattern": 7}}""")
        assertTrue(
            result.violations.any { it.location == "/args/pattern" },
            result.violations.toString(),
        )
    }

    // --- coverage of the keyword subset -------------------------------------------------------

    @Test
    fun reports_a_keyword_it_does_not_apply() {
        val registry = SchemaRegistry.of(
            listOf(parse("""{"${'$'}id": "urn:t", "type": "string", "maxLength": 2}""")),
        )
        val schema = parse("""{"type": "string", "maxLength": 2}""")
        val result = SchemaEvaluator(registry)
            .validate(schema, SchemaLocation("urn:t", ""), Json.parseToJsonElement("\"abcdef\""))
        // Valid as far as this evaluator went, and it says so rather than implying it checked.
        assertTrue(result.isValid)
        assertContains(result.unsupportedKeywords, "maxLength")
    }
    @Test
    fun refuses_a_schema_whose_required_is_not_a_list_of_names() {
        // The schema is as agent-controlled as the instance once a catalog may be inlined, so a
        // `required` written wrongly must not read as "nothing is required".
        for (malformed in listOf(
            """{"type": "object", "required": "child"}""",
            """{"type": "object", "required": {"child": true}}""",
            """{"type": "object", "required": ["child", 7]}""",
        )) {
            val result = evaluator().validate(
                parse(malformed),
                FUNCTION_CALL,
                Json.parseToJsonElement("{}"),
            )
            assertFalse(result.isValid, malformed)
        }
        // The well-formed case still says which property is missing.
        val ok = evaluator().validate(
            parse("""{"type": "object", "required": ["child"]}"""),
            FUNCTION_CALL,
            Json.parseToJsonElement("{}"),
        )
        assertFalse(ok.isValid)
        assertContains(ok.violations.single().message, "`child` is required")
    }

    @Test
    fun a_rejected_property_name_is_not_quoted_back_to_the_agent() {
        // `propertyNames` is the one keyword whose subject is the key itself, so the key is
        // instance data: an object keyed by account number is exactly what it is written for.
        // A renderer turns this violation into the `error` it sends the agent.
        val result = evaluator().validate(
            parse("""{"propertyNames": {"pattern": "^[a-z]+${'$'}"}}"""),
            FUNCTION_CALL,
            Json.parseToJsonElement("""{"4111111111111111": null}"""),
        )
        assertFalse(result.isValid)
        for (violation in result.violations) {
            assertFalse(
                violation.message.contains("4111111111111111") ||
                    violation.location.contains("4111111111111111"),
                "the key reached the message: $violation",
            )
        }
    }

    @Test
    fun refuses_a_type_this_renderer_does_not_know() {
        // All seven JSON types are implemented, so a name outside them is a constraint written
        // wrongly -- and one that reads as satisfied is a constraint an agent deletes by
        // misspelling it.
        for (schema in listOf(
            """{"type": "strng"}""",
            """{"type": "Number"}""",
            """{"type": ["string", "nummber"]}""",
        )) {
            val result = evaluator().validate(
                parse(schema),
                FUNCTION_CALL,
                Json.parseToJsonElement("""{"anything": 1}"""),
            )
            assertFalse(result.isValid, schema)
            assertTrue(result.unsupportedKeywords.any { it.startsWith("type:") }, schema)
        }
    }

    @Test
    fun a_violation_cap_of_zero_does_not_turn_the_checker_off() {
        // The cap bounds how much is said, never the verdict. `ValidationLimits(maxViolations = 0)`
        // is a value the public constructor accepts.
        val result = SchemaEvaluator(
            SchemaRegistry.of(listOf(parse(COMMON_TYPES)), activeCatalog = parse(CATALOG)),
            ValidationLimits(maxViolations = 0),
        ).validate(parse("""{"type": "string"}"""), FUNCTION_CALL, Json.parseToJsonElement("5"))
        assertFalse(result.isValid, "the cap decided the verdict")
    }

    @Test
    fun minimum_compares_integers_as_integers() {
        // Routing both through `Double` makes these two the same number, so a bound just past the
        // range a `Double` can name would accept a value below it.
        val result = evaluator().validate(
            parse("""{"minimum": 9007199254740993}"""),
            FUNCTION_CALL,
            Json.parseToJsonElement("9007199254740992"),
        )
        assertFalse(result.isValid, "precision was lost through Double")
    }

    @Test
    fun scanning_an_array_for_duplicates_is_charged_to_the_budget() {
        // The scan is proportional to the array, and the array is the agent's to size, so an
        // uncharged pass over it is work the total budget cannot see.
        val entries = (0 until 500).joinToString(",")
        val result = SchemaEvaluator(
            SchemaRegistry.of(listOf(parse(COMMON_TYPES)), activeCatalog = parse(CATALOG)),
            ValidationLimits(maxSteps = 50),
        ).validate(
            parse("""{"uniqueItems": true}"""),
            FUNCTION_CALL,
            Json.parseToJsonElement("[" + entries + "]"),
        )
        assertTrue(result.truncated, "the scan was not charged")
    }

    @Test
    fun the_asserted_formats_check_more_than_the_shape() {
        // `DateTimeInput.min`/`max` in the published basic catalog assert these, and a renderer
        // hands whatever passes to a platform parser. `format` is an assertion here, not an
        // annotation, so the shape alone is not the rule.
        val valid = listOf(
            "date" to "2024-02-29",
            "date" to "2023-12-31",
            "time" to "23:59:60Z",
            "time" to "10:00:00+09:00",
            "date-time" to "2026-08-27T09:00:00Z",
            "uri" to "https://example.com/a%20b",
        )
        for ((name, text) in valid) {
            assertEquals(FormatVerdict.VALID, checkFormat(name, text), "$name: $text")
        }
        val invalid = listOf(
            "date" to "2023-02-29",
            "date" to "2024-13-01",
            "date" to "2024-04-31",
            "time" to "10:00:00",
            "time" to "24:00:00Z",
            "date-time" to "2024-02-31T00:00:00Z",
            "uri" to "https://exa mple.com",
        )
        for ((name, text) in invalid) {
            assertEquals(FormatVerdict.INVALID, checkFormat(name, text), "$name: $text")
        }
    }

}
