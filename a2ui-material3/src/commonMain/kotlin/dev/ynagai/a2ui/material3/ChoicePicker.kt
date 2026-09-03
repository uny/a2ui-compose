package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiComponentScope
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.firstMessage
import dev.ynagai.a2ui.compose.rememberBoolean
import dev.ynagai.a2ui.compose.rememberCheckFailures
import dev.ynagai.a2ui.compose.rememberString
import dev.ynagai.a2ui.core.surface.JsonPointer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `ChoicePicker` -- the catalog's one component whose bound value is a list.
 *
 * Two independent properties decide what it looks like and what it does, and keeping them
 * independent is most of this file. `displayStyle` chooses the control -- a stack of rows, or a
 * wrapping band of chips. `variant` chooses the arithmetic -- `mutuallyExclusive` replaces the
 * selection, `multipleSelection` toggles within it. The implementation guide only spells out one
 * of the four combinations (`checkbox` + either variant, drawn as checkboxes or radios), so the
 * chips form takes its selection rule from `variant` alone rather than from a second guess.
 *
 * **Always a list, even when only one thing can be selected.** The catalog types `value` as a
 * `DynamicStringList` under both variants, so `mutuallyExclusive` writes its answer as an array
 * rather than as a bare string -- a renderer that "simplified" it would hand the agent back a data
 * model of a different shape than the one it sent.
 *
 * **And the write is a splice, not a replacement,** which is the same sentence read the other way.
 * The catalog types the bound array as a list of strings, but nothing stops an agent from binding
 * one that holds an object, a number, or a string this picker has no option for. None of those is
 * a selection this picker can represent, so it neither reads one as selected nor writes over it: a
 * tap removes or appends only entries matching a declared `options[].value`, and every other entry
 * keeps its place and its JSON type. Rebuilding the array out of the strings it could read would
 * retype `[1, 2]` to `["1", "2"]` and drop `{"id": 7}` outright -- exactly the shape change the
 * paragraph above refuses. Under `mutuallyExclusive` that means the array it writes is one element
 * long only when the array it read had nothing else in it.
 *
 * `filterable` filters what is drawn and never what is selected. A selection the filter is
 * currently hiding stays in the data model, because the filter is this renderer's own UI state and
 * the agent never hears about it; a filter that silently deselected would lose the user's answer
 * for typing.
 *
 * A picker whose `value` is not a data binding draws its options and refuses them, the same way
 * [CheckBoxRenderer] does and for the same reason.
 */
public val ChoicePickerRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val label = scope.rememberString("label")
    val variant = scope.rememberString("variant")
    val displayStyle = scope.rememberString("displayStyle")
    val filterable = scope.rememberBoolean("filterable")
    val bound = scope.rememberSelection()
    val target = remember(scope) { scope.binding("value") }
    val options = scope.rememberOptions()
    // The values this picker owns: what it draws as selected, and the only entries it rewrites.
    // Taken from `options` rather than from `shown`, because the filter is a way of looking at the
    // list and never a claim about what the data model holds.
    val ownValues = remember(options) { options.mapTo(mutableSetOf()) { it.value } }
    val selected = remember(bound, ownValues) {
        bound.mapNotNull { entry -> entry.selectionValue()?.takeIf { it in ownValues } }
    }
    val failure = scope.rememberCheckFailures().firstMessage()

    // This renderer's own state, not the agent's: the filter is a way of looking at the options
    // and there is nowhere in the payload it belongs. Keyed on the scope so that a component
    // recycled onto a different picker does not inherit the last one's query.
    var query by remember(scope) { mutableStateOf("") }
    val shown = remember(options, query) {
        if (query.isBlank()) options else options.filter { it.label.contains(query, ignoreCase = true) }
    }

    val exclusive = variant != "multipleSelection"
    val onSelect: (String) -> Unit = { value ->
        val pointer: JsonPointer? = target
        if (pointer != null) {
            val next: List<JsonElement> = when {
                // "Toggle selections in the data model upon user interaction." Toggling under
                // `mutuallyExclusive` means the tapped option replaces whatever was there, and
                // re-tapping the selected one clears it -- which is how a radio group with no
                // required answer behaves, and the only way the user can get back to none.
                // "Whatever was there" is the selection and not the whole array: an entry this
                // picker has no option for is not a selection, and replacing a selection must not
                // silently answer for something else the agent is keeping in the same list.
                exclusive -> {
                    val kept = bound.filterNot { it.selectionValue() in ownValues }
                    // `all`, not `== listOf(value)`: an agent may have bound `["rock", "rock"]`,
                    // and a radio drawn as selected has to clear when it is tapped however many
                    // times its answer appears in the array.
                    val onlyThis = selected.isNotEmpty() && selected.all { it == value }
                    if (onlyThis) kept else kept + JsonPrimitive(value)
                }
                // Every match, not the first: `List.minus` drops one occurrence, which would leave
                // a duplicated selection looking deselected while still sitting in the model.
                value in selected -> bound.filterNot { it.selectionValue() == value }
                else -> bound + JsonPrimitive(value)
            }
            scope.write(pointer, JsonArray(next))
        }
    }

    Column(modifier = modifier.leafMargin()) {
        if (label != null) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
        if (filterable == true) {
            // Hoisted out of the `leadingIcon` lambda, which is not only tidier: a composable
            // lambda that captures nothing is compiled to a `ComposableSingletons` object whose
            // name carries a hash, and that name is public ABI -- so a non-capturing lambda here
            // would put a symbol in the published surface that churns on unrelated edits.
            val searchIcon = remember { ICON_PATHS[SEARCH_GLYPH]?.let { iconVector(it) } }
            // Read out here for the same reason, and it is the same reason twice: a composable
            // lambda that captures nothing is what gets lifted into `ComposableSingletons`. Left
            // inline, `{ Text(LocalA2uiStrings.current.filter) }` captured nothing and put
            // `getLambda$-840242893$...` into the published ABI dump -- a name that is a hash of
            // this lambda's position in the file, so any later edit above it renames a public
            // symbol and `checkLegacyAbi` reports a break on a change that touched no API.
            val filterLabel = LocalA2uiStrings.current.filter
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.padding(vertical = OPTION_GAP),
                singleLine = true,
                // A `label` rather than a `placeholder`, because a placeholder disappears once
                // there is a query and would leave the field with no accessible name for exactly
                // as long as it had a value in it. The word comes from [LocalA2uiStrings]: the
                // catalog gives a filter box no text of its own, and this module does not pick the
                // surface's language. The magnifier beside it is the catalog's own `search` glyph,
                // decorative now that the label names the field.
                label = { Text(filterLabel) },
                leadingIcon = {
                    if (searchIcon != null) Icon(imageVector = searchIcon, contentDescription = null)
                },
            )
        }
        if (displayStyle == "chips") {
            Chips(shown, selected, target != null, onSelect)
        } else {
            Rows(shown, selected, exclusive, target != null, onSelect)
        }
        CheckMessage(failure)
    }
}

/** One option: the text the user reads, and the value the data model carries. */
private data class Choice(val label: String, val value: String)

/**
 * A stack of rows, each a radio or a checkbox beside its label.
 *
 * `selectableGroup` on the column is what makes a set of radios one accessibility control rather
 * than a run of independent ones -- and it is only correct for the exclusive case, so the
 * multiple-selection form does without it and uses `toggleable` rows like [CheckBoxRenderer]'s.
 */
@Composable
private fun Rows(
    options: List<Choice>,
    selected: List<String>,
    exclusive: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(modifier = if (exclusive) Modifier.selectableGroup() else Modifier) {
        options.forEach { option ->
            val isSelected = option.value in selected
            val row = if (exclusive) {
                Modifier.selectable(
                    selected = isSelected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { onSelect(option.value) },
                )
            } else {
                Modifier.toggleable(
                    value = isSelected,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = { onSelect(option.value) },
                )
            }
            Row(modifier = row, verticalAlignment = Alignment.CenterVertically) {
                // Null callbacks throughout, so the row keeps the click -- see [CheckBoxRenderer].
                if (exclusive) {
                    RadioButton(selected = isSelected, onClick = null, enabled = enabled)
                } else {
                    Checkbox(checked = isSelected, onCheckedChange = null, enabled = enabled)
                }
                Text(text = option.label, modifier = Modifier.padding(start = OPTION_GAP))
            }
        }
    }
}

/**
 * The chips form: "a horizontal, wrapping row of selectable chips/pills."
 *
 * `FlowRow` rather than a scrolling `Row`, because the guide asks for wrapping -- and because a
 * horizontally scrolling band inside a surface that may itself scroll is the nesting
 * [ListRenderer] is careful about.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(
    options: List<Choice>,
    selected: List<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(OPTION_GAP)) {
        options.forEach { option ->
            // `FilterChip` rather than `AssistChip`: it is the Material 3 chip that carries a
            // selected state, which is the "distinct background/border" the guide asks for, and it
            // gets that state from the theme rather than from a colour named here.
            FilterChip(
                selected = option.value in selected,
                onClick = { onSelect(option.value) },
                enabled = enabled,
                label = { Text(option.label) },
            )
        }
    }
}

/**
 * The bound `value` array as it stands in the data model, entries and all.
 *
 * Read as JSON rather than through
 * [rememberStringList][dev.ynagai.a2ui.compose.rememberStringList], because this renderer writes
 * the array back and a list of strings is not enough to write back *the same array*.
 * [ChoicePickerRenderer] splices into what it finds here, so an entry it never touches keeps the
 * very `JsonElement` the agent put there.
 *
 * A `value` that is absent, unreadable, or bound to something that is not an array reads as empty
 * -- the same degradation the typed accessors give, and a picker with nothing selected is what an
 * empty array draws anyway.
 *
 * `derivedStateOf` for the reason every accessor in this file uses one: `value` is a data binding,
 * so a list keyed on the unresolved property would never see a write land.
 */
@Composable
private fun A2uiComponentScope.rememberSelection(): JsonArray {
    val selection by remember(this) {
        derivedStateOf { value("value") as? JsonArray ?: JsonArray(emptyList()) }
    }
    return selection
}

/**
 * One array entry read as the text an option's `value` could match.
 *
 * The catalog types `options[].value` as a string, so only a primitive can name a selection and an
 * object, an array or a `null` reads as null here. A number reads as its text, which is the
 * leniency the specification asks for when a payload and its catalog disagree about a scalar --
 * and it is a read only: the entry is still written back as the number it was.
 */
private fun JsonElement.selectionValue(): String? = (this as? JsonPrimitive)?.contentOrNull

/**
 * The `options` array, with each `label` resolved, recomputed when a resolved label changes.
 *
 * `derivedStateOf` rather than a plain `remember` on the raw property, for the reason every
 * accessor in the adapter layer uses one: a `label` may be a data binding, and a list keyed on the
 * *unresolved* property would never recompute -- the property is the same JSON before and after
 * the write, so an option named by the data model would show whatever it said when the picker was
 * first drawn and never change again.
 */
@Composable
private fun A2uiComponentScope.rememberOptions(): List<Choice> {
    val options by remember(this) { derivedStateOf { options() } }
    return options
}

/**
 * The `options` array as this renderer reads it.
 *
 * Read off the raw property rather than through a typed accessor: `options` is an array of objects
 * and the catalog types only the `label` inside each as dynamic, so this walks the structure and
 * resolves the one part that can be bound. An entry missing either half is dropped -- the schema
 * requires both, so it is a payload already refused, and drawing a nameless option would give the
 * user something to select that says nothing.
 *
 * **Bounded, because nothing upstream bounds it.** The surface's instance budget divides among a
 * component's *children*, and these are a property -- so a single `ChoicePicker` is one instance
 * to the budget however many options it carries, while [Rows] and [Chips] compose every one of
 * them eagerly in a non-lazy container. One `updateComponents` message carrying fifty thousand
 * options is one component that walks straight past the ~5,000-widget ceiling the rest of this
 * library is measured against. [MAX_OPTIONS] is the bound the budget cannot supply here.
 */
private fun A2uiComponentScope.options(): List<Choice> =
    (property("options") as? JsonArray).orEmpty().take(MAX_OPTIONS).mapNotNull { entry ->
        val option = entry as? JsonObject ?: return@mapNotNull null
        val value = (option["value"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: return@mapNotNull null
        val label = option["label"]?.let { dynamicString(it) } ?: return@mapNotNull null
        Choice(label = label, value = value)
    }

/**
 * The most options a picker will draw -- see [options].
 *
 * Generous by the standard of anything a person reads down: a country list is about 250, and this
 * is four times that. What it refuses is the payload that is not a list of choices.
 *
 * **The truncation is silent, which is the weaker half of this.** Everywhere else a bound is hit
 * this library says so on screen -- a shortened child list draws an
 * [A2uiPlaceholderReason.TooManyChildren][dev.ynagai.a2ui.compose.A2uiPlaceholderReason.TooManyChildren]
 * placeholder. That machinery expands *children*, and these are a property, so there is no
 * placeholder to hand back here without inventing one; and the alternative on offer -- a line of
 * this module's own English under the picker -- is the thing [A2uiStrings] exists to keep to three
 * words. Dropping the tail beats hanging the surface, but a visible degradation would beat both.
 */
private const val MAX_OPTIONS = 1000

/** Between a control and its label, and between chips. */
private val OPTION_GAP = 8.dp

/** The catalog icon the filter box wears -- see the picker's filter branch. */
private const val SEARCH_GLYPH = "search"
