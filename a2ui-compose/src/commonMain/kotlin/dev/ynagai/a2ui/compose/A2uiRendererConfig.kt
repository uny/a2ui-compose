package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Immutable
import dev.ynagai.a2ui.core.function.EvaluationLimits
import dev.ynagai.a2ui.core.function.FallbackLocaleFormatter
import dev.ynagai.a2ui.core.function.LocaleFormatter
import dev.ynagai.a2ui.core.function.UrlOpener
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.surface.RenderLimits
import dev.ynagai.a2ui.core.validation.ValidationLimits

/**
 * Everything an [A2uiRenderer] is configured with, as one value.
 *
 * **This type exists so that the renderer's constructor is not its compatibility surface.** Those
 * seven settings used to be seven defaulted constructor parameters, which is pleasant to write and
 * impossible to extend: a caller naming one of them bound to the synthetic defaults constructor, whose descriptor carries the parameter list *and* a bitmask, so an eighth
 * setting would break every consumer compiled against the old one -- with `NoSuchMethodError` at
 * runtime rather than a recompile prompt. Unlike a constructor of required parameters, that one
 * cannot be preserved by hand. Here a new setting adds a `withX` and breaks nothing.
 *
 * Start from [Default] and derive:
 *
 * ```kotlin
 * val renderer = remember {
 *     A2uiRenderer(
 *         A2uiRendererConfig.Default
 *             .withLocale(systemLocaleFormatter())
 *             .withUrlOpener(opener),
 *     )
 * }
 * ```
 *
 * Note what is *not* here: `initialState`. A seed for the renderer's own mutable state is not a
 * setting, and putting it here would invite a config to be shared between renderers that then
 * disagreed about whose state it was.
 */
@Immutable
public class A2uiRendererConfig private constructor(
    /**
     * The catalogs this renderer can resolve.
     *
     * A component naming one that is absent renders as a placeholder rather than raising, because
     * the specification requires missing references to degrade rather than fail.
     */
    public val catalogs: List<CatalogDefinition>,
    /**
     * How the four locale-sensitive functions format.
     *
     * The default is locale-independent and English-shaped -- **a placeholder**, as
     * [FallbackLocaleFormatter]'s own documentation says. `systemLocaleFormatter()` reads the
     * device's locale and `localeFormatter(tag)` takes a fixed one. Opt-in on purpose: a renderer
     * that read the device by default would make one payload render differently in CI than on a
     * desk.
     */
    public val locale: LocaleFormatter,
    /**
     * Where `openUrl` sends a URL.
     *
     * The default does nothing, since a library should not navigate its host's window uninvited;
     * `rememberPlatformUrlOpener()` is the platform's own.
     */
    public val urlOpener: UrlOpener,
    /** The source of the ISO 8601 timestamp every action message carries. */
    public val clock: A2uiClock,
    /** What bounds one function evaluation. */
    public val evaluationLimits: EvaluationLimits,
    /** What bounds one validation pass. */
    public val validationLimits: ValidationLimits,
    /** What bounds one surface's composition. */
    public val renderLimits: RenderLimits,
) {
    /** This configuration resolving components against [catalogs] instead. */
    public fun withCatalogs(catalogs: List<CatalogDefinition>): A2uiRendererConfig =
        with(catalogs = catalogs)

    /** This configuration formatting through [locale] instead. @see A2uiRendererConfig.locale */
    public fun withLocale(locale: LocaleFormatter): A2uiRendererConfig = with(locale = locale)

    /** This configuration opening URLs through [urlOpener] instead. */
    public fun withUrlOpener(urlOpener: UrlOpener): A2uiRendererConfig =
        with(urlOpener = urlOpener)

    /** This configuration timestamping through [clock] instead. */
    public fun withClock(clock: A2uiClock): A2uiRendererConfig = with(clock = clock)

    /** This configuration bounding evaluation by [limits]. */
    public fun withEvaluationLimits(limits: EvaluationLimits): A2uiRendererConfig =
        with(evaluationLimits = limits)

    /** This configuration bounding validation by [limits]. */
    public fun withValidationLimits(limits: ValidationLimits): A2uiRendererConfig =
        with(validationLimits = limits)

    /** This configuration bounding composition by [limits]. */
    public fun withRenderLimits(limits: RenderLimits): A2uiRendererConfig =
        with(renderLimits = limits)

    /** The private copy every derivation goes through. No setting here has a meaningful null, so
     * a defaulted parameter says "unchanged" unambiguously. */
    private fun with(
        catalogs: List<CatalogDefinition> = this.catalogs,
        locale: LocaleFormatter = this.locale,
        urlOpener: UrlOpener = this.urlOpener,
        clock: A2uiClock = this.clock,
        evaluationLimits: EvaluationLimits = this.evaluationLimits,
        validationLimits: ValidationLimits = this.validationLimits,
        renderLimits: RenderLimits = this.renderLimits,
    ): A2uiRendererConfig = A2uiRendererConfig(
        catalogs = catalogs,
        locale = locale,
        urlOpener = urlOpener,
        clock = clock,
        evaluationLimits = evaluationLimits,
        validationLimits = validationLimits,
        renderLimits = renderLimits,
    )

    public companion object {
        /**
         * The basic catalog, the placeholder locale, a URL opener that does nothing, the system
         * clock, and the default limits.
         *
         * A single shared value rather than a constructor: there is one way to start, and it
         * allocates nothing per renderer.
         */
        public val Default: A2uiRendererConfig = A2uiRendererConfig(
            catalogs = listOf(BasicCatalog.definition),
            locale = FallbackLocaleFormatter,
            urlOpener = UrlOpener { },
            clock = A2uiClock.System,
            evaluationLimits = EvaluationLimits.DEFAULT,
            validationLimits = ValidationLimits.DEFAULT,
            renderLimits = RenderLimits.DEFAULT,
        )
    }
}
