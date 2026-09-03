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
        // Every one of these differs from what `Default` holds. A fixture equal to the default is
        // an assertion that cannot fail: the derivation could ignore its argument outright and the
        // value read back would still match.
        val evaluation = EvaluationLimits(maxResultLength = 7)
        val validation = ValidationLimits(maxViolations = 3)
        val render = RenderLimits(maxInstances = 11)

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
        // `validation` is set before `withRenderLimits`, so this reads it back *through* a later
        // derivation: it fails both if `withValidationLimits` ignores its argument and if the copy
        // helper rebuilds the field from the default instead of carrying it.
        assertEquals(validation, config.validationLimits)
        assertEquals(render, config.renderLimits)

        // One more on top changes only its own field. A `with` rebuilding from the defaults would
        // pass everything above and fail here -- and would do it by quietly restoring the
        // placeholder locale, which no rendering test would report as a locale problem.
        val again = config.withUrlOpener(UrlOpener { })
        assertEquals(emptyList(), again.catalogs)
        assertSame(SHOUTING, again.locale)
        assertSame(clock, again.clock)
        assertEquals(evaluation, again.evaluationLimits)
        assertEquals(validation, again.validationLimits)
        // The last link of the chain above, so only a derivation on top can show it carries.
        assertEquals(render, again.renderLimits)
    }

    @Test
    fun a_configuration_does_not_alias_the_catalog_list_it_was_given() {
        // `@Immutable` says this value's properties do not change after construction, and without
        // the copy in the primary constructor the caller keeps the ability to break that: drop the
        // `.toList()` and `config.catalogs` comes back empty here.
        //
        // What this does *not* pin is the cast -- `toList()` of one element is `listOf`, an
        // `ArrayList` on Kotlin/JS -- because defeating the type system deliberately is not a
        // promise the class can keep. See the property's own documentation.
        val given = mutableListOf(BasicCatalog.definition)
        val config = A2uiRendererConfig.Default.withCatalogs(given)
        given.clear()
        assertEquals(listOf(BasicCatalog.definition), config.catalogs)
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
                .withEvaluationLimits(EvaluationLimits(maxResultLength = 7))
                .withValidationLimits(ValidationLimits(maxViolations = 3))
                .withRenderLimits(RenderLimits(maxInstances = 11)),
        )
        assertEquals(emptyList(), renderer.catalogs)
        assertSame(SHOUTING, renderer.locale)
        assertSame(opener, renderer.urlOpener)
        assertSame(clock, renderer.clock)
        assertEquals(7, renderer.evaluationLimits.maxResultLength)
        // Non-default on purpose. Asserting `ValidationLimits.DEFAULT` here would hold just as well
        // for a getter that ignored `config` and returned the default -- and `validationLimits` is
        // the one of the seven that no other test in the suite drives with a value of its own, so
        // that getter would have had no live test at all.
        assertEquals(3, renderer.validationLimits.maxViolations)
        assertEquals(11, renderer.renderLimits.maxInstances)
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
