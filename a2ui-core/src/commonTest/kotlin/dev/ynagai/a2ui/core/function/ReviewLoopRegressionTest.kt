package dev.ynagai.a2ui.core.function

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
    fun lengthWithOnlyAMaximumDoesNotCallAnAbsentValueTooShort() {
        // An optional field capped at 200 characters must not report TOO_SHORT before the user
        // types — the empty string it becomes a moment later already passes.
        val absent = context("{}").evaluate(
            call("""{"call":"length","args":{"value":{"path":"/form/notes"},"max":200}}"""),
        )
        assertEquals("""{"valid":true}""", absent.toString())
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
