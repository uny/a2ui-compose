package dev.ynagai.a2ui.core.function

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.surface.EvaluationScope
import dev.ynagai.a2ui.core.surface.JsonPointer
import dev.ynagai.a2ui.core.surface.iterate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The derivations that replaced [EvaluationContext]'s seven-parameter constructor.
 *
 * The reason they exist is a binary-compatibility one and cannot be asserted from inside the
 * library -- what pins it is `api/`'s dumps, where the class now shows a one-argument constructor
 * and one method per setting. What *can* go wrong in source is a derivation that drops a field it
 * was not asked about, which is the classic failure of a hand-written copy and is invisible until
 * a payload formats in the wrong locale or an action loses its `UrlOpener`.
 *
 * So every case below changes one thing and asserts the other six survived. **Every value a
 * derivation is handed here differs from the value the one-argument constructor installs** --
 * otherwise "carried" and "reset to the default" are the same observation, and a derivation that
 * dropped its argument would pass. [MARKER] and [TIGHT] exist for that reason and for no other.
 */
class EvaluationContextTest {
    @Test
    fun the_one_argument_constructor_leaves_every_other_choice_at_its_default() {
        val context = EvaluationContext(MODEL)
        assertEquals(MODEL, context.dataModel)
        assertEquals(EvaluationScope.Root, context.scope)
        // The placeholder, chosen silently -- which is why the class documents it by name.
        assertSame(FallbackLocaleFormatter, context.locale)
        assertEquals(InvocationContext.RENDER, context.invocation)
        assertNull(context.urlOpener)
        assertEquals(EvaluationLimits.DEFAULT, context.limits)
        assertSame(A2uiJson.strict, context.json)
    }

    @Test
    fun each_derivation_changes_one_setting_and_carries_the_rest() {
        val opener = UrlOpener { }
        val json = Json { prettyPrint = true }
        val scope = EvaluationScope.Root.iterate(JsonPointer.parse("/rows"), index = 2)

        val full = EvaluationContext(MODEL)
            .inScope(scope)
            .withLocale(MARKER)
            .withInvocation(InvocationContext.USER_ACTION)
            .withUrlOpener(opener)
            .withLimits(TIGHT)
            .withJson(json)

        // Every setting arrived, and `dataModel` -- the one thing no derivation touches -- is
        // still what the constructor was given.
        assertEquals(MODEL, full.dataModel)
        assertEquals(scope, full.scope)
        assertSame(MARKER, full.locale)
        assertEquals(InvocationContext.USER_ACTION, full.invocation)
        assertSame(opener, full.urlOpener)
        assertEquals(TIGHT, full.limits)
        assertSame(json, full.json)

        // And one more derivation on top changes only its own field. A `with` that rebuilt from
        // defaults instead of from `this` would pass every assertion above and fail these -- so
        // this half checks all six of the settings it was not asked to change, not a subset.
        val back = full.withInvocation(InvocationContext.RENDER)
        assertEquals(InvocationContext.RENDER, back.invocation)
        assertEquals(MODEL, back.dataModel)
        assertEquals(scope, back.scope)
        assertSame(MARKER, back.locale)
        assertSame(opener, back.urlOpener)
        assertEquals(TIGHT, back.limits)
        assertSame(json, back.json)
    }

    @Test
    fun a_url_opener_can_be_taken_away_again() {
        // `null` is a meaningful value for `urlOpener` -- it is what makes `openUrl` refuse -- so a
        // derivation that could not express it would leave a capability installed with no way to
        // withdraw it, which is a capability leak rather than a cosmetic bug.
        val opener = UrlOpener { }
        val withOne = EvaluationContext(MODEL).withUrlOpener(opener)
        assertNotNull(withOne.urlOpener)
        assertNull(withOne.withUrlOpener(null).urlOpener)
    }

    @Test
    fun withdrawing_the_opener_leaves_every_other_setting_alone() {
        // `withUrlOpener` is the one derivation whose argument is nullable, so it is the one that
        // could plausibly be written to rebuild from defaults rather than from `this`.
        val configured = EvaluationContext(MODEL)
            .withLocale(MARKER)
            .withLimits(TIGHT)
            .withInvocation(InvocationContext.USER_ACTION)
            .withUrlOpener(UrlOpener { })

        val withdrawn = configured.withUrlOpener(null)
        assertNull(withdrawn.urlOpener)
        assertSame(MARKER, withdrawn.locale)
        assertEquals(TIGHT, withdrawn.limits)
        assertEquals(InvocationContext.USER_ACTION, withdrawn.invocation)
    }

    @Test
    fun a_derivation_that_does_not_mention_the_opener_keeps_it() {
        // The other five derivations must leave `urlOpener` where it is. A copy-paste that made one
        // of them write the field would take the capability away silently.
        val opener = UrlOpener { }
        val configured = EvaluationContext(MODEL).withUrlOpener(opener)
        assertSame(opener, configured.withLocale(MARKER).urlOpener)
        assertSame(opener, configured.withLimits(TIGHT).urlOpener)
        assertSame(opener, configured.withJson(Json { prettyPrint = true }).urlOpener)
        assertSame(opener, configured.inScope(EvaluationScope.Root).urlOpener)
    }

    @Test
    fun a_derivation_leaves_the_context_it_came_from_alone() {
        val original = EvaluationContext(MODEL)
        val derived = original.withInvocation(InvocationContext.USER_ACTION)
        assertEquals(InvocationContext.RENDER, original.invocation)
        // Read the derived value too: without this the test passes against a `withInvocation` that
        // returned `this` and did nothing at all, which is not what it is here to say.
        assertEquals(InvocationContext.USER_ACTION, derived.invocation)
        assertNotEquals(original.invocation, derived.invocation)
    }

    private companion object {
        val MODEL: JsonObject =
            Json.parseToJsonElement("""{"rows":[{"n":1},{"n":2},{"n":3}]}""") as JsonObject

        /**
         * A formatter that is distinguishable from [FallbackLocaleFormatter] by identity alone.
         *
         * It never formats anything here -- what matters is only that it is not the default, so
         * that "the derivation carried it" is a different observation from "the field was reset".
         */
        val MARKER: LocaleFormatter = object : LocaleFormatter by FallbackLocaleFormatter {}

        /** Bounds that differ from [EvaluationLimits.DEFAULT] in every field, for the same reason. */
        val TIGHT: EvaluationLimits = EvaluationLimits(
            maxDepth = 3,
            maxSteps = 7,
            maxResultLength = 11,
            maxPatternLength = 13,
            maxSubjectLength = 17,
        )
    }
}
