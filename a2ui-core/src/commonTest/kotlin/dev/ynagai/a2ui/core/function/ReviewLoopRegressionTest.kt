package dev.ynagai.a2ui.core.function

import dev.ynagai.a2ui.core.protocol.A2uiFormatException
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.FunctionCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.ynagai.a2ui.core.surface.EvaluationScope
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour pinned by the review loop on this PR. Each test names the defect it prevents from
 * coming back; several of them are the *cross-target* half of a rule, so they are written in
 * `commonTest` deliberately — passing on the JVM alone would not have caught the original.
 */
/** `formatString(value: <template>)` — a local copy, since FormatStringTest's is file-private. */
private fun format(
    template: String,
    data: String = "{}",
    scope: EvaluationScope = EvaluationScope.Root,
): String {
    val wire = buildJsonObject {
        put("call", JsonPrimitive("formatString"))
        put("args", buildJsonObject { put("value", JsonPrimitive(template)) })
    }
    val call = A2uiJson.strict.decodeFromJsonElement(FunctionCall.serializer(), wire)
    return (context(data, scope = scope).evaluate(call) as JsonPrimitive).content
}

class ReviewLoopRegressionTest {

    private fun emailValid(value: String): Boolean? {
        val wire = Json.parseToJsonElement("""{"call":"email","args":{"value":${JsonPrimitive(value)}}}""")
        val result = context("{}").evaluate(
            A2uiJson.strict.decodeFromJsonElement(FunctionCall.serializer(), wire),
        ) as JsonObject
        return (result["valid"] as JsonPrimitive).booleanOrNull
    }

    @Test
    fun emailTreatsUnicodeWhitespaceTheSameOnEveryTarget() {
        // `\s` is ASCII-only on the JVM and Unicode on ECMAScript, so writing it literally made
        // these two addresses valid on JVM/Native and invalid on JS/Wasm from one payload.
        assertEquals(false, emailValid("a\u00A0b@c.de"), "no-break space")
        assertEquals(false, emailValid("a\u2003b@c.de"), "em space")
        assertEquals(false, emailValid("a\u3000b@c.de"), "ideographic space")
        assertEquals(false, emailValid("a\uFEFFb@c.de"), "zero-width no-break space")
        assertEquals(true, emailValid("ada@example.com"), "an ordinary address still passes")
    }

    @Test
    fun indexDoesNotResolveItsOffsetTwiceInsideATemplate() {
        // `/holder` holds an object shaped like a binding. Every other function rejects it;
        // `@index` used to follow it to `/n` and answer 5.
        val data = """{"rows":[{}],"holder":{"path":"/n"},"n":5}"""
        val failure = assertFailsWith<A2uiFunctionException> {
            format("\${@index(offset:/holder)}", data, scope = itemScope("/rows", 0))
        }
        assertTrue(failure.message!!.contains("must be a number"), failure.message!!)
    }

    @Test
    fun indexStillWorksInsideATemplateWithARealOffset() {
        assertEquals(
            "3",
            format("\${@index(offset:3)}", """{"rows":[{}]}""", scope = itemScope("/rows", 0)),
        )
    }

    @Test
    fun aMalformedBindingArgumentIsAFunctionFailureNotASerializationError() {
        // `FunctionCall.args` is not schema-checked at parse time, so this is the first place a
        // malformed binding is read; the strict decoder's own exception is not one of the three
        // types this module documents.
        val failure = assertFailsWith<A2uiFunctionException> {
            context("{}").evaluate(call("""{"call":"required","args":{"value":{"path":"/x","extra":1}}}"""))
        }
        assertTrue(failure.message!!.contains("not a valid binding or call"), failure.message!!)
        assertFailsWith<A2uiFunctionException> {
            context("{}").evaluate(call("""{"call":"required","args":{"value":{"path":5}}}"""))
        }
    }

    @Test
    fun theDeliberateFormatFailureIsStillAFormatFailure() {
        // `A2uiFormatException` extends `SerializationException`, so wrapping the latter must not
        // swallow the former: an object carrying both `path` and `call` is a malformed payload,
        // which a renderer classifies by catching `A2uiFormatException`.
        assertFailsWith<A2uiFormatException> {
            context("{}").evaluate(
                call("""{"call":"required","args":{"value":{"path":"/x","call":"required"}}}"""),
            )
        }
    }

    @Test
    fun lengthWithOnlyAMaximumDoesNotCallAnAbsentValueTooShort() {
        // An optional field capped at 200 characters must not report TOO_SHORT before the user
        // types — the empty string it becomes a moment later already passes.
        val absent = context("{}").evaluate(
            call("""{"call":"length","args":{"value":{"path":"/form/notes"},"max":200}}"""),
        )
        assertEquals("""{"valid":true}""", absent.toString())
        // An explicit `min: 0` must agree with the empty string, which passes it.
        assertEquals(
            """{"valid":true}""",
            context("{}").evaluate(
                call("""{"call":"length","args":{"value":{"path":"/x"},"min":0,"max":200}}"""),
            ).toString(),
        )
        assertEquals(
            """{"valid":true}""",
            context("{}").evaluate(
                call("""{"call":"length","args":{"value":"","min":0,"max":200}}"""),
            ).toString(),
        )
        // A declared minimum still fails, and still says why.
        val withMin = context("{}").evaluate(
            call("""{"call":"length","args":{"value":{"path":"/form/name"},"min":1}}"""),
        )
        assertEquals("""{"valid":false,"code":"TOO_SHORT"}""", withMin.toString())
    }

    @Test
    fun openUrlRunsAtMostOncePerUserInteraction() {
        val opened = mutableListOf<String>()
        val ctx = context(
            "{}",
            invocation = InvocationContext.USER_ACTION,
            urlOpener = { opened += it },
        )
        val template = "\${openUrl(url:'https://example.com/a')}\${openUrl(url:'https://example.com/b')}"
        val wire = A2uiJson.strict.decodeFromJsonElement(
            FunctionCall.serializer(),
            Json.parseToJsonElement("""{"call":"formatString","args":{"value":${JsonPrimitive(template)}}}"""),
        )
        assertFailsWith<A2uiFunctionException> { ctx.evaluate(wire) }
        assertEquals(listOf("https://example.com/a"), opened, "the second open must not happen")
    }

    @Test
    fun errorMessagesDoNotQuoteTheContentOfTheDataModel() {
        val secret = "4111111111111111-and-more"
        val failure = assertFailsWith<A2uiFunctionException> {
            context("""{"form":{"card":"$secret"}}""").evaluate(
                call("""{"call":"formatNumber","args":{"value":{"path":"/form/card"}}}"""),
            )
        }
        assertTrue(failure.message!!.contains("must be a number"), failure.message!!)
        assertTrue(!failure.message!!.contains("4111"), "field content must not reach the message")
    }

    @Test
    fun errorMessagesDoNotCarryUnboundedAgentText() {
        val huge = "A".repeat(50_000)
        val failure = assertFailsWith<A2uiFunctionException> { format("\${${huge}1()}") }
        assertTrue(failure.message!!.length < 1_000, "message was ${failure.message!!.length} chars")
        // A JSON number keeps its raw literal, so the non-string branch of `describe` needs the
        // same treatment as the string branch.
        val digits = "9".repeat(50_000)
        val fromLiteral = assertFailsWith<A2uiFunctionException> {
            context("{}").evaluate(call("""{"call":"not","args":{"value":$digits}}"""))
        }
        assertTrue(
            fromLiteral.message!!.length < 1_000,
            "message was ${fromLiteral.message!!.length} chars",
        )
    }

    @Test
    fun pluralizeUsesTheMagnitudeAsCldrDoes() {
        assertEquals(
            "\"item\"",
            context("{}").evaluate(
                call("""{"call":"pluralize","args":{"value":-1,"one":"item","other":"items"}}"""),
            ).toString(),
        )
    }

    @Test
    fun anOffsetWithAMisplacedColonIsRejected() {
        assertNull(parseIso8601("1970-01-01T00:00+0:000"))
        assertNull(parseIso8601("1970-01-01T00:00+000:0"))
        // The three legal forms still parse.
        assertEquals(0L, parseIso8601("1970-01-01T00:00+00:00"))
        assertEquals(0L, parseIso8601("1970-01-01T00:00+0000"))
        assertEquals(0L, parseIso8601("1970-01-01T00:00+00"))
    }

    @Test
    fun theEscapeRuleIsWhatItsCommentSays() {
        assertEquals("C:\${x}", format("C:\\\${x}", """{"x":9}"""))
        assertEquals("C:\\\${x}", format("C:\\\\\${x}", """{"x":9}"""))
    }
}

/** Round 2: the bounds, and the two formatting defects the bounds work uncovered. */
class ReviewLoopBoundsTest {

    @Test
    fun theResultBudgetSpansTheWholeEvaluationNotOneStringAtATime() {
        // Two sibling arguments, each interpolating a value that fits the budget on its own. Both
        // are live at once while the argument map is built, so the total is what must be bounded.
        val half = "x".repeat(600)
        val data = """{"big":"$half"}"""
        val template = "\${formatString(value:'ok',a:formatString(value:/big),b:formatString(value:/big))}"
        val failure = assertFailsWith<A2uiFunctionException> {
            val wire = buildJsonObject {
                put("call", JsonPrimitive("formatString"))
                put("args", buildJsonObject { put("value", JsonPrimitive(template)) })
            }
            context(data, limits = EvaluationLimits(maxResultLength = 1_000)).evaluate(
                A2uiJson.strict.decodeFromJsonElement(FunctionCall.serializer(), wire),
            )
        }
        assertTrue(failure.message!!.contains("maximum result length"), failure.message!!)
    }

    @Test
    fun theResultBudgetCoversFormattingFunctionsAndNotOnlyFormatString() {
        val failure = assertFailsWith<A2uiFunctionException> {
            context("{}", limits = EvaluationLimits(maxResultLength = 4)).evaluate(
                call("""{"call":"formatNumber","args":{"value":1000000}}"""),
            )
        }
        assertTrue(failure.message!!.contains("maximum result length"), failure.message!!)
    }

    @Test
    fun anArgumentListIsBoundedByTheStepBudget() {
        val body = (0 until 200).joinToString(",") { "a$it:'x'" }
        val failure = assertFailsWith<A2uiFunctionException> {
            val wire = buildJsonObject {
                put("call", JsonPrimitive("formatString"))
                put("args", buildJsonObject { put("value", JsonPrimitive("\${formatString($body)}")) })
            }
            context("{}", limits = EvaluationLimits(maxSteps = 20)).evaluate(
                A2uiJson.strict.decodeFromJsonElement(FunctionCall.serializer(), wire),
            )
        }
        assertTrue(failure.message!!.contains("evaluations"), failure.message!!)
    }

    @Test
    fun theRegexSubjectBoundStaysUnderTheStackDepthAnOrdinaryPatternNeeds() {
        // `(a|b)*` is not a pathological pattern; a backtracking engine still recurses once per
        // character for it, and at the old 64 KiB bound that was a StackOverflowError.
        val subject = "a".repeat(DEFAULT_MAX_SUBJECT_LENGTH)
        val result = context("{}").evaluate(
            call("""{"call":"regex","args":{"pattern":"(a|b)*","value":${JsonPrimitive(subject)}}}"""),
        )
        assertEquals("""{"valid":true}""", result.toString())
        val failure = assertFailsWith<A2uiFunctionException> {
            context("{}").evaluate(
                call(
                    """{"call":"regex","args":{"pattern":"(a|b)*","value":""" +
                        "${JsonPrimitive("a".repeat(DEFAULT_MAX_SUBJECT_LENGTH + 1))}}}",
                ),
            )
        }
        assertTrue(failure.message!!.contains("exceeds the maximum"), failure.message!!)
    }

    @Test
    fun aMagnitudeTooLargeToRoundIsStillWrittenOutInFull() {
        // Not `1.2345678901234567E14`, and the same string on every target.
        assertEquals(
            """"USD 123,456,789,012,345.67"""",
            context("{}").evaluate(
                call("""{"call":"formatCurrency","args":{"value":123456789012345.67,"currency":"USD"}}"""),
            ).toString(),
        )
        assertEquals(
            """"10,000,000,000,000,000"""",
            context("{}").evaluate(
                call("""{"call":"formatNumber","args":{"value":1e16,"decimals":0}}"""),
            ).toString(),
        )
    }
}

/**
 * Round 3: progressive rendering. `a2ui_protocol.md:838` requires a renderer to tolerate a data
 * path that resolves to `undefined` because its `updateDataModel` has not arrived yet.
 */
class ProgressiveRenderingTest {

    private fun evaluate(wire: String, data: String = "{}") =
        context(data).evaluate(call(wire)).toString()

    @Test
    fun theFormattingFunctionsAnswerWithNullWhenTheirValueHasNotArrived() {
        assertEquals("null", evaluate("""{"call":"formatNumber","args":{"value":{"path":"/cart/total"}}}"""))
        assertEquals(
            "null",
            evaluate("""{"call":"formatCurrency","args":{"value":{"path":"/x"},"currency":"USD"}}"""),
        )
        assertEquals(
            "null",
            evaluate("""{"call":"formatDate","args":{"value":{"path":"/x"},"format":"yyyy"}}"""),
        )
        assertEquals(
            "null",
            evaluate("""{"call":"pluralize","args":{"value":{"path":"/x"},"other":"items"}}"""),
        )
        assertEquals("null", evaluate("""{"call":"formatString","args":{"value":{"path":"/x"}}}"""))
    }

    @Test
    fun anAbsentValueInterpolatesAsTheEmptyStringLikeABarePath() {
        // The whole point of answering with null rather than "": inside a template it renders as
        // "" anyway, so the two spellings of the same absent data now agree.
        assertEquals("n=", format("n=\${/cart/total}"))
        assertEquals("n=", format("n=\${formatNumber(value:/cart/total)}"))
    }

    @Test
    fun aFormatSelectingArgumentStaysStrict() {
        // Tolerating these would render an amount whose currency is unknown, or a date with no
        // pattern — confidently wrong rather than visibly absent.
        assertFailsWith<A2uiFunctionException> {
            evaluate("""{"call":"formatCurrency","args":{"value":5,"currency":{"path":"/x"}}}""")
        }
        assertFailsWith<A2uiFunctionException> {
            evaluate("""{"call":"formatDate","args":{"value":0,"format":{"path":"/x"}}}""")
        }
    }

    @Test
    fun aCheckWhoseConditionHasNotArrivedIsNotYetAFailure() {
        val condition = A2uiJson.strict.decodeFromJsonElement(
            dev.ynagai.a2ui.core.protocol.DataBinding.serializer(),
            Json.parseToJsonElement("""{"path":"/checks/card"}"""),
        )
        assertEquals(true, context("{}").evaluateCheck(condition).valid)
        // A result that has arrived is still read as written.
        assertEquals(
            false,
            context("""{"checks":{"card":{"valid":false,"code":"X"}}}""")
                .evaluateCheck(condition).valid,
        )
    }

    @Test
    fun aValueThatIsPresentIsStillFormatted() {
        assertEquals(""""1,234.50"""", evaluate("""{"call":"formatNumber","args":{"value":1234.5,"decimals":2}}"""))
    }
}
