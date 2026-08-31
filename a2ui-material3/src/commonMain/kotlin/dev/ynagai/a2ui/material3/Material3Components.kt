package dev.ynagai.a2ui.material3

import dev.ynagai.a2ui.compose.ComponentRegistry

/**
 * The A2UI basic catalog drawn with Material 3.
 *
 * A registry to hand to `A2uiSurface`. The composition it draws into must have a `MaterialTheme`
 * above it: every renderer here reads the theme's typography and colour scheme, which is the point
 * of a design-system adapter rather than an accident of how they are written.
 *
 * **All eighteen of the catalog's components**, which is every component the specification's
 * forty-three examples name. A component this registry does not know -- one from another catalog
 * -- draws as an [UnknownType][dev.ynagai.a2ui.compose.A2uiPlaceholderReason.UnknownType]
 * placeholder rather than as nothing, so a surface using one is visibly incomplete instead of
 * quietly wrong, and a host that has written its own can pass them to [ComponentRegistry.with].
 *
 * **`Video` and `AudioPlayer` draw a media component's frame but play nothing.** There is no
 * player in Compose Multiplatform to draw, and shipping one means a media stack per target; a host
 * that has one registers its own renderer for the two. See [VideoRenderer], which says what that
 * does and does not promise.
 *
 * `Image` draws whatever [LocalA2uiImageLoader] is set to, and a described placeholder when the
 * host has set none -- this module fetches nothing itself. See [A2uiImageLoader].
 *
 * **Four words come from the host rather than from the payload.** A picker dialog's confirm and
 * cancel buttons, a `filterable` `ChoicePicker`'s search field and a `Modal`'s close button have
 * no text in the catalog to borrow, and leaving them unnamed makes them unusable with a screen
 * reader -- so they are named through [LocalA2uiStrings], English until a host provides
 * otherwise. Everything else on a surface is the agent's own words.
 *
 * **`Tabs` and `Modal` hold state the agent cannot see.** A tab strip's selected index and a
 * modal's open-ness are the renderer's, because the catalog gives neither component a property to
 * bind them to -- the guide says as much for `Tabs`. A `Modal` also intercepts its trigger's taps,
 * so a trigger carrying an `action` does not dispatch it; see [ModalRenderer].
 *
 * `checks` -- the catalog's `Checkable` mixin -- is honoured by every component that carries it. A
 * `Button` whose check fails is disabled, which is what the protocol asks for by name; a failing
 * input is captioned with the message. See [checkFailures][dev.ynagai.a2ui.compose.checkFailures],
 * where the rules are evaluated, which a host writing its own input renderer can call for the same
 * behaviour.
 */
public object Material3Components {
    /** The catalog's eighteen components, all of them. */
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
            "Tabs" to TabsRenderer,
            "Modal" to ModalRenderer,
            "Video" to VideoRenderer,
            "AudioPlayer" to AudioPlayerRenderer,
        ),
    )
}
