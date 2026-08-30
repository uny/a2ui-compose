package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.firstMessage
import dev.ynagai.a2ui.compose.rememberBoolean
import dev.ynagai.a2ui.compose.rememberCheckFailures
import dev.ynagai.a2ui.compose.rememberString
import dev.ynagai.a2ui.core.protocol.Severity
import dev.ynagai.a2ui.core.surface.JsonPointer
import kotlinx.serialization.json.JsonPrimitive

/**
 * `DateTimeInput` -- Material's date and time pickers, behind a field that shows ISO 8601.
 *
 * The field itself never accepts typing. "Render using native date and time picker controls" is
 * what the guide asks for, and the picker is where the value comes from; the read-only field is
 * the anchor that opens it and the place the current value is legible. That also means the model
 * only ever receives a string a picker produced, so the ISO 8601 the catalog requires is
 * guaranteed by construction rather than by validating what someone typed.
 *
 * **Which pickers open is `enableDate`/`enableTime`, and both default to false.** The catalog says
 * so, which leaves a component that sets neither with nothing to pick -- so this treats "neither"
 * as "date", because a `DateTimeInput` that opened no picker at all would be a dead field, and the
 * date is the half its own name leads with. With both enabled the date is asked first and the time
 * second, and the two are combined into one `yyyy-MM-ddTHH:mm` value.
 *
 * `min` and `max` bound the date picker's selectable range. They are read as ISO 8601 like
 * everything else here, and one that will not parse is ignored rather than treated as an empty
 * range that would refuse every date.
 *
 * A field whose `value` is not a data binding does not open a picker: it has nowhere to write, and
 * an input that collected an answer and dropped it is [TextFieldRenderer]'s broken renderer again.
 */
@OptIn(ExperimentalMaterial3Api::class)
public val DateTimeInputRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val label = scope.rememberString("label")
    val value = scope.rememberString("value").orEmpty()
    val wantsDate = scope.rememberBoolean("enableDate") ?: false
    val wantsTime = scope.rememberBoolean("enableTime") ?: false
    // Neither is the catalog's default pair, and it names no picker. See the KDoc.
    val showsDate = wantsDate || !wantsTime
    val target = remember(scope) { scope.binding("value") }
    val failure = scope.rememberCheckFailures().firstMessage()
    val min = scope.rememberString("min")?.let { Iso8601.epochDay(it) }
    val max = scope.rememberString("max")?.let { Iso8601.epochDay(it) }

    // Which dialog is open, if any. The date runs before the time when both are wanted, and
    // `pickedDay` carries the first answer across to the second.
    var stage by remember(scope) { mutableStateOf(Stage.NONE) }
    var pickedDay by remember(scope) { mutableStateOf<Long?>(null) }

    Column(modifier = modifier.leafMargin()) {
        OutlinedTextField(
            value = value,
            // Read-only, so this is never called; required by the API. The field's own text comes
            // from the data model on every recomposition, so there is no state here to update.
            onValueChange = {},
            readOnly = true,
            enabled = target != null,
            label = label?.let { { Text(it) } },
            isError = failure?.severity == Severity.ERROR,
            trailingIcon = {
                IconButton(
                    onClick = { stage = if (showsDate) Stage.DATE else Stage.TIME },
                    enabled = target != null,
                ) {
                    val glyph = if (showsDate) ICON_PATHS[CALENDAR_GLYPH] else CLOCK_PATH
                    val vector = remember(glyph) { glyph?.let { iconVector(it) } }
                    if (vector != null) Icon(imageVector = vector, contentDescription = null)
                }
            },
        )
        CheckMessage(failure)
    }

    val pointer: JsonPointer? = target
    when (stage) {
        Stage.NONE -> Unit

        Stage.DATE -> {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = Iso8601.epochDay(value)?.let { it * Iso8601.DAY_MILLIS },
                selectableDates = remember(min, max) { RangeOfDays(min, max) },
            )
            PickerDialog(
                onDismiss = { stage = Stage.NONE },
                onConfirm = {
                    val day = state.selectedDateMillis?.floorDiv(Iso8601.DAY_MILLIS)
                    pickedDay = day
                    // Straight on to the time when both were asked for; otherwise this was the
                    // whole answer and it is written now.
                    if (wantsTime && day != null) {
                        stage = Stage.TIME
                    } else {
                        val written = Iso8601.combine(day, time = null)
                        if (written != null && pointer != null) {
                            scope.write(pointer, JsonPrimitive(written))
                        }
                        stage = Stage.NONE
                    }
                },
            ) {
                DatePicker(state = state)
            }
        }

        Stage.TIME -> {
            val existing = Iso8601.hourMinute(value)
            val state = rememberTimePickerState(
                initialHour = existing?.first ?: 0,
                initialMinute = existing?.second ?: 0,
                is24Hour = true,
            )
            PickerDialog(
                onDismiss = { stage = Stage.NONE },
                onConfirm = {
                    val time = Iso8601.time(state.hour, state.minute)
                    // The day picked a moment ago, or the one already in the model when only the
                    // time was asked for. Absent both, the time stands alone -- which is the
                    // `format: time` the catalog's own `min`/`max` allow.
                    val day = pickedDay ?: Iso8601.epochDay(value).takeIf { wantsDate }
                    val written = Iso8601.combine(day, time)
                    if (written != null && pointer != null) scope.write(pointer, JsonPrimitive(written))
                    pickedDay = null
                    stage = Stage.NONE
                },
            ) {
                TimePicker(state = state)
            }
        }
    }
}

/** Which picker is open. */
private enum class Stage { NONE, DATE, TIME }

/**
 * The shell both pickers open in.
 *
 * `DatePickerDialog` for the time picker too, rather than an `AlertDialog`: it is the container
 * Material 3 gives these, and using one shell means the two dialogs cannot drift apart in padding
 * or in where their buttons sit.
 *
 * The buttons are glyphs, not words, for [ChoicePickerRenderer]'s reason -- this module draws no
 * text the payload did not supply, and "OK"/"Cancel" would be two English strings on a surface
 * whose language the agent chose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { GlyphButton(CHECK_GLYPH, onConfirm) },
        dismissButton = { GlyphButton(CLOSE_GLYPH, onDismiss) },
    ) {
        content()
    }
}

/** One of the dialog's two buttons: a catalog glyph and no label. */
@Composable
private fun GlyphButton(name: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        val vector = remember(name) { ICON_PATHS[name]?.let { iconVector(it) } }
        if (vector != null) Icon(imageVector = vector, contentDescription = null, modifier = Modifier)
    }
}

/**
 * The days `min` and `max` leave selectable.
 *
 * A class rather than an object expression so that it can be `remember`ed on the two bounds:
 * `rememberDatePickerState` keys its state on this instance, and a fresh one on every
 * recomposition would reset the user's selection while the dialog was open.
 */
@OptIn(ExperimentalMaterial3Api::class)
private class RangeOfDays(private val min: Long?, private val max: Long?) :
    androidx.compose.material3.SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        val day = utcTimeMillis.floorDiv(Iso8601.DAY_MILLIS)
        return (min == null || day >= min) && (max == null || day <= max)
    }
}

private const val CALENDAR_GLYPH = "calendarToday"
private const val CHECK_GLYPH = "check"
private const val CLOSE_GLYPH = "close"

/**
 * A clock face, for the time-only field.
 *
 * Drawn here rather than added to [ICON_PATHS] because that map is the catalog's closed enum of
 * icon names, and a key no `Icon` component may ask for does not belong in it. Even-odd like the
 * rest of this module's own glyphs -- see [IconRenderer].
 */
private const val CLOCK_PATH =
    "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16zm.5-13H11v6l5.2 " +
        "3.2.8-1.3-4.5-2.7z"
