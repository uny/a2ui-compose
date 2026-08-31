package dev.ynagai.a2ui.material3

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.rememberString

/**
 * `Video` -- the frame a player will occupy, with the poster in it.
 *
 * **Nothing plays, and that is the shipped behaviour rather than a gap left open.** The guide asks
 * for "a native video player component with user controls enabled", and there is no such component
 * in Compose Multiplatform: playback means a per-platform media stack -- ExoPlayer, AVPlayer, an
 * HTML `<video>` element -- which is an engine in each of four targets, inside a module whose job
 * is a design-system adapter. So this draws what a player would occupy and says what is there, and
 * a host with a media stack registers its own `Video` renderer through
 * [ComponentRegistry.with][dev.ynagai.a2ui.compose.ComponentRegistry.with] -- the escape hatch
 * `Icon` documents, used here for the same reason.
 *
 * That is a weaker promise than the guide's and it is made visibly rather than silently: the frame
 * carries a play glyph that does not depress, so a surface with a `Video` on it reads as a video
 * that this host cannot play rather than as a video that is broken.
 *
 * **`posterUrl` is drawn, and `url` is never fetched.** The poster goes through the same
 * [LocalA2uiImageLoader] every `Image` does, under the same `http`/`https` restriction, so a host
 * that has wired up images gets the still for free. The video URL is not touched at all -- not
 * even to probe it -- because fetching it is exactly the capability this renderer does not have.
 *
 * The frame is 16:9 and fills the width it is offered, which is the guide's "span the full width
 * of the parent's container". A row grants it a share rather than letting it take the whole width
 * -- see `claimsMainAxis` in `Layout.kt`.
 */
public val VideoRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val poster = scope.rememberString("posterUrl")
    val loader = LocalA2uiImageLoader.current
    Box(
        modifier
            .leafMargin()
            .fillMaxWidth()
            .aspectRatio(VIDEO_ASPECT)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (loader != null && poster != null && poster.isFetchable()) {
            // Cropped rather than fitted: the still is the backdrop for the glyph in front of it,
            // and a letterboxed poster inside an already-letterboxed frame is two sets of bars.
            // `matchParentSize` rather than `fillMaxWidth` is what makes that true -- a loader is
            // promised a modifier that already carries the size, and given only a width it
            // resolves the height from the source's own ratio, which is `Crop` cropping nothing
            // and the bars back. It also keeps the poster out of the frame's own measurement,
            // which is the half a `fillMaxSize` here would get wrong.
            loader.Image(poster, null, ContentScale.Crop, Modifier.matchParentSize())
        }
        PlayGlyph(size = VIDEO_GLYPH)
    }
}

/**
 * `AudioPlayer` -- the same promise as [VideoRenderer], in a bar rather than a frame.
 *
 * Nothing plays here either, and the note on [VideoRenderer] is the whole of why. What is
 * different is that the catalog gives an `AudioPlayer` a `description` -- "a title or summary" --
 * so unlike a video this one has words of the agent's own to show, and it shows them: an audio
 * component that drew only a transport bar would be an unlabelled control in a surface whose text
 * is the entire point of the component. The description doubles as the accessible name, which is
 * the [ImageRenderer] pattern -- the part of a medium a renderer without the medium can still
 * deliver.
 *
 * A bar spanning its container, per the guide's "like video, its container should span the full
 * width of its parent".
 */
public val AudioPlayerRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val description = scope.rememberString("description")
    Row(
        modifier
            .leafMargin()
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(AUDIO_PADDING)
            // On the bar rather than on the glyph, and merging rather than beside: without
            // `mergeDescendants` the bar's description and the `Text` below it are two nodes
            // carrying the same string, and a screen reader stops at both and says it twice.
            // Merged, it reads the component once, as the thing it is. The glyph itself is
            // described by nothing, which is `Icon`'s own rule for a leaf that repeats what the
            // text beside it already says.
            .semantics(mergeDescendants = true) { description?.let { contentDescription = it } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayGlyph(size = ICON_SIZE)
        if (description != null) {
            Text(
                text = description,
                modifier = Modifier.padding(start = AUDIO_PADDING),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The transport glyph both media components show.
 *
 * Taken from [ICON_PATHS], which is the catalog's closed enum of icon *names* -- borrowing a glyph
 * out of it is not the same as adding one to it, and `DateTimeInput`'s calendar does the same.
 * Described by nothing: it is not a control, and announcing "play" for something that will not
 * play is worse than the silence.
 */
@Composable
private fun PlayGlyph(size: Dp) {
    val vector = remember { ICON_PATHS[PLAY_GLYPH]?.let { iconVector(it) } }
    if (vector != null) {
        Icon(
            imageVector = vector,
            contentDescription = null,
            modifier = Modifier.size(size),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The frame a 16:9 video occupies. Every video in the corpus is landscape; none names a ratio. */
private const val VIDEO_ASPECT = 16f / 9f

/** Large enough to read as the centre of the frame rather than as an icon dropped into it. */
private val VIDEO_GLYPH = 56.dp

/** Between the glyph, the description, and the bar's own edge. */
private val AUDIO_PADDING = 12.dp

private const val PLAY_GLYPH = "play"
