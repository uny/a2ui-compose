package dev.ynagai.a2ui.gallery

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.ActionMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Gallery's logic, without a composition.
 *
 * These are the claims the integration suite will lean on -- stepping, resetting, and what the
 * inspection panes read -- and every one of them is settled by the state object alone. Asserting
 * them here rather than through the UI means a failure names the operation that broke rather than
 * the pane that stopped showing something.
 */
class GalleryStateTest {
    @Test
    fun a_fresh_gallery_has_applied_nothing() {
        val state = GalleryState(examples = listOf(simpleText))
        assertEquals(0, state.cursor)
        assertTrue(state.canAdvance)
        // No surface at all yet, rather than an empty one: `createSurface` is the first message,
        // and until it is applied the renderer has never heard of this example.
        assertNull(state.surfaceId)
        assertEquals("{}", state.dataModelJson)
        assertEquals(emptyList(), state.actionLog.toList())
    }

    @Test
    fun advancing_applies_exactly_one_message() {
        val state = GalleryState(examples = listOf(incremental))
        val messages = incremental.raw.size
        repeat(messages) { applied ->
            assertEquals(applied, state.cursor)
            state.advance()
            assertEquals(applied + 1, state.cursor)
        }
        assertFalse(state.canAdvance)
        // And a further advance is a no-op rather than a failure: the button is disabled, but the
        // integration suite calls this directly and should not have to guard every call.
        state.advance()
        assertEquals(messages, state.cursor)
    }

    @Test
    fun running_all_of_them_leaves_a_renderable_surface() {
        val state = GalleryState(examples = listOf(simpleText))
        state.advanceAll()
        assertEquals(simpleText.raw.size, state.cursor)
        assertFalse(state.canAdvance)
        val surfaceId = assertNotNull(state.surfaceId, "the example creates a surface")
        assertTrue(state.renderer.state.surfaces.getValue(surfaceId).isRenderable)
    }

    @Test
    fun running_all_from_part_way_through_applies_only_what_is_left() {
        val stepped = GalleryState(examples = listOf(incremental))
        repeat(3) { stepped.advance() }
        stepped.advanceAll()

        val straight = GalleryState(examples = listOf(incremental))
        straight.advanceAll()

        // The same state either way. `advanceAll` takes a subList from the cursor, and an
        // off-by-one there would re-apply a `createSurface` -- which the processor refuses -- or
        // skip a message, which nothing would report at all.
        assertEquals(straight.renderer.state, stepped.renderer.state)
    }

    @Test
    fun resetting_returns_to_nothing_applied() {
        val state = GalleryState(examples = listOf(simpleText))
        state.advanceAll()
        state.record(anAction)
        state.reset()
        assertEquals(0, state.cursor)
        assertNull(state.surfaceId)
        assertEquals(emptyList(), state.actionLog.toList())
    }

    @Test
    fun selecting_another_example_restarts_the_stepper() {
        val state = GalleryState(examples = listOf(simpleText, incremental))
        state.advanceAll()
        state.select(incremental)
        assertEquals(incremental, state.example)
        assertEquals(0, state.cursor)
        assertNull(state.surfaceId)
    }

    @Test
    fun selecting_the_example_already_showing_keeps_where_it_had_got_to() {
        val state = GalleryState(examples = listOf(incremental))
        state.advance()
        state.select(incremental)
        assertEquals(1, state.cursor)
    }

    @Test
    fun the_data_model_pane_follows_the_surface() {
        val state = GalleryState(examples = listOf(incremental))
        state.advanceAll()
        val surface = assertNotNull(state.surface)
        assertEquals(
            GalleryJson.encodeToString<JsonElement>(surface.dataModel),
            state.dataModelJson,
        )
        // An example that writes a data model should have one worth showing; `{}` here would mean
        // the pane is reading the wrong surface rather than that the example is empty.
        assertTrue(surface.dataModel.isNotEmpty(), "this example writes to its data model")
    }

    @Test
    fun a_recorded_action_keeps_both_the_message_and_its_json() {
        val state = GalleryState(examples = listOf(simpleText))
        state.record(anAction)
        val entry = state.actionLog.single()
        assertEquals(anAction, entry.message)
        assertEquals("submit", entry.label)
        assertEquals("button_1", entry.sourceComponentId)
        // Round-trips: the pane shows the payload an agent would receive, not a rendering of this
        // library's data class.
        assertEquals(
            anAction,
            A2uiJson.strict.decodeFromString<dev.ynagai.a2ui.core.protocol.RendererToAgentMessage>(
                entry.json,
            ),
        )
    }

    @Test
    fun a_shown_message_is_the_payload_the_agent_sent() {
        val state = GalleryState(examples = listOf(simpleText))
        for (index in simpleText.raw.indices) {
            assertEquals(
                simpleText.raw[index],
                A2uiJson.strict.parseToJsonElement(state.messageJson(index)),
            )
        }
    }

    private companion object {
        // Named from the corpus rather than hand-written: what the Gallery steps through is the
        // specification's own examples, and a fixture here would let this pass while the real
        // corpus broke.
        val simpleText = EXAMPLES.single { it.file == "00_simple-text.json" }

        /** The corpus's own progressive-rendering example: six messages, two of them data. */
        val incremental = EXAMPLES.single { it.file == "00_incremental.json" }

        val anAction = ActionMessage(
            name = "submit",
            surfaceId = "surface_1",
            sourceComponentId = "button_1",
            timestamp = "2026-08-31T00:00:00Z",
            context = JsonObject(mapOf("email" to JsonPrimitive("a@example.com"))),
        )
    }
}
