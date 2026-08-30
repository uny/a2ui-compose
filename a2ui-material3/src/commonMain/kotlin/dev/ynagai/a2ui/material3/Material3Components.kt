package dev.ynagai.a2ui.material3

import dev.ynagai.a2ui.compose.ComponentRegistry

/**
 * The A2UI basic catalog drawn with Material 3.
 *
 * A registry to hand to `A2uiSurface`. The composition it draws into must have a `MaterialTheme`
 * above it: every renderer here reads the theme's typography and colour scheme, which is the point
 * of a design-system adapter rather than an accident of how they are written.
 *
 * **Ten of the catalog's eighteen components, and the other eight are absent on purpose.**
 * A component this registry does not know draws as an
 * [UnknownType][dev.ynagai.a2ui.compose.A2uiPlaceholderReason.UnknownType] placeholder rather than
 * as nothing, so a surface using one is visibly incomplete instead of quietly wrong. Adding the
 * rest is what the remaining work is; a host that has written its own can pass them to
 * [ComponentRegistry.with] and draw a mixed surface today. Missing: `Video`, `AudioPlayer`,
 * `Tabs`, `Modal`, `CheckBox`, `ChoicePicker`, `Slider` and `DateTimeInput`.
 *
 * `Image` draws whatever [LocalA2uiImageLoader] is set to, and a described placeholder when the
 * host has set none -- this module fetches nothing itself. See [A2uiImageLoader].
 *
 * Not covered here: `checks`, the renderer-side validation the catalog folds into `Button` and
 * `TextField`. The rules parse -- `CheckRule` is in the core -- but nothing here evaluates them or
 * shows a validation message, so a payload carrying `checks` renders as though it carried none.
 */
public object Material3Components {
    /** The ten components this module draws. */
    public val Basic: ComponentRegistry = ComponentRegistry(
        mapOf(
            "Text" to TextRenderer,
            "Row" to RowRenderer,
            "Column" to ColumnRenderer,
            "Button" to ButtonRenderer,
            "TextField" to TextFieldRenderer,
            "Card" to CardRenderer,
            "Divider" to DividerRenderer,
            "List" to ListRenderer,
            "Icon" to IconRenderer,
            "Image" to ImageRenderer,
        ),
    )
}
