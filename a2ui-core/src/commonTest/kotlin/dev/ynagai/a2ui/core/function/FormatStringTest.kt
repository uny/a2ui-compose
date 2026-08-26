package dev.ynagai.a2ui.core.function

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.FunctionCall
import dev.ynagai.a2ui.core.surface.EvaluationScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `formatString(value: <template>)` over [data], evaluated in [scope]. */
private fun format(
    template: String,
    data: String = "{}",
    scope: EvaluationScope = EvaluationScope.Root,
    limits: EvaluationLimits = EvaluationLimits.DEFAULT,
): String {
    // Built as JSON rather than spliced into a string literal, so that a template containing a
    // quote or a backslash reaches the evaluator as written instead of breaking the payload.
    val wire = buildJsonObject {
        put("call", JsonPrimitive("formatString"))
        put("args", buildJsonObject { put("value", JsonPrimitive(template)) })
    }
    val call = A2uiJson.strict.decodeFromJsonElement(FunctionCall.serializer(), wire)
    return (context(data, scope = scope, limits = limits).evaluate(call) as JsonPrimitive).content
}

class FormatStringTest {

    @Test
    fun aTemplateWithNoExpressionsIsItsOwnValue() {
        assertEquals("Hello world", format("Hello world"))
    }

    @Test
    fun anAbsolutePathIsInterpolated() {
        assertEquals(
            "Hello Ada! Welcome back to A2UI.",
            format(
                "Hello \${/user/firstName}! Welcome back to \${/appName}.",
                """{"user":{"firstName":"Ada"},"appName":"A2UI"}""",
            ),
        )
    }

    @Test
    fun aRelativePathIsResolvedAgainstTheCollectionScope() {
        assertEquals(
            "Grace",
            format(
                "\${firstName}",
                """{"employees":[{"firstName":"Ada"},{"firstName":"Grace"}]}""",
                scope = itemScope("/employees", 1),
            ),
        )
    }

    @Test
    fun anEscapedMarkerIsALiteral() {
        assertEquals("Cost: \${total}", format("Cost: \\\${total}", """{"total":9}"""))
    }

    @Test
    fun anEscapedMarkerAndARealOneCoexist() {
        assertEquals("\${x} = 9", format("\\\${x} = \${/x}", """{"x":9}"""))
    }

    @Test
    fun typeConversionFollowsTheProtocol() {
        val data = """{"n":1.50,"b":true,"z":null,"o":{"a":1},"arr":[1,"x"]}"""
        assertEquals("1.50", format("\${/n}", data))
        assertEquals("true", format("\${/b}", data))
        assertEquals("", format("\${/z}", data))
        assertEquals("", format("\${/missing}", data))
        assertEquals("""{"a":1}""", format("\${/o}", data))
        assertEquals("""[1,"x"]""", format("\${/arr}", data))
    }

    @Test
    fun aFunctionCallIsInterpolatedWithNamedArguments() {
        assertEquals(
            "1,234.50",
            format("\${formatNumber(value:1234.5, decimals:2)}"),
        )
    }

    @Test
    fun anArgumentMayBeAnExplicitlyWrappedBinding() {
        assertEquals(
            "2026-08-26",
            format(
                "\${formatDate(value:\${/currentDate}, format:'yyyy-MM-dd')}",
                """{"currentDate":"2026-08-26T09:30:00Z"}""",
            ),
        )
    }

    @Test
    fun anArgumentMayBeABarePath() {
        assertEquals(
            "42",
            format("\${formatNumber(value:/count)}", """{"count":42}"""),
        )
    }

    @Test
    fun aQuotedArgumentMayContainTheDelimitersOfTheGrammar() {
        // `:` inside the pattern must not be read as the argument-name separator.
        assertEquals("09:30", format("\${formatDate(value:1756200600000, format:'HH:mm')}"))
    }

    @Test
    fun aQuotedArgumentMayContainACommaAndABrace() {
        // The `}` must not close the expression and the `,` must not split the argument list.
        // `\'` is formatString's escape; the pattern that reaches formatDate is `yy'},'yy`, whose
        // own single quotes are TR35's way of marking `},` as a literal.
        assertEquals(
            "25},25",
            format("\${formatDate(value:1756200600000, format:'yy\\'},\\'yy')}"),
        )
    }

    @Test
    fun callsNestInsideOneAnother() {
        assertEquals(
            "1,234.50 USD-ish",
            format("\${formatString(value:'\${formatNumber(value:1234.5, decimals:2)} USD-ish')}"),
        )
    }

    @Test
    fun aSingleUnnamedArgumentBindsToValue() {
        assertEquals("42", format("\${formatNumber(/count)}", """{"count":42}"""))
    }

    @Test
    fun moreThanOneUnnamedArgumentIsRefused() {
        assertFailsWith<A2uiFunctionException> { format("\${formatNumber(1, 2)}") }
    }

    @Test
    fun indexIsAvailableInsideATemplateItem() {
        assertEquals(
            "3. Ada",
            format(
                "\${@index(offset:3)}. \${name}",
                """{"rows":[{"name":"Ada"}]}""",
                scope = itemScope("/rows", 0),
            ),
        )
    }

    @Test
    fun indexInsideATemplateIsStillRefusedInTheRootScope() {
        assertFailsWith<A2uiFunctionException> { format("\${@index()}") }
    }

    @Test
    fun literalsAreReadAsThemselvesRatherThanAsPaths() {
        assertEquals("true", format("\${true}", """{"true":"shadowed"}"""))
        assertEquals("", format("\${null}"))
        assertEquals("7", format("\${7}"))
        assertEquals("a b", format("\${'a b'}"))
    }

    @Test
    fun anUnterminatedExpressionIsRefused() {
        val failure = assertFailsWith<A2uiFunctionException> { format("\${/a") }
        assertTrue(failure.message!!.contains("unterminated"))
    }

    @Test
    fun anEmptyExpressionIsRefused() {
        assertFailsWith<A2uiFunctionException> { format("\${}") }
    }

    @Test
    fun anExpressionThatIsNeitherLiteralPathNorCallIsRefused() {
        assertFailsWith<A2uiFunctionException> { format("\${first name}") }
    }

    @Test
    fun anUnimplementedFunctionInsideATemplateIsRefused() {
        assertFailsWith<A2uiFunctionException> { format("\${now()}") }
    }

    @Test
    fun aResultLongerThanTheBoundIsRefusedRatherThanBuilt() {
        val data = """{"big":"${"x".repeat(1000)}"}"""
        val template = "\${/big}".repeat(100)
        val failure = assertFailsWith<A2uiFunctionException> {
            format(template, data, limits = EvaluationLimits(maxResultLength = 5_000))
        }
        assertTrue(failure.message!!.contains("exceeds"))
    }

    @Test
    fun expressionsNestedPastTheBoundAreRefused() {
        val limits = EvaluationLimits(maxDepth = 3)
        var expression = "'x'"
        repeat(6) { expression = "\${$expression}" }
        assertFailsWith<A2uiFunctionException> { format(expression, limits = limits) }
    }

    @Test
    fun aPathResolvingToAnObjectShapedLikeABindingIsNotFollowedTwice() {
        // The parser has already resolved this argument; re-reading it as a `DataBinding` would
        // dereference `/decoy` a second time and produce "found" instead of the object itself.
        val data = """{"holder":{"path":"/decoy"},"decoy":"found"}"""
        assertEquals("""{"path":"/decoy"}""", format("\${/holder}", data))
    }
}
