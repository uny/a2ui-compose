package dev.ynagai.a2ui.compose

import dev.ynagai.a2ui.core.function.EvaluationLimits
import dev.ynagai.a2ui.core.function.FallbackLocaleFormatter
import dev.ynagai.a2ui.core.function.LocaleFormatter
import dev.ynagai.a2ui.core.function.PluralCategory
import dev.ynagai.a2ui.core.function.UrlOpener
import dev.ynagai.a2ui.core.surface.RenderLimits
import dev.ynagai.a2ui.core.validation.ValidationLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * [A2uiRendererConfig]'s derivations, and that the renderer reads through them.
 *
 * Why the type exists is a binary-compatibility argument that only `api/`'s dumps can pin: the
 * renderer's constructor is now `(config, initialState)` and stays that way, while a new setting
 * adds a `withX` here. What a test *can* catch is a derivation that drops a setting it was not
 * asked about -- the classic hand-written-copy failure, which here would silently put a renderer
 * back on the placeholder locale or the default limits.
 */
class A2uiRendererConfigTest {
    @Test
    fun the_default_is_the_basic_catalog_and_a_placeholder_locale() {
        val config = A2uiRendererConfig.Default
        assertEquals(listOf(BasicCatalog.definition), config.catalogs)
        assertSame(FallbackLocaleFormatter, config.locale)
        assertEquals(EvaluationLimits.DEFAULT, config.evaluationLimits)
        assertEquals(ValidationLimits.DEFAULT, config.validationLimits)
        assertEquals(RenderLimits.DEFAULT, config.renderLimits)
    }

    @Test
    fun each_derivation_changes_one_setting_and_carries_the_rest() {
        val opener = UrlOpener { }
        val clock = A2uiClock { "2026-09-03T00:00:00Z" }
        val evaluation = EvaluationLimits(maxResultLength = 7)
        val validation = ValidationLimits.DEFAULT
        val render = RenderLimits.DEFAULT

        val config = A2uiRendererConfig.Default
            .withCatalogs(emptyList())
            .withLocale(SHOUTING)
            .withUrlOpener(opener)
            .withClock(clock)
            .withEvaluationLimits(evaluation)
            .withValidationLimits(validation)
            .withRenderLimits(render)

        assertEquals(emptyList(), config.catalogs)
        assertSame(SHOUTING, config.locale)
        assertSame(opener, config.urlOpener)
        assertSame(clock, config.clock)
        assertEquals(evaluation, config.evaluationLimits)

        // One more on top changes only its own field. A `with` rebuilding from the defaults would
        // pass everything above and fail here -- and would do it by quietly restoring the
        // placeholder locale, which no rendering test would report as a locale problem.
        val again = config.withUrlOpener(UrlOpener { })
        assertEquals(emptyList(), again.catalogs)
        assertSame(SHOUTING, again.locale)
        assertSame(clock, again.clock)
        assertEquals(evaluation, again.evaluationLimits)
    }

    @Test
    fun a_derivation_leaves_the_configuration_it_came_from_alone() {
        val derived = A2uiRendererConfig.Default.withCatalogs(emptyList())
        assertEquals(listOf(BasicCatalog.definition), A2uiRendererConfig.Default.catalogs)
        assertEquals(emptyList(), derived.catalogs)
    }

    @Test
    fun the_renderer_reads_its_settings_through_the_configuration_it_was_given() {
        // The seven accessors on `A2uiRenderer` are what the rest of the library uses --
        // `A2uiComponentScope` builds every `EvaluationContext` from `renderer.locale`,
        // `renderer.urlOpener` and `renderer.evaluationLimits`. A delegating getter wired to the
        // wrong field would leave the whole renderer on defaults with the config looking correct.
        val opener = UrlOpener { }
        val clock = A2uiClock { "2026-09-03T00:00:00Z" }
        val renderer = A2uiRenderer(
            A2uiRendererConfig.Default
                .withCatalogs(emptyList())
                .withLocale(SHOUTING)
                .withUrlOpener(opener)
                .withClock(clock)
                .withEvaluationLimits(EvaluationLimits(maxResultLength = 7)),
        )
        assertEquals(emptyList(), renderer.catalogs)
        assertSame(SHOUTING, renderer.locale)
        assertSame(opener, renderer.urlOpener)
        assertSame(clock, renderer.clock)
        assertEquals(7, renderer.evaluationLimits.maxResultLength)
        assertEquals(ValidationLimits.DEFAULT, renderer.validationLimits)
        assertEquals(RenderLimits.DEFAULT, renderer.renderLimits)
    }

    @Test
    fun a_renderer_built_with_no_configuration_takes_the_default() {
        assertSame(A2uiRendererConfig.Default, A2uiRenderer().config)
    }

    private companion object {
        /** Distinguishable from [FallbackLocaleFormatter] by identity alone, which is all these
         * assertions need. */
        val SHOUTING: LocaleFormatter = object : LocaleFormatter by FallbackLocaleFormatter {
            override fun pluralCategory(value: Double): PluralCategory = PluralCategory.OTHER
        }
    }
}
