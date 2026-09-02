package dev.ynagai.a2ui.core.function

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.surface.EvaluationScope
import dev.ynagai.a2ui.core.surface.JsonPointer
import dev.ynagai.a2ui.core.surface.iterate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * So every case below changes one thing and asserts the other six survived.
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
        val limits = EvaluationLimits.DEFAULT
        val json = Json { prettyPrint = true }
        val scope = EvaluationScope.Root.iterate(JsonPointer.parse("/rows"), index = 2)

        val full = EvaluationContext(MODEL)
            .inScope(scope)
            .withLocale(FallbackLocaleFormatter)
            .withInvocation(InvocationContext.USER_ACTION)
            .withUrlOpener(opener)
            .withLimits(limits)
            .withJson(json)

        // Every setting arrived, and `dataModel` -- the one thing no derivation touches -- is
        // still what the constructor was given.
        assertEquals(MODEL, full.dataModel)
        assertEquals(scope, full.scope)
        assertEquals(InvocationContext.USER_ACTION, full.invocation)
        assertSame(opener, full.urlOpener)
        assertEquals(limits, full.limits)
        assertSame(json, full.json)

        // And one more derivation on top changes only its own field. A `with` that rebuilt from
        // defaults instead of from `this` would pass every assertion above and fail these.
        val back = full.withInvocation(InvocationContext.RENDER)
        assertEquals(InvocationContext.RENDER, back.invocation)
        assertEquals(scope, back.scope)
        assertSame(opener, back.urlOpener)
        assertSame(json, back.json)
    }

    @Test
    fun a_url_opener_can_be_taken_away_again() {
        // The case a defaulted parameter cannot express: `null` is a meaningful value for
        // `urlOpener` -- it is what makes `openUrl` refuse -- so it cannot double as "unchanged".
        // A `with` that treated it as unchanged would silently keep the opener installed, which is
        // a capability leak rather than a cosmetic bug.
        val opener = UrlOpener { }
        val withOne = EvaluationContext(MODEL).withUrlOpener(opener)
        assertNotNull(withOne.urlOpener)
        assertNull(withOne.withUrlOpener(null).urlOpener)
    }

    @Test
    fun a_derivation_leaves_the_context_it_came_from_alone() {
        val original = EvaluationContext(MODEL)
        original.withInvocation(InvocationContext.USER_ACTION)
        assertEquals(InvocationContext.RENDER, original.invocation)
    }

    private companion object {
        val MODEL: JsonObject =
            Json.parseToJsonElement("""{"rows":[{"n":1},{"n":2},{"n":3}]}""") as JsonObject
    }
}
