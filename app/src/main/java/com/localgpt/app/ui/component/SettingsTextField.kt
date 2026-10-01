package com.localgpt.app.ui.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * A text field for editing a persisted setting, with explicit one-way external sync.
 *
 * Unlike `remember(key) { mutableStateOf(...) }` — which wipes in-progress user edits
 * every time the settings flow emits (e.g. when an unrelated setting changes) — this
 * component keeps the user's draft while the field is focused and only adopts external
 * value changes when the field is NOT focused (e.g. initial load, or a change made
 * from another screen).
 *
 * @param initialValue the current persisted value; treated as the external source of truth.
 * @param onSave called with the new text on every keystroke (persist it, e.g. to DataStore).
 * @param onValueChange optional observer of the field's current text, fired for keystrokes
 *   and for adopted external updates.
 */
@Composable
fun SettingsTextField(
    initialValue: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    onValueChange: (String) -> Unit = {},
) {
    var text by remember { mutableStateOf(initialValue) }
    var isFocused by remember { mutableStateOf(false) }

    // One-way sync: adopt external changes only while the user is not typing,
    // so in-progress edits are never wiped by unrelated settings updates.
    // LaunchedEffect (not direct composition write) so the onValueChange
    // notification is a proper side effect.
    LaunchedEffect(initialValue) {
        if (!isFocused && text != initialValue) {
            text = initialValue
            onValueChange(initialValue)
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
            onSave(it)
        },
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        trailingIcon = trailingIcon,
        shape = shape,
        modifier =
            modifier.onFocusChanged { state: FocusState ->
                isFocused = state.isFocused
            },
        interactionSource = remember { MutableInteractionSource() },
    )
}
