package dev.ynagai.a2ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiPlaceholder
import dev.ynagai.a2ui.compose.A2uiPlaceholderReason
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.compose.ComponentRegistry
import dev.ynagai.a2ui.core.function.systemLocaleFormatter
import dev.ynagai.a2ui.material3.Material3Components

/**
 * The tags the Gallery's own chrome carries, for the integration suite to find it by.
 *
 * Named constants rather than literals in the tests, because these are the one part of a
 * development tool's UI that something else depends on: a pane renamed without its tag moving
 * leaves a test asserting about a node that no longer exists, and `onNodeWithTag` reports that as
 * "not found" rather than as the rename it was.
 *
 * Only the Gallery's chrome is tagged. Nothing inside the preview is -- what a surface draws is
 * the agent's, and a test that reached for it by tag would be asserting about this file rather
 * than about the renderer.
 */
public object GalleryTags {
    public const val SAMPLE_LIST: String = "gallery:samples"
    public const val EXAMPLE_TITLE: String = "gallery:example-title"
    public const val PREVIEW: String = "gallery:preview"
    public const val MESSAGES: String = "gallery:messages"
    public const val ADVANCE: String = "gallery:advance"
    public const val ADVANCE_ALL: String = "gallery:advance-all"
    public const val RESET: String = "gallery:reset"
    public const val STEP_LABEL: String = "gallery:step"
    public const val DATA_MODEL: String = "gallery:data-model"
    public const val ACTION_LOG: String = "gallery:action-log"

    /** The tag of the row that selects [file], e.g. `gallery:sample:00_simple-text.json`. */
    public fun sample(file: String): String = "gallery:sample:$file"
}

/**
 * The width at which the three columns stop fitting side by side.
 *
 * Below it the same three panes are shown one at a time behind a selector. The blueprint asks for
 * three columns, and on a desktop or a browser that is what this draws; a phone-sized window is
 * narrower than any three readable columns, and showing them anyway would make the pane that
 * matters -- the preview -- too narrow to tell a layout bug from a wrapping one.
 */
private val THREE_COLUMN_MIN_WIDTH = 900.dp

/** Which pane the narrow layout is showing. */
private enum class Pane(val title: String) {
    Samples("Samples"),
    Render("Render"),
    Inspect("Inspect"),
}

/**
 * The Gallery: the reference environment for this renderer.
 *
 * Three columns, as the framework adapter blueprint's §7 specifies -- sample navigation, the
 * surface preview with its message stream and stepper, and live inspection of the data model and
 * the actions the surface sent.
 *
 * Applies its own [MaterialTheme]. Every renderer in [Material3Components] reads the theme, so
 * there has to be one; putting it here rather than in each platform's entry point keeps the six of
 * them to the one line each that actually differs.
 *
 * @param state the Gallery's state. Hoisted so the integration suite can drive the same object the
 *   UI does -- see [GalleryState], which is where stepping and the action log live.
 *
 *   The default reads the device's locale, which the library itself deliberately does not: a
 *   renderer that did would make the same payload render differently in CI than on a desk. A
 *   development tool is the one place that trade sits the other way round -- the corpus calls
 *   `formatDate`, `formatNumber` and `formatCurrency` in thirty-odd places, and a Gallery showing
 *   the locale-independent fallback would be showing what no host ever sees. It is also the only
 *   thing that runs `platformLocaleData` on Kotlin/JS, which has no Compose UI test harness.
 *
 *   `urlOpener` is deliberately *not* wired the same way: an `openUrl` action here still does
 *   nothing, because opening one is a capability handed to an agent's payload rather than a
 *   formatter reading the machine.
 * @param registry what draws the surface. The shipped Material 3 registry by default; a host
 *   trying its own component renderer passes [ComponentRegistry.with]'s result.
 */
@Composable
public fun GalleryApp(
    modifier: Modifier = Modifier,
    state: GalleryState = remember { GalleryState(locale = systemLocaleFormatter()) },
    registry: ComponentRegistry = Material3Components.Basic,
) {
    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            BoxWithConstraintsLayout(state = state, registry = registry)
        }
    }
}

@Composable
private fun BoxWithConstraintsLayout(state: GalleryState, registry: ComponentRegistry) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= THREE_COLUMN_MIN_WIDTH) {
            Row(Modifier.fillMaxSize()) {
                SamplesPane(state, Modifier.width(260.dp).fillMaxHeight())
                VerticalDivider()
                RenderPane(state, registry, Modifier.weight(1f).fillMaxHeight())
                VerticalDivider()
                InspectPane(state, Modifier.width(340.dp).fillMaxHeight())
            }
        } else {
            NarrowLayout(state, registry)
        }
    }
}

/**
 * The three panes one at a time, behind a selector.
 *
 * The selector's state is remembered here rather than in [GalleryState]: which pane a narrow
 * window is showing is a fact about this layout, and putting it in the state object would give the
 * integration suite a knob whose value cannot matter to a renderer.
 */
@Composable
private fun NarrowLayout(state: GalleryState, registry: ComponentRegistry) {
    var pane by remember { mutableStateOf(Pane.Render) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (candidate in Pane.entries) {
                val selected = candidate == pane
                if (selected) {
                    Button(onClick = { pane = candidate }) { Text(candidate.title) }
                } else {
                    OutlinedButton(onClick = { pane = candidate }) { Text(candidate.title) }
                }
            }
        }
        HorizontalDivider()
        when (pane) {
            Pane.Samples -> SamplesPane(state, Modifier.fillMaxSize())
            Pane.Render -> RenderPane(state, registry, Modifier.fillMaxSize())
            Pane.Inspect -> InspectPane(state, Modifier.fillMaxSize())
        }
    }
}

/** Left column: every example in the corpus. */
@Composable
private fun SamplesPane(state: GalleryState, modifier: Modifier = Modifier) {
    Column(modifier) {
        PaneTitle("Samples (${state.examples.size})")
        LazyColumn(Modifier.fillMaxSize().testTag(GalleryTags.SAMPLE_LIST)) {
            items(state.examples, key = { it.file }) { example ->
                val selected = example == state.example
                Column(
                    Modifier
                        .fillMaxWidth()
                        .testTag(GalleryTags.sample(example.file))
                        .clickable { state.select(example) }
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = example.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // The filename as well as the title: the corpus is ordered by file, and
                        // several examples share a title prefix, so the file is what tells two
                        // neighbouring rows apart.
                        text = example.file,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Centre column: the surface preview, the stepper, and the message stream. */
@Composable
private fun RenderPane(
    state: GalleryState,
    registry: ComponentRegistry,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = state.example.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(GalleryTags.EXAMPLE_TITLE),
            )
            if (state.example.description.isNotEmpty()) {
                Text(
                    text = state.example.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Stepper(state)
        HorizontalDivider()
        // The preview takes the larger share, and the message stream the smaller: the stream is a
        // list of short rows that scrolls, while a surface that is given too little height is the
        // one thing here that cannot be read by scrolling -- a layout under a squeezed constraint
        // is a different layout.
        SurfacePreview(state, registry, Modifier.weight(2f).fillMaxWidth())
        HorizontalDivider()
        MessageStream(state, Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * The stepper controls, wrapping rather than clipping.
 *
 * A `FlowRow`, not a `Row`: at [THREE_COLUMN_MIN_WIDTH] the two fixed side panes leave the centre
 * under 300dp, which is less than the three buttons and the counter need in a line -- a plain
 * `Row` pushed the counter off the end, where it was neither readable nor findable by tag. How
 * much room the controls want depends on the platform's default type, so the wrap is the fix
 * rather than a wider threshold measured on one of them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Stepper(state: GalleryState) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { state.advance() },
            enabled = state.canAdvance,
            modifier = Modifier.testTag(GalleryTags.ADVANCE),
        ) { Text("Advance") }
        OutlinedButton(
            onClick = { state.advanceAll() },
            enabled = state.canAdvance,
            modifier = Modifier.testTag(GalleryTags.ADVANCE_ALL),
        ) { Text("Run all") }
        OutlinedButton(
            onClick = { state.reset() },
            modifier = Modifier.testTag(GalleryTags.RESET),
        ) { Text("Reset") }
        Text(
            text = "${state.cursor} / ${state.example.raw.size}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag(GalleryTags.STEP_LABEL),
        )
    }
}

/**
 * The surface itself, drawn by [registry].
 *
 * The placeholder is a visible chip rather than the library's default of nothing. A half-stepped
 * example is *supposed* to be missing components, and this is the one screen where seeing which
 * ones is the point -- a gap in the layout would say the same thing far less precisely.
 *
 * **The preview scrolls, so the surface is measured with an unbounded height, and vertical layout
 * that needs a bounded one does not take effect here.** A `Column` whose `justify` spreads its
 * children reaches `fillMaxHeight()` against an infinite constraint and wraps instead --
 * `00_complex-layout.json`'s `spaceBetween` and `00_interactive-button.json`'s `center` both look
 * like `start` in this pane -- and a child granted `weight` down a column measures at zero.
 * Horizontal layout, which is what most of the corpus exercises, is unaffected.
 *
 * This is the deliberate half of a trade. Bounding the preview would make vertical arrangement
 * behave, at the cost of clipping any surface taller than the pane, and only `List` brings
 * scrolling of its own. A host embedding `A2uiSurface` in its own `Modifier.verticalScroll` is
 * also the placement the library documents as usual -- see `ListComponent.kt` -- so what this pane
 * shows is what that host would see. Read a vertical-arrangement question somewhere bounded.
 */
@Composable
private fun SurfacePreview(
    state: GalleryState,
    registry: ComponentRegistry,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .testTag(GalleryTags.PREVIEW)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        val surfaceId = state.surfaceId
        if (surfaceId == null) {
            Text(
                text = if (state.cursor == 0) {
                    "Nothing applied yet — press Advance."
                } else {
                    "No renderable surface yet: the example has not sent its root component."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            A2uiSurface(
                renderer = state.renderer,
                surfaceId = surfaceId,
                registry = registry,
                placeholder = GalleryPlaceholder,
                onMessage = state::record,
            )
        }
    }
}

private val GalleryPlaceholder = A2uiPlaceholder { reason, modifier ->
    Text(
        text = reason.describe(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A one-line reason, phrased for someone reading the preview rather than a stack trace. */
private fun A2uiPlaceholderReason.describe(): String = when (this) {
    is A2uiPlaceholderReason.MissingComponent -> "not yet defined: $componentId"
    is A2uiPlaceholderReason.UnknownType -> "no renderer for $component (#$componentId)"
    is A2uiPlaceholderReason.Cycle -> "cycle at $componentId"
    is A2uiPlaceholderReason.TooDeep -> "too deep at $componentId"
    is A2uiPlaceholderReason.BudgetExceeded -> "over the $limit-instance budget at $componentId"
    is A2uiPlaceholderReason.TooManyChildren -> "$dropped children dropped from $componentId"
}

/**
 * The example's messages, applied ones marked.
 *
 * Shows the raw JSON rather than the decoded model: this pane exists to let a developer compare
 * what the agent sent against what the preview did with it, and showing this library's own reading
 * of the payload on both sides would make a decoding bug invisible.
 */
@Composable
private fun MessageStream(state: GalleryState, modifier: Modifier = Modifier) {
    Column(modifier) {
        PaneTitle("Messages")
        // Keyed on the example, so choosing another sample opens its stream at the top. A
        // `LazyColumn`'s own remembered state survives the content changing underneath it, which
        // left a two-message example scrolled to where a six-message one had been.
        val scroll = remember(state.example.file) { LazyListState() }
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag(GalleryTags.MESSAGES)) {
            itemsIndexed(state.example.raw) { index, _ ->
                val applied = index < state.cursor
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (index == state.cursor) MaterialTheme.colorScheme.tertiaryContainer
                            else Color.Transparent
                        )
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "${if (applied) "✓" else "·"} ${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = state.messageJson(index),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

/** Right column: the live data model, and the actions the surface sent. */
@Composable
private fun InspectPane(state: GalleryState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            PaneTitle("Data model")
            Text(
                text = state.dataModelJson,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .testTag(GalleryTags.DATA_MODEL)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).fillMaxWidth()) {
            PaneTitle("Actions (${state.actionLog.size})")
            LazyColumn(Modifier.fillMaxSize().testTag(GalleryTags.ACTION_LOG)) {
                items(state.actionLog) { entry ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(
                            text = entry.sourceComponentId
                                ?.let { "${entry.label}  ← #$it" }
                                ?: entry.label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = entry.json,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PaneTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
    HorizontalDivider()
    Spacer(Modifier.height(4.dp))
}
