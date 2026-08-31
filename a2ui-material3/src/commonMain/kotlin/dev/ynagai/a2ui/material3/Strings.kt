package dev.ynagai.a2ui.material3

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The handful of words this module has to supply itself, for the host to translate.
 *
 * **Almost every string a surface shows came out of the agent's payload**, and that is deliberate:
 * the agent chose the surface's language, and a renderer that mixed its own English into it would
 * be answering a question nobody asked it. Five words cannot come from there. A picker dialog has
 * a confirm and a cancel button, a filterable `ChoicePicker` has a search field, a `Modal` has the
 * button that closes it, a `Video` has the frame itself, and none of the five has any text in the
 * catalog to borrow -- but leaving them unnamed is not a solution either: an unlabelled control
 * announces nothing to a screen reader, which for a dialog's only controls means the dialog cannot
 * be operated without sight, and an unnamed region is one a reader passes over as if it were not
 * there.
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
    /**
     * Names the button that dismisses a `Modal`.
     *
     * A glyph rather than a word on screen -- an "X" is what the guide names first, and it is the
     * one control a dialog can afford to draw small. This is its accessible name, which is the
     * half a glyph cannot supply.
     */
    public val close: String = "Close",
    /**
     * Names the frame a `Video` draws.
     *
     * The one component in the catalog with nothing of the agent's own to say: an `AudioPlayer`
     * has a `description` and an `Image` has one too, and a `Video` has neither -- only a `url`
     * this module does not fetch and a `posterUrl` that is a picture. Without a word from here a
     * screen reader finds nothing at all where the frame is, which reads as an empty surface
     * rather than as a video, so this is the whole of what a reader gets to know it is there.
     */
    public val video: String = "Video",
)

/**
 * The words this module uses, which a host may replace -- see [A2uiStrings].
 *
 * Non-null, unlike [LocalA2uiImageLoader]: there is always something reasonable to draw here,
 * whereas there is no sensible default way to fetch an image.
 */
public val LocalA2uiStrings: ProvidableCompositionLocal<A2uiStrings> =
    staticCompositionLocalOf { A2uiStrings() }
