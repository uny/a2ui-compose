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
    fun anUnnamedArgumentIsRefused() {
        // The basic catalog's `formatString` description says arguments "must be named". Accepting
        // `${formatNumber(/count)}` would render here and fail on a conformant renderer.
        val one = assertFailsWith<A2uiFunctionException> {
            format("\${formatNumber(/count)}", """{"count":42}""")
        }
        assertTrue(one.message!!.contains("must be named"), one.message!!)
        val two = assertFailsWith<A2uiFunctionException> { format("\${formatNumber(1, 2)}") }
        assertTrue(two.message!!.contains("must be named"), two.message!!)
        // The named form is the one that works.
        assertEquals("42", format("\${formatNumber(value:/count)}", """{"count":42}"""))
    }

    @Test
    fun aCallNameIsJudgedByTheSameRuleAsACatalogName() {
        // #45: a name a catalog may declare must be a name a format string can call. Neither of
        // these is in the basic catalog, so both reach the evaluator and fail there as not
        // implemented -- which is the proof the parser let them through. Before this, `_helper`
        // failed here as "not a function name", because `_` is not a letter.
        val underscore = assertFailsWith<A2uiFunctionException> { format("\${_helper()}") }
        assertTrue(underscore.message!!.contains("no function named"), underscore.message!!)
        // A supplementary-plane letter, which a `Char`-at-a-time rule saw as two surrogates.
        val astral = assertFailsWith<A2uiFunctionException> { format("\${𝔥𝔢𝔩𝔭()}") }
        assertTrue(astral.message!!.contains("no function named"), astral.message!!)
        // A combining mark (U+0301, `Mn`) is `XID_Continue` and neither a letter nor a digit.
        val combining = assertFailsWith<A2uiFunctionException> { format("\${he\u0301lp()}") }
        assertTrue(combining.message!!.contains("no function named"), combining.message!!)
        // And the other direction: `ͺ` (U+037A) is `ID_Start` but not `XID_Start`, so a catalog
        // refuses it as a name and the parser now does too.
        val excluded = assertFailsWith<A2uiFunctionException> { format("\${ͺ()}") }
        assertTrue(excluded.message!!.contains("is not a function name"), excluded.message!!)
        val hyphen = assertFailsWith<A2uiFunctionException> { format("\${my-helper()}") }
        assertTrue(hyphen.message!!.contains("is not a function name"), hyphen.message!!)
    }

    @Test
    fun anArgumentNameIsJudgedByTheSameRuleAsACatalogName() {
        // `formatNumber` has no `_value` argument, so a parsed `_value:` fails as unrequired
        // rather than as "not an argument name" -- the parser accepted the name.
        val underscore = assertFailsWith<A2uiFunctionException> { format("\${formatNumber(_value:1)}") }
        assertTrue(underscore.message!!.contains("requires an argument"), underscore.message!!)
        // The two names that tell this rule from the earlier approximation: an astral letter it
        // refused, and `ͺ` (U+037A, not `XID_Start`) it accepted. An unrequired argument is
        // otherwise ignored, so the second is the one input that used to render and now fails.
        val astral = assertFailsWith<A2uiFunctionException> { format("\${formatNumber(𝔳:1)}") }
        assertTrue(astral.message!!.contains("requires an argument"), astral.message!!)
        val excluded = assertFailsWith<A2uiFunctionException> { format("\${formatNumber(ͺ:1)}") }
        assertTrue(excluded.message!!.contains("is not an argument name"), excluded.message!!)
        val hyphen = assertFailsWith<A2uiFunctionException> { format("\${formatNumber(my-value:1)}") }
        assertTrue(hyphen.message!!.contains("is not an argument name"), hyphen.message!!)
        val digit = assertFailsWith<A2uiFunctionException> { format("\${formatNumber(9x:1)}") }
        assertTrue(digit.message!!.contains("is not an argument name"), digit.message!!)
    }

    @Test
    fun aParserErrorIsAFunctionExceptionNamingFormatString() {
        // #4: each of these branches sits next to unguarded substring arithmetic. A regression that
        // turned one into an `IndexOutOfBoundsException` would escape as a type the evaluator's
        // KDoc tells renderers they need not catch, so the type is the assertion, not just the
        // message.
        val unclosed = assertFailsWith<A2uiFunctionException> { format("\${formatNumber(value:1}") }
        assertTrue(unclosed.message!!.contains("opens a call it does not close"), unclosed.message!!)
        assertEquals(FunctionNames.FORMAT_STRING, unclosed.call)
        val empty = assertFailsWith<A2uiFunctionException> { format("\${formatNumber(value:1,,decimals:2)}") }
        assertTrue(empty.message!!.contains("has an empty argument"), empty.message!!)
        assertEquals(FunctionNames.FORMAT_STRING, empty.call)
        val twice = assertFailsWith<A2uiFunctionException> { format("\${formatNumber(value:1,value:2)}") }
        assertTrue(twice.message!!.contains("names the argument `value` twice"), twice.message!!)
        assertEquals(FunctionNames.FORMAT_STRING, twice.call)
    }

    @Test
    fun aBareNestedCallDoesNotSplitTheArgumentList() {
        // `callsNestInsideOneAnother` nests through a quoted string, which `splitTop` skips as a
        // whole and never counts a parenthesis for. This is the other shape: the inner call's
        // comma and colon are inside its own parentheses, and only the counter keeps the outer
        // list from splitting there. Break it and `grouping:false), decimals:2` is argument two.
        assertEquals(
            "1,234.50",
            format("\${formatNumber(value:formatNumber(value:1234.5, grouping:false), decimals:2)}"),
        )
        // And with the nested call last, where a split inside it leaves an unclosed call behind.
        assertEquals(
            "1,234.50",
            format("\${formatNumber(decimals:2, value:formatNumber(value:1234.5, grouping:false))}"),
        )
    }

    @Test
    fun aQuotedLiteralHasNoEscapeTable() {
        // The specification defines quoting but no escape sequences, so `\n` is the letter n. An
        // escape table added here would pass every other test while making the same template mean
        // different things on different renderers.
        assertEquals("anb", format("\${'a\\nb'}"))
        assertEquals("a\\b", format("\${'a\\\\b'}"))
        // The escape does apply to the quote itself, in either quote style.
        assertEquals("it's", format("\${'it\\'s'}"))
        assertEquals("say \"hi\"", format("\${\"say \\\"hi\\\"\"}"))
        // A literal with no backslash at all takes the copy-free path.
        assertEquals("plain", format("\${\"plain\"}"))
    }

    @Test
    fun aNumberLiteralIsOnlyWhatJsonCallsANumber() {
        assertEquals("-3", format("\${-3}"))
        // Interpolated as the number, not as the spelling the agent used.
        assertEquals("1000", format("\${1e3}"))
        assertEquals("2.5", format("\${2.5}"))
        // Everything `toDoubleOrNull` accepts beyond JSON's grammar is a relative path instead, so
        // an agent whose data has a key of that name still reaches it. Drop the character filter
        // and each of these starts interpolating as a number.
        val data = """{"rows":[{"1d":"a","0x1p3":"b","Infinity":"c","NaN":"d","1e999":"e"}]}"""
        val scope = itemScope("/rows", 0)
        assertEquals("a", format("\${1d}", data, scope = scope))
        assertEquals("b", format("\${0x1p3}", data, scope = scope))
        assertEquals("c", format("\${Infinity}", data, scope = scope))
        assertEquals("d", format("\${NaN}", data, scope = scope))
        // Passes the character filter but overflows to infinity, which is not a JSON number either.
        assertEquals("e", format("\${1e999}", data, scope = scope))
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
