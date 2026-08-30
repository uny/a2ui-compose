package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.RenderChild
import dev.ynagai.a2ui.compose.rememberChildren

/**
 * `Card` -- a visible boundary around exactly one child.
 *
 * **Outlined and transparent rather than filled**, which is the implementation guide's own
 * recommendation and not a styling preference. §4 puts the problem plainly: a card inside another
 * card has to stay distinct, and the obvious fix -- alternating surface colours by depth -- means
 * every renderer carries a depth counter and every component below one has to be told what it is
 * sitting on. A transparent container with a 1dp outline nests to any depth by drawing an inner
 * boundary inside the outer one, and costs no context passing at all.
 *
 * The child is drawn inside a `Column` holding the guide's 16dp inner padding. That padding is
 * *localised* in the guide's sense -- it keeps the content off the card's own border and is not
 * part of the outer layout, which is what [leafMargin] carries.
 *
 * The catalog gives a card exactly one `child`, and a payload wanting more is told to wrap them in
 * a `Column`. Nothing here enforces that: the children are iterated, so a payload the schema
 * refuses draws all of what it named rather than silently dropping the tail. `CatalogValidator` is
 * where a caller that wants the refusal asks for it.
 */
public val CardRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val children = scope.rememberChildren("child")
    OutlinedCard(
        modifier = modifier.leafMargin(),
        shape = MaterialTheme.shapes.medium,
        // Transparent, not the theme's surface colour. `outlinedCardColors` would otherwise paint
        // the card opaque, and a nested card would then be an invisible rectangle inside an
        // identically coloured one.
        //
        // The content colour is named too, and has to be: the default is `contentColorFor` of the
        // container, and no theme colour maps from transparent -- so it resolves to `Unspecified`,
        // which the card then provides as `LocalContentColor` to everything inside it. Inheriting
        // the surrounding colour instead is also §4's rule of thumb for leaves, applied to the
        // container that would otherwise overwrite it.
        colors = CardDefaults.outlinedCardColors(
            containerColor = Color.Transparent,
            contentColor = LocalContentColor.current,
        ),
    ) {
        Column(Modifier.padding(CARD_PADDING)) {
            children.forEach { scope.RenderChild(it) }
        }
    }
}

/** The guide's inner padding, "e.g. 16dp" -- content kept clear of the card's own outline. */
private val CARD_PADDING = 16.dp
