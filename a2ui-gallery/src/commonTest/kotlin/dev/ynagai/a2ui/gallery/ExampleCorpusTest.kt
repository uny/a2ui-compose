package dev.ynagai.a2ui.gallery

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CreateSurfaceMessage
import dev.ynagai.a2ui.core.protocol.UpdateComponentsMessage
import dev.ynagai.a2ui.core.protocol.UpdateDataModelMessage
import dev.ynagai.a2ui.core.validation.CatalogValidator
import dev.ynagai.a2ui.core.validation.MessageDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The corpus that defines G2's completion.
 *
 * G2's criterion in the plan is "the standard widgets render, and the samples run on every
 * target". Neither half is decidable as written, so this fixes the second half to a corpus the
 * specification owns: its own 43 examples. What this file asserts is the part that can be checked
 * before a single pixel exists -- that all 43 parse into this implementation's message types, and
 * that the specification's own validator, running against the basic catalog, accepts every message
 * in them.
 *
 * That matters more than it sounds. If an example does not validate, then either the example is
 * wrong or this implementation's catalog handling is, and finding out during T10 -- with a
 * half-drawn widget in the way -- costs far more than finding out now.
 */
class ExampleCorpusTest {
    @Test
    fun the_corpus_is_the_specifications_own() {
        assertEquals(EXAMPLES_EXPECTED, EXAMPLES.size, "vendored example count changed")
        assertEquals(
            Example.FOUNDATIONAL,
            EXAMPLES.filter { it.isFoundational }.map { it.file.removeSuffix(".json") }.toSet(),
            "the five foundational examples the SDK skill names are not all present",
        )
    }

    @Test
    fun every_example_decodes() {
        // Decoding happens in the corpus initializer, so reaching here at all is most of the
        // claim; what is asserted is that nothing decoded to an empty message list, which would
        // pass silently and render as a blank Gallery page.
        val empty = EXAMPLES.filter { it.decoded.isEmpty() }.map { it.file }
        assertTrue(empty.isEmpty(), "examples carry no messages: $empty")
        // The message total, not `decoded.size == raw.size` -- `decoded` is `raw.map { }`, so that
        // comparison holds for every possible corpus and asserts nothing. This one is against the
        // specification's count, so re-vendoring a revision that drops or adds a message says so.
        assertEquals(MESSAGES_EXPECTED, EXAMPLES.sumOf { it.decoded.size }, "vendored message count changed")
        // The title the Gallery lists an example under. Without this, `Example.name`'s fallback to
        // the bare filename covers a `name` that never parsed, and the list renders
        // `00_simple-text` where the specification says `Simple Text`.
        val unnamed = EXAMPLES.filter { it.name == it.file.removeSuffix(".json") || it.name.isBlank() }
        assertTrue(unnamed.isEmpty(), "examples fell back to their filename for a name: ${unnamed.map { it.file }}")
        val undescribed = EXAMPLES.filter { it.description.isBlank() }.map { it.file }
        assertTrue(undescribed.isEmpty(), "examples fell back to an empty description: $undescribed")
    }

    @Test
    fun every_example_asks_for_the_catalog_this_suite_checks_it_against() {
        // `catalogId` is `"type": "string"` in the schema, so an example naming a catalog nobody
        // holds is a valid message. This suite binds the basic catalog explicitly rather than
        // reading each example's own id, so without this assertion it would keep reporting
        // "validates against the basic catalog" for a corpus that had stopped asking for it --
        // and the failure would surface as an unresolvable catalog in the renderer instead.
        val foreign = EXAMPLES.flatMap { example ->
            example.decoded.filterIsInstance<CreateSurfaceMessage>()
                .map { example.file to it.catalogId }
        }.filter { (_, catalogId) -> catalogId != BASIC_CATALOG.catalogId }
        assertTrue(foreign.isEmpty(), "examples name a catalog this suite does not check against: $foreign")
    }

    @Test
    fun every_message_validates_against_the_basic_catalog() {
        val failures = EXAMPLES.flatMap { example ->
            example.raw.mapIndexedNotNull { index, message ->
                val validation = VALIDATOR.validateMessage(
                    message = message,
                    direction = MessageDirection.AGENT_TO_RENDERER,
                    catalogId = BASIC_CATALOG_ID,
                )
                // `isValid` reads `violations` alone. A keyword the evaluator does not apply, or a
                // run that hit its budget, both arrive as acceptance -- so checking only `isValid`
                // would report "the catalog accepts all 126" for messages part of which was never
                // checked. `a2ui-core`'s conformance suite guards the same two fields for the same
                // reason; the corpus needs it just as much.
                //
                // Reported alongside the violations rather than instead of them: `truncated` is
                // only ever set with violations already in hand, so a branch that returned early on
                // it would name the budget and hide every violation that prompted the cap.
                val notes = buildList {
                    if (validation.unsupportedKeywords.isNotEmpty()) {
                        add("schema only partly applied: ${validation.unsupportedKeywords}")
                    }
                    if (validation.truncated) add("stopped at its bounds, so this verdict is partial")
                    addAll(validation.violations.map { "${it.location} ${it.message}" })
                }
                if (notes.isEmpty()) null else "${example.file} #$index: " + notes.joinToString("; ")
            }
        }
        assertTrue(failures.isEmpty(), "examples the basic catalog refuses:\n" + failures.joinToString("\n"))
    }

    @Test
    fun the_basic_catalog_refuses_a_component_it_does_not_define() {
        // The negative control for the test above, which is otherwise satisfied maximally by a
        // validator that returns valid for everything -- so on its own it certifies that 126
        // messages were passed to something, not that anything was checked.
        //
        // A pair rather than a single rejection, differing in the component name and nothing else.
        // A lone `assertFalse` passes for any reason the message is bad, including a missing
        // required property, so it would still hold if the catalog's component list were never
        // consulted. Anchoring it against the accepted twin is what makes the name the variable.
        fun message(component: String) = A2uiJson.strict.parseToJsonElement(
            """{"version":"v1.0","updateComponents":{"surfaceId":"s","components":""" +
                """[{"id":"root","component":"$component","text":"hello"}]}}""",
        )

        fun verdict(component: String) = VALIDATOR.validateMessage(
            message = message(component),
            direction = MessageDirection.AGENT_TO_RENDERER,
            catalogId = BASIC_CATALOG_ID,
        )

        assertTrue(verdict("Text").isValid, "the basic catalog refused a component it does define")
        assertFalse(
            verdict("NoSuchComponent").isValid,
            "the basic catalog accepted a component it does not define",
        )
    }

    @Test
    fun the_corpus_uses_only_the_three_message_types_the_gallery_steps_through() {
        // The Gallery's stepper drives `MessageProcessor` and nothing else. If the corpus grew a
        // `callRendererFunction` or a `deleteSurface`, the stepper would need a reply path and an
        // action log entry that it does not have, so this asserts the shape T11 is built against
        // rather than leaving it as an assumption.
        val kinds = EXAMPLES.flatMap { it.decoded }.map { message ->
            when (message) {
                is CreateSurfaceMessage -> "createSurface"
                is UpdateComponentsMessage -> "updateComponents"
                is UpdateDataModelMessage -> "updateDataModel"
                else -> message::class.simpleName ?: "unknown"
            }
        }.toSet()
        assertEquals(setOf("createSurface", "updateComponents", "updateDataModel"), kinds)
    }

    private companion object {
        const val EXAMPLES_EXPECTED = 43
        const val MESSAGES_EXPECTED = 126
        const val BASIC_CATALOG_ID = "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json"

        /**
         * Built once for the whole class, not per test.
         *
         * `kotlin.test` constructs a new instance of the test class for every test method, so a
         * `private val` here would build the schema registry over a fifty-kilobyte catalog once per
         * test method. On a JVM that is invisible; in a browser on a CI runner it was most of a mocha
         * timeout, and a timeout is what karma reports as a bare `Error`.
         */
        val VALIDATOR = CatalogValidator.of(listOf(BASIC_CATALOG))
    }
}
