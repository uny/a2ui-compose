package dev.ynagai.a2ui.gallery

import dev.ynagai.a2ui.core.protocol.CreateSurfaceMessage
import dev.ynagai.a2ui.core.protocol.UpdateComponentsMessage
import dev.ynagai.a2ui.core.protocol.UpdateDataModelMessage
import dev.ynagai.a2ui.core.validation.CatalogValidator
import dev.ynagai.a2ui.core.validation.MessageDirection
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(
            EXAMPLES.all { it.decoded.size == it.raw.size },
            "an example decoded to a different number of messages than it carries",
        )
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
                if (validation.isValid) {
                    null
                } else {
                    "${example.file} #$index: " +
                        validation.violations.joinToString { "${it.location} ${it.message}" }
                }
            }
        }
        assertTrue(failures.isEmpty(), "examples the basic catalog refuses:\n" + failures.joinToString("\n"))
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
        const val BASIC_CATALOG_ID = "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json"

        /**
         * Built once for the whole class, not per test.
         *
         * `kotlin.test` constructs a new instance of the test class for every test method, so a
         * `private val` here would build the schema registry over a fifty-kilobyte catalog four
         * times. On a JVM that is invisible; in a browser on a CI runner it was most of a mocha
         * timeout, and a timeout is what karma reports as a bare `Error`.
         */
        val VALIDATOR = CatalogValidator.of(listOf(BASIC_CATALOG))
    }
}
