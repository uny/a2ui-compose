package dev.ynagai.a2ui.core.function

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.DataBinding
import dev.ynagai.a2ui.core.protocol.FunctionCall
import dev.ynagai.a2ui.core.protocol.Severity
import dev.ynagai.a2ui.core.surface.EvaluationScope
import dev.ynagai.a2ui.core.surface.JsonPointer
import dev.ynagai.a2ui.core.surface.iterate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds an [EvaluationContext] over [data] with the fallback formatter and default bounds. */
internal fun context(
    data: String = "{}",
    scope: EvaluationScope = EvaluationScope.Root,
    invocation: InvocationContext = InvocationContext.RENDER,
    urlOpener: UrlOpener? = null,
    limits: EvaluationLimits = EvaluationLimits.DEFAULT,
): EvaluationContext = EvaluationContext(Json.parseToJsonElement(data))
    .inScope(scope)
    .withInvocation(invocation)
    .withUrlOpener(urlOpener)
    .withLimits(limits)

/** Reads a wire-form [FunctionCall] the way a payload would carry it. */
internal fun call(wire: String): FunctionCall =
    A2uiJson.strict.decodeFromString(FunctionCall.serializer(), wire)

internal fun json(text: String): JsonElement = Json.parseToJsonElement(text)

/** The scope one item deep into a template over [path]. */
internal fun itemScope(path: String, index: Int): EvaluationScope =
    EvaluationScope.Root.iterate(JsonPointer.parse(path), index)

class FunctionEvaluatorTest {

    // ---- required ---------------------------------------------------------------------

    @Test
    fun requiredAcceptsANonEmptyString() {
        assertEquals(
            json("""{"valid":true}"""),
            context().evaluate(call("""{"call":"required","args":{"value":"a"}}""")),
        )
    }

    @Test
    fun requiredRejectsTheEmptyStringWithACode() {
        assertEquals(
            json("""{"valid":false,"code":"REQUIRED"}"""),
            context().evaluate(call("""{"call":"required","args":{"value":""}}""")),
        )
    }

    @Test
    fun requiredRejectsAnEmptyArrayButAcceptsANonEmptyOne() {
        val evaluator = context()
        assertEquals(
            false,
            evaluator.valid(call("""{"call":"required","args":{"value":[]}}""")),
        )
        assertEquals(
            true,
            evaluator.valid(call("""{"call":"required","args":{"value":[0]}}""")),
        )
    }

    @Test
    fun requiredAcceptsZeroAndFalseWhichAreNotEmptiness() {
        val evaluator = context()
        assertEquals(true, evaluator.valid(call("""{"call":"required","args":{"value":0}}""")))
        assertEquals(true, evaluator.valid(call("""{"call":"required","args":{"value":false}}""")))
    }

    @Test
    fun requiredRejectsAValueThatIsMissingFromTheDataModel() {
        val evaluator = context("""{"user":{}}""")
        assertEquals(
            false,
            evaluator.valid(call("""{"call":"required","args":{"value":{"path":"/user/name"}}}""")),
        )
    }

    @Test
    fun anAbsentArgumentIsACallFailureRatherThanAnInvalidResult() {
        val failure = assertFailsWith<A2uiFunctionException> {
            context().evaluate(call("""{"call":"required","args":{}}"""))
        }
        assertEquals("required", failure.call)
    }

    // ---- regex ------------------------------------------------------------------------

    @Test
    fun regexSearchesRatherThanMatchingTheWholeString() {
        val evaluator = context()
        val partial = """{"call":"regex","args":{"value":"abc123","pattern":"[0-9]+"}}"""
        assertEquals(true, evaluator.valid(call(partial)))
        val anchored = """{"call":"regex","args":{"value":"abc123","pattern":"^[0-9]+$"}}"""
        assertEquals(false, evaluator.valid(call(anchored)))
    }

    @Test
    fun regexReportsAnUnusableValueAsInvalid() {
        val evaluator = context("{}")
        val wire = """{"call":"regex","args":{"value":{"path":"/nope"},"pattern":"x"}}"""
        assertEquals(
            json("""{"valid":false,"code":"PATTERN_MISMATCH"}"""),
            evaluator.evaluate(call(wire)),
        )
    }

    @Test
    fun regexRefusesAPatternLongerThanTheBound() {
        val pattern = "a".repeat(EvaluationLimits.DEFAULT.maxPatternLength + 1)
        val failure = assertFailsWith<A2uiFunctionException> {
            context().evaluate(call("""{"call":"regex","args":{"value":"a","pattern":"$pattern"}}"""))
        }
        assertTrue(failure.message!!.contains("exceeds the maximum"))
    }

    @Test
    fun regexReportsAnUncompilablePatternAsACallFailure() {
        assertFailsWith<A2uiFunctionException> {
            context().evaluate(call("""{"call":"regex","args":{"value":"a","pattern":"[a-"}}"""))
        }
    }

    // ---- length -----------------------------------------------------------------------

    @Test
    fun lengthChecksBothEndsInclusively() {
        val evaluator = context()
        fun check(value: String) =
            evaluator.evaluate(call("""{"call":"length","args":{"value":"$value","min":2,"max":4}}"""))
        assertEquals(json("""{"valid":false,"code":"TOO_SHORT"}"""), check("a"))
        assertEquals(json("""{"valid":true}"""), check("ab"))
        assertEquals(json("""{"valid":true}"""), check("abcd"))
        assertEquals(json("""{"valid":false,"code":"TOO_LONG"}"""), check("abcde"))
    }

    @Test
    fun lengthNeedsABound() {
        assertFailsWith<A2uiFunctionException> {
            context().evaluate(call("""{"call":"length","args":{"value":"a"}}"""))
        }
    }

    @Test
    fun lengthCountsUtf16CodeUnitsAsJavaScriptDoes() {
        // A single astral character is two code units, so `max: 1` rejects it.
        val evaluator = context()
        val wire = """{"call":"length","args":{"value":"😀","max":1}}"""
        assertEquals(false, evaluator.valid(call(wire)))
    }

    // ---- numeric ----------------------------------------------------------------------

    @Test
    fun numericChecksTheRange() {
        val evaluator = context()
        fun check(value: String) =
            evaluator.valid(call("""{"call":"numeric","args":{"value":$value,"min":1,"max":10}}"""))
        assertEquals(false, check("0"))
        assertEquals(true, check("1"))
        assertEquals(true, check("10"))
        assertEquals(false, check("11"))
    }

    @Test
    fun numericParsesAStringTheWayATextFieldWritesItBack() {
        val evaluator = context("""{"form":{"age":" 42 "}}""")
        val wire = """{"call":"numeric","args":{"value":{"path":"/form/age"},"min":0}}"""
        assertEquals(true, evaluator.valid(call(wire)))
    }

    @Test
    fun numericReportsAnUnparseableValueAsInvalid() {
        val evaluator = context("""{"form":{"age":"old"}}""")
        val wire = """{"call":"numeric","args":{"value":{"path":"/form/age"},"min":0}}"""
        assertEquals(
            json("""{"valid":false,"code":"NOT_A_NUMBER"}"""),
            evaluator.evaluate(call(wire)),
        )
    }

    // ---- email ------------------------------------------------------------------------

    @Test
    fun emailFollowsTheGuidesPattern() {
        val evaluator = context()
        fun check(value: String) =
            evaluator.valid(call("""{"call":"email","args":{"value":"$value"}}"""))
        assertEquals(true, check("a@b.co"))
        assertEquals(false, check("a@b"))
        assertEquals(false, check("a b@c.de"))
        assertEquals(false, check("@b.co"))
    }

    // ---- logic ------------------------------------------------------------------------

    @Test
    fun andAndOrFoldTheirValues() {
        val evaluator = context()
        val both = """{"call":"and","args":{"values":[true,true]}}"""
        val mixedAnd = """{"call":"and","args":{"values":[true,false]}}"""
        val mixedOr = """{"call":"or","args":{"values":[false,true]}}"""
        val neither = """{"call":"or","args":{"values":[false,false]}}"""
        assertEquals(JsonPrimitive(true), evaluator.evaluate(call(both)))
        assertEquals(JsonPrimitive(false), evaluator.evaluate(call(mixedAnd)))
        assertEquals(JsonPrimitive(true), evaluator.evaluate(call(mixedOr)))
        assertEquals(JsonPrimitive(false), evaluator.evaluate(call(neither)))
    }

    @Test
    fun andShortCircuitsBeforeEvaluatingAFailingArgument() {
        // The second value would raise if it were reached; the fold must not reach it.
        val wire = """{"call":"and","args":{"values":[false,{"call":"noSuchFunction"}]}}"""
        assertEquals(JsonPrimitive(false), context().evaluate(call(wire)))
    }

    @Test
    fun orShortCircuitsBeforeEvaluatingAFailingArgument() {
        val wire = """{"call":"or","args":{"values":[true,{"call":"noSuchFunction"}]}}"""
        assertEquals(JsonPrimitive(true), context().evaluate(call(wire)))
    }

    @Test
    fun logicAcceptsAnArrayThatArrivesThroughABinding() {
        val evaluator = context("""{"flags":[true,false]}""")
        val wire = """{"call":"and","args":{"values":{"path":"/flags"}}}"""
        assertEquals(JsonPrimitive(false), evaluator.evaluate(call(wire)))
    }

    @Test
    fun notNegates() {
        assertEquals(
            JsonPrimitive(false),
            context().evaluate(call("""{"call":"not","args":{"value":true}}""")),
        )
    }

    @Test
    fun callsNestInsideArguments() {
        val wire = """
            {"call":"not","args":{"value":{"call":"or","args":{"values":[false,false]}}}}
        """.trimIndent()
        assertEquals(JsonPrimitive(true), context().evaluate(call(wire)))
    }

    @Test
    fun aNonBooleanValueIsACallFailure() {
        assertFailsWith<A2uiFunctionException> {
            context().evaluate(call("""{"call":"not","args":{"value":"true"}}"""))
        }
    }

    @Test
    fun logicReadsAValidationResultAsItsValidity() {
        // The specification contradicts itself here and its own examples pick a side.
        // `catalog.json` types `required`/`email`/`regex`/`length`/`numeric` as
        // `"returnType": "validationResult"` — the object this evaluator builds — while the basic
        // catalog implementation guide's prose for the same five says each returns `true` or
        // `false`, and `and`/`or`/`not` are typed `boolean` under either reading. Both
        // `09_login-form` and `32_advanced-form-validator` then write
        // `and(values: [email(...), length(...)])`, nesting one inside the other. Refusing that
        // would fail the payloads the specification ships to demonstrate `checks`.
        val evaluator = context("""{"email":"ada@example.com","blank":""}""")
        val passing = """
            {"call":"and","args":{"values":[
              {"call":"email","args":{"value":{"path":"/email"}}},
              {"call":"required","args":{"value":{"path":"/email"}}}
            ]}}
        """.trimIndent()
        val failing = """
            {"call":"and","args":{"values":[
              {"call":"email","args":{"value":{"path":"/email"}}},
              {"call":"required","args":{"value":{"path":"/blank"}}}
            ]}}
        """.trimIndent()
        assertEquals(JsonPrimitive(true), evaluator.evaluate(call(passing)))
        assertEquals(JsonPrimitive(false), evaluator.evaluate(call(failing)))
        // `or` and `not` fold through the same reading, so neither can drift from `and`.
        val eitherWay = """
            {"call":"not","args":{"value":{"call":"required","args":{"value":{"path":"/blank"}}}}}
        """.trimIndent()
        assertEquals(JsonPrimitive(true), evaluator.evaluate(call(eitherWay)))
    }

    @Test
    fun onlyTheLogicFunctionsReadAValidationResultAsABoolean() {
        // The leniency is about one contradiction in how a validity is spelled, not a truthiness
        // for the evaluator at large: an object where an ordinary boolean argument belongs is
        // still the call failure it was.
        val evaluator = context("""{"result":{"valid":true}}""")
        assertFailsWith<A2uiFunctionException> {
            evaluator.evaluate(
                call("""{"call":"formatNumber","args":{"value":1000,"grouping":{"path":"/result"}}}"""),
            )
        }
        // And an object that is not a validation result at all reaches the same refusal from
        // inside `and`, rather than being read as some other kind of truth.
        assertFailsWith<A2uiFunctionException> {
            evaluator.evaluate(call("""{"call":"and","args":{"values":[{"path":"/nothing"},true]}}"""))
        }
    }

    // ---- @index -----------------------------------------------------------------------

    @Test
    fun indexReturnsThePositionOfTheItemBeingRendered() {
        val evaluator = context("""{"rows":[1,2,3]}""", scope = itemScope("/rows", 2))
        assertEquals(JsonPrimitive(2), evaluator.evaluate(call("""{"call":"@index"}""")))
    }

    @Test
    fun indexAddsItsOffset() {
        val evaluator = context("""{"rows":[1,2,3]}""", scope = itemScope("/rows", 0))
        assertEquals(
            JsonPrimitive(1),
            evaluator.evaluate(call("""{"call":"@index","args":{"offset":1}}""")),
        )
    }

    @Test
    fun indexOutsideACollectionScopeIsAnError() {
        val failure = assertFailsWith<A2uiFunctionException> {
            context("""{"rows":[1]}""").evaluate(call("""{"call":"@index"}"""))
        }
        assertTrue(failure.message!!.contains("list template"))
    }

    @Test
    fun indexTakesNoCatalogId() {
        val evaluator = context("""{"rows":[1]}""", scope = itemScope("/rows", 0))
        assertFailsWith<A2uiFunctionException> {
            evaluator.evaluate(call("""{"call":"@index","catalogId":"basic"}"""))
        }
    }

    @Test
    fun indexReadsTheInnermostIterationWhenTemplatesNest() {
        val outer = EvaluationScope.Root.iterate(JsonPointer.parse("/rows"), 1)
        val inner = outer.iterate(JsonPointer.parse("cells"), 3)
        val evaluator = context("""{"rows":[{"cells":[]},{"cells":[0,1,2,3]}]}""", scope = inner)
        assertEquals(JsonPrimitive(3), evaluator.evaluate(call("""{"call":"@index"}""")))
    }

    // ---- bindings ---------------------------------------------------------------------

    @Test
    fun aRelativeBindingIsMeasuredFromTheItemScope() {
        val data = """{"employees":[{"name":"Ada"},{"name":"Grace"}]}"""
        val evaluator = context(data, scope = itemScope("/employees", 1))
        assertEquals(JsonPrimitive("Grace"), evaluator.evaluate(DataBinding("name")))
    }

    @Test
    fun aBindingThatReachesNothingIsNullRatherThanAnError() {
        assertEquals(JsonNull, context("""{"a":1}""").evaluate(DataBinding("/b/c")))
    }

    // ---- openUrl ----------------------------------------------------------------------

    @Test
    fun openUrlIsRefusedWhileRendering() {
        val opened = mutableListOf<String>()
        val evaluator = context(invocation = InvocationContext.RENDER, urlOpener = opened::add)
        assertFailsWith<A2uiFunctionException> {
            evaluator.evaluate(call("""{"call":"openUrl","args":{"url":"https://example.com"}}"""))
        }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun openUrlDelegatesOnAUserAction() {
        val opened = mutableListOf<String>()
        val evaluator = context(invocation = InvocationContext.USER_ACTION, urlOpener = opened::add)
        val result =
            evaluator.evaluate(call("""{"call":"openUrl","args":{"url":"https://example.com/x"}}"""))
        assertEquals(JsonNull, result)
        assertEquals(listOf("https://example.com/x"), opened)
    }

    @Test
    fun openUrlRefusesASchemeOutsideTheAllowlist() {
        val opened = mutableListOf<String>()
        val evaluator = context(invocation = InvocationContext.USER_ACTION, urlOpener = opened::add)
        for (url in listOf("javascript:alert(1)", "data:text/html;base64,x", "file:///etc/passwd")) {
            assertFailsWith<A2uiFunctionException> {
                evaluator.evaluate(call("""{"call":"openUrl","args":{"url":"$url"}}"""))
            }
        }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun openUrlRefusesARelativeUrlItCannotResolve() {
        val evaluator = context(
            invocation = InvocationContext.USER_ACTION,
            urlOpener = { error("must not be reached") },
        )
        assertFailsWith<A2uiFunctionException> {
            evaluator.evaluate(call("""{"call":"openUrl","args":{"url":"/settings"}}"""))
        }
    }

    @Test
    fun openUrlFailsVisiblyWithNoOpenerInstalled() {
        val evaluator = context(invocation = InvocationContext.USER_ACTION)
        val failure = assertFailsWith<A2uiFunctionException> {
            evaluator.evaluate(call("""{"call":"openUrl","args":{"url":"https://example.com"}}"""))
        }
        assertTrue(failure.message!!.contains("UrlOpener"))
    }

    // ---- checks -----------------------------------------------------------------------

    @Test
    fun aCheckConditionEvaluatesToAValidationResult() {
        val result = context().evaluateCheck(call("""{"call":"email","args":{"value":"nope"}}"""))
        assertEquals(false, result.valid)
        assertEquals(ValidationCode.INVALID_EMAIL, result.code)
        assertNull(result.severity)
    }

    @Test
    fun aCheckConditionMayBeABindingToAResultTheAgentComputed() {
        val data = """
            {"checks":{"card":{"valid":false,"code":"EXPIRED_CARD","severity":"warning","hint":1}}}
        """.trimIndent()
        val result = context(data).evaluateCheck(DataBinding("/checks/card"))
        assertEquals(false, result.valid)
        assertEquals("EXPIRED_CARD", result.code)
        assertEquals(Severity.WARNING, result.severity)
        assertEquals(json("1"), result.additional["hint"])
    }

    @Test
    fun aConditionThatIsNotAnObjectIsAMalformedCheckRatherThanAFailedOne() {
        assertFailsWith<A2uiFunctionException> {
            context().evaluateCheck(call("""{"call":"and","args":{"values":[true,true]}}"""))
        }
    }

    // ---- bounds -----------------------------------------------------------------------

    @Test
    fun nestingPastTheDepthBoundIsRefused() {
        val limits = EvaluationLimits(maxDepth = 4)
        var wire = """{"call":"not","args":{"value":true}}"""
        repeat(limits.maxDepth + 2) { wire = """{"call":"not","args":{"value":$wire}}""" }
        val failure = assertFailsWith<A2uiFunctionException> {
            context(limits = limits).evaluate(call(wire))
        }
        assertTrue(failure.message!!.contains("deep"))
    }

    @Test
    fun aBudgetIsPerTopLevelEvaluationRatherThanPerContext() {
        val evaluator = context("""{"a":1}""", limits = EvaluationLimits(maxSteps = 2))
        repeat(5) { assertEquals(JsonPrimitive(1), evaluator.evaluate(DataBinding("/a"))) }
    }

    @Test
    fun exhaustingTheStepBudgetIsRefused() {
        val values = List(20) { """{"call":"not","args":{"value":false}}""" }.joinToString(",")
        val wire = """{"call":"and","args":{"values":[$values]}}"""
        assertFailsWith<A2uiFunctionException> {
            context(limits = EvaluationLimits(maxSteps = 5)).evaluate(call(wire))
        }
    }

    @Test
    fun aTypeErrorMessageDoesNotCarryTheValueItRejected() {
        // The agent picks both the function and the path, so a type error is something it can
        // provoke on any bound field. The message must name the type and nothing else — numbers
        // and booleans included, since an account number is a JSON number as often as a string.
        val data = """{"form":{"cardNumber":4111111111111111,"note":"hunter2","consent":true}}"""
        val evaluator = context(data)
        val calls = listOf(
            """{"call":"not","args":{"value":{"path":"/form/cardNumber"}}}""",
            """{"call":"formatNumber","args":{"value":{"path":"/form/note"}}}""",
            """{"call":"formatDate","args":{"value":{"path":"/form/consent"},"format":"yyyy"}}""",
        )
        for (wire in calls) {
            val message =
                assertFailsWith<A2uiFunctionException> { evaluator.evaluate(call(wire)) }.message!!
            assertFalse(message.contains("4111111111111111"), message)
            assertFalse(message.contains("hunter2"), message)
            assertFalse(message.contains("true"), message)
        }
    }

    @Test
    fun anUnimplementedFunctionIsReportedByName() {
        val failure = assertFailsWith<A2uiFunctionException> {
            context().evaluate(call("""{"call":"validateCreditCard","args":{"n":1}}"""))
        }
        assertEquals("validateCreditCard", failure.call)
    }

    @Test
    fun anArgumentTheFunctionDoesNotReadIsIgnoredRatherThanRefused() {
        // Checking a call against its catalog schema is the catalog-driven checker's job.
        val wire = """{"call":"required","args":{"value":"a","surplus":{"call":"noSuchFunction"}}}"""
        assertEquals(true, context().valid(call(wire)))
    }
}

/** The `valid` field of a call that returns a `ValidationResult`. */
internal fun EvaluationContext.valid(fn: FunctionCall): Boolean = evaluateCheck(fn).valid
