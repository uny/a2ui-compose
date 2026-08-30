package dev.ynagai.a2ui.material3

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.rememberString

/**
 * How a host draws the image at a URL.
 *
 * **This module does not fetch anything, and that is deliberate.** Compose Multiplatform has no
 * remote image of its own, so drawing one means an HTTP stack and a cache -- an engine-sized
 * dependency in a module whose job is a design-system adapter. A host almost always already has
 * one, and a host that does not can add Coil in a line:
 *
 * ```kotlin
 * CompositionLocalProvider(
 *     LocalA2uiImageLoader provides A2uiImageLoader { url, description, scale, modifier ->
 *         AsyncImage(model = url, contentDescription = description, contentScale = scale, modifier = modifier)
 *     },
 * ) { A2uiSurface(/* ... */) }
 * ```
 *
 * The [modifier] handed in already carries the size the variant asks for and the margin the layout
 * needs; an implementation should pass it to whatever it draws rather than sizing the image itself.
 *
 * **[url] is the agent's string, and fetching it is a capability this module is handing over.**
 * The core makes the same handover for `openUrl` and states its half of the bargain: `UrlOpener`
 * receives a URL "guaranteed to be absolute and `http`- or `https`-schemed", because the evaluator
 * applied the specification's allowlist first. There is no equivalent guarantee here -- a renderer
 * has no scheme allowlist to apply and the catalog states none -- so an implementation is handed
 * whatever the agent wrote, and it is the implementation that has to decide what to do about it:
 *
 * - an image loader will resolve `file://` and, on Android, `content://`, so a payload can name a
 *   local path and learn from the drawing whether it was readable;
 * - an `https` URL the agent chose is fetched the moment the surface composes, with no gesture,
 *   which discloses the viewer's address and user agent to whoever the agent named.
 *
 * Neither is hypothetical for an agent that is prompt-injected rather than merely wrong. A host
 * that fetches should restrict the scheme, and one that renders untrusted agents should also decide
 * whether an image may be fetched before the user has asked to see it.
 */
@Stable
public fun interface A2uiImageLoader {
    /**
     * Draws the image at [url], sized and inset by [modifier].
     *
     * [url] is agent-authored and unvalidated -- see the note on this interface.
     */
    @Composable
    public fun Image(url: String, description: String?, scale: ContentScale, modifier: Modifier)
}

/**
 * The image loader in effect, or null when the host has provided none.
 *
 * Without one an `Image` draws the sized, described placeholder [ImageRenderer] falls back to -- a
 * surface that is visibly incomplete rather than one that is silently missing a picture, and a test
 * suite that never reaches the network.
 */
public val LocalA2uiImageLoader: ProvidableCompositionLocal<A2uiImageLoader?> =
    staticCompositionLocalOf { null }

/**
 * `Image` -- the picture at a URL, drawn by the host's loader.
 *
 * `variant` chooses the size and `fit` the scaling, both as the implementation guide lays them out.
 * The variants that name a fixed square get one; the two that the guide calls full-width --
 * `largeFeature` and `header` -- fill the width they are offered, and `mediumFeature`, the default,
 * fills its container but stops at 300dp.
 *
 * That 300dp cap is doing more than following the guide. A `fillMaxWidth` taken inside a `Row`
 * resolves against the width the *grandparent* offered rather than this image's share of the row,
 * so an uncapped image beside anything else takes the whole row and leaves its siblings at zero --
 * the failure `TextField` carries a note about. A bounded `widthIn` cannot do that, which is why
 * the default variant has one and the two banner variants, which the guide means to sit alone at
 * the top of a surface, do not.
 */
public val ImageRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val url = scope.rememberString("url")
    val description = scope.rememberString("description")
    val variant = scope.rememberString("variant")
    val fit = scope.rememberString("fit")
    // The shape is part of what `variant` means -- "a hint for the image size and style" -- so it
    // is taken before the branch rather than inside the placeholder. Applied only there, an
    // `avatar` was a circle exactly while it was missing and a square once a host drew it.
    val sized = modifier.leafMargin().variantSize(variant)
        .clip(if (variant == "avatar") CircleShape else MaterialTheme.shapes.small)
    val loader = LocalA2uiImageLoader.current
    if (loader != null && url != null) {
        loader.Image(url, description, contentScale(fit), sized)
    } else {
        Box(
            sized
                .background(MaterialTheme.colorScheme.surfaceVariant)
                // The description still reaches the accessibility tree. It is the one part of an
                // image a renderer without a loader can still deliver, and the catalog asks for it
                // precisely so that the picture is not the only way to know what is there.
                .semantics { description?.let { contentDescription = it } },
        )
    }
}

/** The size the guide gives each `variant`; `mediumFeature` is the catalog's default. */
private fun Modifier.variantSize(variant: String?): Modifier = when (variant) {
    "icon" -> size(24.dp)
    "avatar" -> size(40.dp)
    "smallFeature" -> size(100.dp)
    "largeFeature" -> fillMaxWidth().height(320.dp)
    "header" -> fillMaxWidth().height(200.dp)
    else -> widthIn(max = 300.dp).fillMaxWidth().height(200.dp)
}

/**
 * `fit` as a Compose scaling mode -- the guide's "platform's equivalent content scaling mode".
 *
 * The catalog's default is `fill`, which is CSS's `object-fit: fill`: stretch to the box, aspect
 * ratio not preserved. `FillBounds` is that, and is not Compose's own default.
 */
private fun contentScale(fit: String?): ContentScale = when (fit) {
    "contain" -> ContentScale.Fit
    "cover" -> ContentScale.Crop
    "none" -> ContentScale.None
    "scaleDown" -> ContentScale.Inside
    else -> ContentScale.FillBounds
}
