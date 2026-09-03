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
    catalogs: List<CatalogDefinition>,
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
    /**
     * The catalogs this renderer can resolve.
     *
     * A component naming one that is absent renders as a placeholder rather than raising, because
     * the specification requires missing references to degrade rather than fail.
     *
     * Copied, not aliased. `@Immutable` promises Compose that this value never changes after
     * construction, and a caller who kept a reference to the `MutableList` they handed to
     * [withCatalogs] could otherwise break that promise by emptying it afterwards. The copy ends
     * that, and its reach is exactly that far: a list passed to [withCatalogs] becomes the derived
     * configuration's, never [Default]'s.
     *
     * It does **not** make the list unmodifiable, and cannot: `toList()` of a single element is
     * `listOf`, which on Kotlin/JS is an `ArrayList` at runtime, so a host that casts what it is
     * handed back to `MutableList` can still edit it -- and doing so to [Default]'s own list would
     * reach every renderer built from the default afterwards. That is the type system being
     * deliberately defeated rather than a promise being broken quietly, which is why it is written
     * down here rather than defended against.
     */
    public val catalogs: List<CatalogDefinition> = catalogs.toList()

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

    /** This configuration bounding evaluation by [evaluationLimits] instead. */
    public fun withEvaluationLimits(evaluationLimits: EvaluationLimits): A2uiRendererConfig =
        with(evaluationLimits = evaluationLimits)

    /** This configuration bounding validation by [validationLimits] instead. */
    public fun withValidationLimits(validationLimits: ValidationLimits): A2uiRendererConfig =
        with(validationLimits = validationLimits)

    /** This configuration bounding composition by [renderLimits] instead. */
    public fun withRenderLimits(renderLimits: RenderLimits): A2uiRendererConfig =
        with(renderLimits = renderLimits)

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

    /**
     * Two configurations are equal when all seven settings are.
     *
     * Here so a host can key a `remember` on this value. Without it every derivation is a fresh
     * identity, so the natural Compose spelling
     *
     * ```kotlin
     * val config = A2uiRendererConfig.Default.withUrlOpener(opener)
     * val renderer = remember(config) { A2uiRenderer(config) }
     * ```
     *
     * would rebuild the renderer on every recomposition -- discarding every surface it holds, every
     * data-model write, and whatever the user was in the middle of typing.
     *
     * **Partial by nature, and knowing where it stops is the point.** [locale], [urlOpener] and
     * [clock] are function interfaces, whose instances compare by identity: two configurations
     * derived from `systemLocaleFormatter()` called twice are unequal and stay unequal. A host that
     * wants the key above to hold must hold those values still, exactly as it must for any lambda
     * it lets reach a `remember` key. The other four settings compare by value.
     */
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is A2uiRendererConfig &&
                catalogs == other.catalogs &&
                locale == other.locale &&
                urlOpener == other.urlOpener &&
                clock == other.clock &&
                evaluationLimits == other.evaluationLimits &&
                validationLimits == other.validationLimits &&
                renderLimits == other.renderLimits
            )

    override fun hashCode(): Int {
        var result = catalogs.hashCode()
        result = 31 * result + locale.hashCode()
        result = 31 * result + urlOpener.hashCode()
        result = 31 * result + clock.hashCode()
        result = 31 * result + evaluationLimits.hashCode()
        result = 31 * result + validationLimits.hashCode()
        result = 31 * result + renderLimits.hashCode()
        return result
    }

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
