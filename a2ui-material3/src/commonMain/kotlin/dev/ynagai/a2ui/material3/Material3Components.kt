package dev.ynagai.a2ui.material3

import dev.ynagai.a2ui.compose.ComponentRegistry

/**
 * The A2UI basic catalog drawn with Material 3.
 *
 * A registry to hand to `A2uiSurface`. The composition it draws into must have a `MaterialTheme`
 * above it: every renderer here reads the theme's typography and colour scheme, which is the point
 * of a design-system adapter rather than an accident of how they are written.
 *
 * **Fourteen of the catalog's eighteen components, and the other four are absent on purpose.**
 * A component this registry does not know draws as an
 * [UnknownType][dev.ynagai.a2ui.compose.A2uiPlaceholderReason.UnknownType] placeholder rather than
 * as nothing, so a surface using one is visibly incomplete instead of quietly wrong. Adding the
 * rest is what the remaining work is; a host that has written its own can pass them to
 * [ComponentRegistry.with] and draw a mixed surface today. Missing: `Video`, `AudioPlayer`,
 * `Tabs` and `Modal`.
 *
 * `Image` draws whatever [LocalA2uiImageLoader] is set to, and a described placeholder when the
 * host has set none -- this module fetches nothing itself. See [A2uiImageLoader].
 *
 * **Three words come from the host rather than from the payload.** A picker dialog's confirm and
 * cancel buttons and a `filterable` `ChoicePicker`'s search field have no text in the catalog to
 * borrow, and leaving them unnamed makes them unusable with a screen reader -- so they are named
 * through [LocalA2uiStrings], English until a host provides otherwise. Everything else on a
 * surface is the agent's own words.
 *
 * `checks` -- the catalog's `Checkable` mixin -- is honoured by every component that carries it. A
 * `Button` whose check fails is disabled, which is what the protocol asks for by name; a failing
 * input is captioned with the message. See [checkFailures][dev.ynagai.a2ui.compose.checkFailures],
 * where the rules are evaluated, which a host writing its own input renderer can call for the same
 * behaviour.
 */
public object Material3Components {
    /** The fourteen components this module draws. */
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
            "CheckBox" to CheckBoxRenderer,
            "ChoicePicker" to ChoicePickerRenderer,
            "Slider" to SliderRenderer,
            "DateTimeInput" to DateTimeInputRenderer,
        ),
    )
}
