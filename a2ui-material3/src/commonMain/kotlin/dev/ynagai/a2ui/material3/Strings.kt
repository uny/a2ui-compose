package dev.ynagai.a2ui.material3

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The handful of words this module has to supply itself, for the host to translate.
 *
 * **Almost every string a surface shows came out of the agent's payload**, and that is deliberate:
 * the agent chose the surface's language, and a renderer that mixed its own English into it would
 * be answering a question nobody asked it. Three words cannot come from there. A picker dialog has
 * a confirm and a cancel button, and a filterable `ChoicePicker` has a search field, and none of
 * the three has any text in the catalog to borrow -- but an unlabelled button is not a solution
 * either: it announces nothing to a screen reader, which for a dialog's only two controls means
 * the dialog cannot be operated without sight.
 *
 * So they are named here and the host may replace them, the same shape [A2uiImageLoader] uses for
 * the same reason -- the library declines to decide something that belongs to the application:
 *
 * ```
 * CompositionLocalProvider(
 *     LocalA2uiStrings provides A2uiStrings(confirm = "決定", cancel = "取消", filter = "絞り込み"),
 * ) {
 *     A2uiSurface(renderer, surfaceId, Material3Components.Basic)
 * }
 * ```
 *
 * The defaults are English because something has to be on screen when a host says nothing, and an
 * empty string would be the unlabelled button again.
 */
public data class A2uiStrings(
    /** Accepts a picker's selection. */
    public val confirm: String = "OK",
    /** Closes a picker without changing anything. */
    public val cancel: String = "Cancel",
    /** Names the search field a `filterable` `ChoicePicker` puts above its options. */
    public val filter: String = "Filter",
)

/**
 * The words this module uses, which a host may replace -- see [A2uiStrings].
 *
 * Non-null, unlike [LocalA2uiImageLoader]: there is always something reasonable to draw here,
 * whereas there is no sensible default way to fetch an image.
 */
public val LocalA2uiStrings: ProvidableCompositionLocal<A2uiStrings> =
    staticCompositionLocalOf { A2uiStrings() }
