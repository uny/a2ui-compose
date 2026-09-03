package dev.ynagai.a2ui.compose

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.Severity
import dev.ynagai.a2ui.core.surface.EvaluationScope
import dev.ynagai.a2ui.core.surface.RenderLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `checks`, evaluated without a composition.
 *
 * The specification describes a check's condition two ways -- as a `ValidationResult` object and as
 * a bare boolean -- and its own examples use both, so most of what is settled here is which shapes
 * are recognised and what each one means. The half that needs a screen -- a disabled button, a
 * captioned field -- is in the Material 3 module's tests.
 */
class ChecksTest {
    @Test
    fun a_passing_check_reports_nothing() {
        assertEquals(emptyList(), scopeFor("valid_email").checkFailures())
    }

    @Test
    fun a_failing_validation_result_reports_the_rule_s_message() {
        // `required` against `/blank`, which holds "". The result carries a `code` and no
        // `message`, so the message shown is the rule's own -- the fallback the schema calls for.
        val failures = scopeFor("blank_field").checkFailures()
        assertEquals(1, failures.size)
        assertEquals("Value is required", failures.single().message)
        assertEquals("REQUIRED", failures.single().code)
        assertEquals(Severity.ERROR, failures.single().severity)
    }

    @Test
    fun a_result_that_carries_its_own_message_wins_over_the_rule_s() {
        // A condition that is a *binding* rather than a function call: the data model holds a whole
        // `ValidationResult`, which is the form the protocol documents for a domain-specific check
        // like `validateCreditCard`. Its message is more specific than the rule's fallback, so it
        // is the one shown.
        val failures = scopeFor("bound_result").checkFailures()
        assertEquals("The card expiration date has passed.", failures.single().message)
        assertEquals("EXPIRED_CARD", failures.single().code)
    }

    @Test
    fun a_severity_the_result_names_is_kept_and_does_not_disable() {
        val failures = scopeFor("warned").checkFailures()
        assertEquals(Severity.WARNING, failures.single().severity)
        // The distinction the severity exists for: a warning is shown and the action stays
        // available. A renderer that gated on "any failure" would take the button away for it.
        assertTrue(!failures.hasError(), "a warning alone should not disable anything")
        assertEquals("Looks unusual", failures.firstMessage()?.message)
    }

    @Test
    fun a_bare_boolean_condition_is_read_as_a_validity() {
        // The implementation guide's reading of the same five functions: `true`/`false` rather than
        // an object. Here the data model simply holds `false`, which a renderer that only
        // understood the object form would silently treat as unreadable and pass.
        val failures = scopeFor("bare_boolean").checkFailures()
        assertEquals("Not allowed", failures.single().message)
        assertEquals(Severity.ERROR, failures.single().severity)
    }

    @Test
    fun a_check_that_cannot_be_read_does_not_fail() {
        // Two ways to be unreadable, neither of which is an invalid answer: a condition that
        // resolves to a string, and a path with nothing behind it -- the second being ordinary
        // progressive rendering, before the `updateDataModel` carrying it has arrived. A renderer
        // that counted these as failures would disable a button for a payload that never said
        // anything was wrong.
        for (id in listOf("not_a_validity", "absent_path")) {
            assertEquals(emptyList(), scopeFor(id).checkFailures(), "$id should report nothing")
        }
    }

    @Test
    fun a_condition_that_raises_is_a_failure_rather_than_a_skip() {
        // The other half, and the half the user controls. `regex` refuses a subject past
        // `maxSubjectLength`, so a rule gating a `Button` on a field's contents raises the moment
        // the user pastes more than the evaluator will look at. Skipping it -- which is what
        // "cannot be evaluated does not fail" used to mean -- deleted the rule and re-enabled the
        // button, letting anyone through the gate by typing past it.
        val failures = scopeFor("subject_too_long").checkFailures()
        assertEquals("Letters and digits only", failures.single().message)
        assertTrue(failures.hasError(), "a check the user broke must still hold the gate shut")

        // The same rule passes when the subject is short enough, so the failure above is the
        // limit talking and not the rule being permanently broken.
        assertEquals(emptyList(), scopeFor("subject_short_enough").checkFailures())

        // And the cost, stated: a function this evaluator does not have now disables the action
        // rather than being ignored. That is the agent's mistake failing safe and loudly.
        assertTrue(scopeFor("unknown_function").checkFailures().hasError())
    }

    @Test
    fun a_component_with_no_checks_reports_nothing() {
        assertEquals(emptyList(), scopeFor("plain").checkFailures())
    }

    @Test
    fun every_failing_rule_is_reported_in_the_order_it_was_written() {
        // Both rules on the field fail. The order matters because an input shows the first message
        // that has one, and the agent's order is the only ranking there is.
        val failures = scopeFor("two_failures").checkFailures()
        assertEquals(listOf("first", "second"), failures.map { it.message })
    }

    @Test
    fun a_rule_that_will_not_decode_costs_only_its_own_rule() {
        // The list is decoded one rule at a time, so an unreadable rule cannot take a readable one
        // down with it. Read as a whole `ListSerializer` this returns nothing at all -- and
        // "nothing failing" is what re-enables a `Button` the agent gated, which is a validation
        // bypass rather than the degradation the API promises.
        //
        // Two ways to be unreadable, both of which `A2uiJson.strict` refuses for the whole array:
        // a rule carrying a key `CheckRule` does not model, and a `condition` that is not a
        // `BoundValue`. Either one is written *before* the real rule, so an implementation that
        // stopped at the first failure would report nothing.
        for (id in listOf("unknown_key_first", "bad_condition_first")) {
            val failures = scopeFor(id).checkFailures()
            assertEquals(listOf("Value is required"), failures.map { it.message }, "$id")
            assertTrue(failures.hasError(), "$id should still disable the action it gates")
        }
    }

    @Test
    fun an_error_is_shown_ahead_of_a_warning_the_agent_happened_to_write_first() {
        // Both fail. The warning is written first, so the agent's own order would put it on screen
        // -- and the error, which is also what disables the `Button` this field feeds, would never
        // be shown at all: a greyed-out action with a field drawn as a mild remark beside it.
        // Within one severity the agent's order still decides; only an error jumps the queue.
        val failures = scopeFor("warning_before_error").checkFailures()
        assertEquals(listOf("Looks unusual", "Value is required"), failures.map { it.message })
        assertEquals("Value is required", failures.firstMessage()?.message)
        assertTrue(failures.hasError())
    }

    private fun scopeFor(componentId: String): A2uiComponentScope {
        val renderer = A2uiRenderer(A2uiRendererConfig.Default
            .withClock({ "2026-08-30T00:00:00Z" }),
        ).also { it.applyAll(MESSAGES) }
        return A2uiComponentScope(
            renderer = renderer,
            surfaceId = SURFACE,
            component = renderer.state.surfaces.getValue(SURFACE).components.getValue(componentId),
            evaluationScope = EvaluationScope.Root,
            budget = { RenderLimits.DEFAULT.maxInstances },
            onMessage = {},
        )
    }

    private companion object {
        const val SURFACE = "s"

        val MESSAGES: List<AgentToRendererMessage> = listOf(
            """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE","catalogId":"CATALOG_ID"}}""",
            """
            {"version":"v1.0","updateDataModel":{"surfaceId":"$SURFACE","value":{
              "email":"ada@example.com",
              "blank":"",
              "allowed":false,
              "card":{"valid":false,"code":"EXPIRED_CARD","message":"The card expiration date has passed."},
              "unusual":{"valid":false,"severity":"warning","message":"Looks unusual"},
              "note":"not a validity",
              "long":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
              "short":"OK123"
            }}}
            """.trimIndent(),
            """
            {"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE","components":[
              {"id":"plain","component":"TextField","label":"Plain"},
              {"id":"valid_email","component":"TextField","label":"Email","value":{"path":"/email"},
               "checks":[{"condition":{"call":"email","args":{"value":{"path":"/email"}}},
                          "message":"Invalid email"}]},
              {"id":"blank_field","component":"TextField","label":"Name","value":{"path":"/blank"},
               "checks":[{"condition":{"call":"required","args":{"value":{"path":"/blank"}}},
                          "message":"Value is required"}]},
              {"id":"bound_result","component":"TextField","label":"Card","value":{"path":"/blank"},
               "checks":[{"condition":{"path":"/card"},"message":"Check your card"}]},
              {"id":"warned","component":"TextField","label":"Amount","value":{"path":"/blank"},
               "checks":[{"condition":{"path":"/unusual"}}]},
              {"id":"bare_boolean","component":"CheckBox","label":"Agree","value":{"path":"/allowed"},
               "checks":[{"condition":{"path":"/allowed"},"message":"Not allowed"}]},
              {"id":"unknown_function","component":"TextField","label":"X","value":{"path":"/blank"},
               "checks":[{"condition":{"call":"noSuchFunction","args":{"value":{"path":"/blank"}}},
                          "message":"never shown"}]},
              {"id":"not_a_validity","component":"TextField","label":"X","value":{"path":"/blank"},
               "checks":[{"condition":{"path":"/note"},"message":"never shown"}]},
              {"id":"absent_path","component":"TextField","label":"X","value":{"path":"/blank"},
               "checks":[{"condition":{"path":"/nothing/here"},"message":"never shown"}]},
              {"id":"subject_too_long","component":"TextField","label":"X","value":{"path":"/long"},
               "checks":[{"condition":{"call":"regex","args":{"pattern":"^[A-Z0-9]+$","value":{"path":"/long"}}},
                          "message":"Letters and digits only"}]},
              {"id":"subject_short_enough","component":"TextField","label":"X","value":{"path":"/short"},
               "checks":[{"condition":{"call":"regex","args":{"pattern":"^[A-Z0-9]+$","value":{"path":"/short"}}},
                          "message":"Letters and digits only"}]},
              {"id":"warning_before_error","component":"TextField","label":"X","value":{"path":"/blank"},
               "checks":[{"condition":{"path":"/unusual"}},
                         {"condition":{"call":"required","args":{"value":{"path":"/blank"}}},
                          "message":"Value is required"}]},
              {"id":"unknown_key_first","component":"TextField","label":"X","value":{"path":"/blank"},
               "checks":[{"condition":{"path":"/allowed"},"message":"never shown","severity":"error"},
                         {"condition":{"call":"required","args":{"value":{"path":"/blank"}}},
                          "message":"Value is required"}]},
              {"id":"bad_condition_first","component":"TextField","label":"X","value":{"path":"/blank"},
               "checks":[{"condition":true,"message":"never shown"},
                         {"condition":{"call":"required","args":{"value":{"path":"/blank"}}},
                          "message":"Value is required"}]},
              {"id":"two_failures","component":"TextField","label":"X","value":{"path":"/blank"},
               "checks":[{"condition":{"call":"required","args":{"value":{"path":"/blank"}}},"message":"first"},
                         {"condition":{"call":"email","args":{"value":{"path":"/blank"}}},"message":"second"}]}
            ]}}
            """.trimIndent(),
        ).map { text ->
            A2uiJson.strict.decodeFromString(
                AgentToRendererMessage.serializer(),
                text.replace("CATALOG_ID", BasicCatalog.id),
            )
        }
    }
}
