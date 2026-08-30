package dev.ynagai.a2ui.gallery

import dev.ynagai.a2ui.material3.Material3Components
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which of the specification's forty-three examples the shipped registry can draw.
 *
 * Derived from the corpus rather than listed by hand -- `isDrawableBy` compares the component
 * types an example names against the types the registry knows -- so this grows on its own as
 * components are added, and the assertion below is what pins where it has got to.
 */
class DrawableExamplesTest {
    @Test
    fun the_drawable_examples_are_the_ones_the_fourteen_basic_components_cover() {
        val drawable = EXAMPLES
            .filter { it.isDrawableBy(Material3Components.Basic.types) }
            .map { it.file }
        // Written out rather than counted. This is the coverage claim, and a registry that lost a
        // component or a corpus that gained an example should have to say which one -- a count
        // would go on passing while a different set of thirty-nine passed through it.
        //
        // The four left out are the four components still missing: `24_recipe-card` needs `Tabs`,
        // `26_podcast-episode` an `AudioPlayer`, `36_modal` a `Modal`, and `29_movie-card` both a
        // `Modal` and a `Video`.
        assertEquals(
            listOf(
                "00_complex-layout.json",
                "00_formatted-text.json",
                "00_incremental.json",
                "00_interactive-button.json",
                "00_row-layout.json",
                "00_simple-login-form.json",
                "00_simple-text.json",
                "01_flight-status.json",
                "02_email-compose.json",
                "03_calendar-day.json",
                "04_weather-current.json",
                "05_product-card.json",
                "06_music-player.json",
                "07_task-card.json",
                "08_user-profile.json",
                "09_login-form.json",
                "10_notification-permission.json",
                "11_purchase-complete.json",
                "12_chat-message.json",
                "13_coffee-order.json",
                "14_sports-player.json",
                "15_account-balance.json",
                "16_workout-summary.json",
                "17_event-detail.json",
                "18_track-list.json",
                "19_software-purchase.json",
                "20_restaurant-card.json",
                "21_shipping-status.json",
                "22_credit-card.json",
                "23_step-counter.json",
                "25_contact-card.json",
                "27_stats-card.json",
                "28_countdown-timer.json",
                "30_live-invitation-builder.json",
                "31_incremental-dashboard.json",
                "32_advanced-form-validator.json",
                "33_financial-data-grid.json",
                "34_child-list-template.json",
                "35_markdown-text.json",
            ),
            drawable,
        )
    }

    @Test
    fun an_example_naming_no_components_is_drawable_by_nothing() {
        // The degenerate case `isDrawableBy` exists to exclude: `containsAll(emptySet())` is true
        // for every registry, so an example with no components would otherwise report as drawable
        // by the empty one.
        val empty = Example(
            file = "empty.json",
            name = "empty",
            description = "",
            raw = emptyList(),
            decoded = emptyList(),
            componentTypes = emptySet(),
        )
        assertTrue(!empty.isDrawableBy(Material3Components.Basic.types))
    }

    @Test
    fun every_example_names_at_least_one_component() {
        // Guards the scan in `componentTypes` rather than the corpus: a change that stopped it
        // finding components would otherwise make every example look drawable by everything.
        val silent = EXAMPLES.filter { it.componentTypes.isEmpty() }.map { it.file }
        assertEquals(emptyList(), silent)
    }
}
