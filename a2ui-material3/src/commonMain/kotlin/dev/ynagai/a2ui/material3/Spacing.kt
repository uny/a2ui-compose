package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The uniform outer margin the implementation guide's Leaf-Margin Strategy asks for.
 *
 * The guide's §3 splits the catalog in two. Structural, invisible containers -- `Row`, `Column`,
 * `List` -- carry **no** padding and **no** margin, so that wrapping something in one does not
 * change its spacing and nesting them does not multiply it. Everything else -- the visual leaves
 * (`Text`, `Image`, `Icon`, `Video`, `AudioPlayer`, `Slider`) and the outlined containers and
 * inputs (`Card`, `Button`, `TextField`, `CheckBox`, `ChoicePicker`) -- carries this margin on all
 * four sides.
 *
 * Applied as an outer `padding` on the modifier the parent handed down, which in Compose is what a
 * margin is: `padding` before any background or border in the chain insets the whole component,
 * and the components here take it before they draw anything.
 *
 * Why margins on the leaves rather than a gap on the containers, in the guide's own terms: with
 * `Row(a, b)` and margins on `a` and `b` there is space to the left of `a`, between them, and to
 * the right of `b`, whatever the row is nested inside -- and because the row itself contributes
 * nothing, an arbitrarily deep stack of rows and columns spaces exactly like a flat one.
 */
internal val A2uiLeafMargin = 8.dp

/**
 * This component's share of the Leaf-Margin Strategy -- see [A2uiLeafMargin].
 *
 * Taken first in each renderer's modifier chain, before a weight or a background, so it insets the
 * component rather than its content. A container that is *not* meant to carry it simply does not
 * call this.
 */
internal fun Modifier.leafMargin(): Modifier = padding(A2uiLeafMargin)
