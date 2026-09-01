package dev.ynagai.a2ui.material3

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
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
 * **A bordered `Box` rather than Material 3's `OutlinedCard`, and that is a workaround.** The two
 * draw the same thing here -- a transparent rounded rectangle with a 1dp outline -- because that is
 * all this component is allowed to be. What is given up is not paint but *touch*: a `Surface` is
 * hit-testable across its whole area and a `Box` is not, which is why the chain below ends in an
 * empty `pointerInput` and why deleting that line is a behaviour change rather than a tidy-up.
 * What `OutlinedCard`
 * brings with it is Material 3's non-interactive `Surface`, and a `Surface` whose children are
 * drawn through [RenderChild] crashes Kotlin/Native: replacing the content of an `A2uiSurface` that
 * sits in a scrolling (unbounded-height) parent segfaults in `AtomicInt.compareAndSet`, with a
 * stack that cannot be unwound. That is the placement this library documents as usual, so it is not
 * an exotic case -- it took down half the specification's own corpus in the Gallery, on macOS and
 * iOS both.
 *
 * The four conditions are all required, and each on its own is fine: the same swap with a bounded
 * height, the same `Surface` with a literal child instead of [RenderChild], the same [RenderChild]
 * in a plain `Box`, and a bare `OutlinedCard` with these colours and no `A2uiSurface` around it all
 * pass. `Button`'s clickable `Surface` overload passes too, so the blast radius is this component.
 * JVM and both web targets never reproduced it. Neither half is wrong on its own, which is why this
 * is a workaround rather than a fix: the defect is below both of them.
 *
 * The catalog gives a card exactly one `child`, and a payload wanting more is told to wrap them in
 * a `Column`. Nothing here enforces that: the children are iterated, so a payload the schema
 * refuses draws all of what it named rather than silently dropping the tail. `CatalogValidator` is
 * where a caller that wants the refusal asks for it.
 */
public val CardRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val children = scope.rememberChildren("child")
    Box(
        modifier
            .leafMargin()
            // Border before clip, which is `Surface`'s own order and not an arbitrary one.
            // `Modifier.border` insets a rounded stroke by half its width, so the outer edge of
            // the hairline lands exactly on the shape's outline -- the same path a preceding
            // `clip` would antialias it against, thinning a 1dp line that has nothing to spare.
            // Clipping after leaves the stroke whole and still clips the content, which is the
            // only thing the clip is here for.
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium)
            // The one thing a bare `Box` does give up, restored by hand. A `Surface` carries an
            // empty `pointerInput` and is therefore *opaque to touches* everywhere it is drawn,
            // including where it is transparent to the eye; a `Box` is hit-testable only where a
            // child is. Measured, not assumed: with this line deleted, a tap on the card's own
            // 16dp padding falls through to whatever the host composed behind the surface, and
            // with it the tap stops at the card exactly as it did under `OutlinedCard`. Nothing
            // in the catalog can stack a card over a control -- `Row` and `Column` do not overlap
            // -- so the payload cannot reach this, but a host that draws `A2uiSurface` over its
            // own chrome can, and did not have to think about it before.
            .pointerInput(Unit) {},
    ) {
        Column(Modifier.padding(CARD_PADDING)) {
            children.forEach { scope.RenderChild(it) }
        }
    }
}

/** The guide's inner padding, "e.g. 16dp" -- content kept clear of the card's own outline. */
private val CARD_PADDING = 16.dp
