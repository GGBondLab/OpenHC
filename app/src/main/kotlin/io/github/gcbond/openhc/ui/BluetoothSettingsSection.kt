package io.github.gcbond.openhc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.BluetoothAddressPolicy
import io.github.gcbond.openhc.connection.BluetoothSource
import io.github.gcbond.openhc.settings.AppSettings

/**
 * 「连接方式」组里的车机蓝牙地址：一行「车机蓝牙地址 [当前地址 ›]」，说明里写来源；点它在下面展开手动填写（保存、清除）。
 * 没取到地址时一进来就是展开的。
 *
 * 兼容无触屏电视 / 遥控器方向键：拦截输入框吞掉的上下键与输入法「完成」，把焦点直接交给「保存 / 清除」按钮。
 */
@Composable
internal fun BluetoothAddressSection(settings: AppSettings, systemBluetoothAddress: String?) {
    var savedManual by remember { mutableStateOf(settings.manualBluetoothAddress) }
    var manualText by remember { mutableStateOf(savedManual ?: "") }
    val current = BluetoothAddressPolicy.choose(systemBluetoothAddress, savedManual)
    var editing by rememberSaveable { mutableStateOf(current.source == BluetoothSource.UNAVAILABLE) }

    val address = current.address
    ValueRow(
        title = stringResource(R.string.settings_bt),
        summary = stringResource(R.string.settings_bt_summary),
        value = address ?: stringResource(R.string.settings_bt_value_missing),
        description = when (current.source) {
            BluetoothSource.SYSTEM_PROPERTY -> stringResource(R.string.settings_bt_current_property, address ?: "")
            BluetoothSource.MANUAL -> stringResource(R.string.settings_bt_current_manual, address ?: "")
            BluetoothSource.UNAVAILABLE -> stringResource(R.string.settings_bt_current_unavailable)
        } + "\n" + stringResource(R.string.settings_bt_hint),
        valueColor = if (address == null) Palette.Warn else Palette.Text,
        monospace = address != null,
    ) { editing = !editing }
    if (!editing) return

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val clearFocus = remember { FocusRequester() }
    var feedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    GroupRow(spacing = 8.dp) {
        val normalized = BluetoothAddressPolicy.normalize(manualText)
        val invalid = manualText.isNotBlank() && normalized == null
        val canSave = normalized != null && normalized != savedManual
        val canClear = savedManual != null || manualText.isNotEmpty()
        val savedMessage = normalized?.let { stringResource(R.string.settings_bt_saved, it) }
        val clearedMessage = stringResource(R.string.settings_bt_cleared)

        val moveOutDown = {
            keyboardController?.hide()
            val moved = when {
                canSave -> runCatching { saveFocus.requestFocus(); true }.getOrDefault(false)
                canClear -> runCatching { clearFocus.requestFocus(); true }.getOrDefault(false)
                else -> false
            }
            if (!moved) focusManager.moveFocus(FocusDirection.Down)
        }

        OutlinedTextField(
            value = manualText,
            onValueChange = {
                manualText = it
                feedback = null
            },
            label = { Text(stringResource(R.string.settings_bt_manual_label)) },
            isError = invalid,
            supportingText = if (invalid) {
                { Text(stringResource(R.string.settings_bt_invalid)) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { moveOutDown() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus)
                .focusProperties {
                    down = when {
                        canSave -> saveFocus
                        canClear -> clearFocus
                        else -> FocusRequester.Default
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown, Key.Tab -> {
                            moveOutDown()
                            true
                        }
                        Key.DirectionUp -> {
                            keyboardController?.hide()
                            focusManager.moveFocus(FocusDirection.Up)
                            true
                        }
                        else -> false
                    }
                },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(
                stringResource(R.string.action_save),
                onClick = {
                    settings.manualBluetoothAddress = normalized
                    savedManual = normalized
                    manualText = normalized ?: ""
                    feedback = savedMessage?.let { it to true }
                    runCatching { clearFocus.requestFocus() }
                },
                modifier = Modifier
                    .focusRequester(saveFocus)
                    .focusProperties {
                        up = fieldFocus
                        if (canClear) right = clearFocus
                    },
                kind = ActionKind.PRIMARY,
                enabled = canSave,
            )
            ActionButton(
                stringResource(R.string.action_clear),
                onClick = {
                    settings.manualBluetoothAddress = null
                    savedManual = null
                    manualText = ""
                    feedback = clearedMessage to false
                    runCatching { fieldFocus.requestFocus() }
                },
                modifier = Modifier
                    .focusRequester(clearFocus)
                    .focusProperties {
                        up = fieldFocus
                        if (canSave) left = saveFocus
                    },
                enabled = canClear,
            )
        }
        feedback?.let { (text, positive) ->
            RowDescription(text, color = if (positive) Palette.Positive else Palette.Muted)
        }
    }
}
