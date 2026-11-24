/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.ui.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dot.gallery.cloud.sync.UploadDataBudget
import com.dot.gallery.generated.resources.Res
import com.dot.gallery.generated.resources.action_cancel
import com.dot.gallery.generated.resources.action_save
import com.dot.gallery.generated.resources.cloud_backup_daily_limit_custom
import com.dot.gallery.generated.resources.cloud_backup_daily_limit_custom_hint
import com.dot.gallery.generated.resources.cloud_backup_daily_limit_unlimited
import com.dot.gallery.generated.resources.cloud_backup_daily_limit_value
import org.jetbrains.compose.resources.stringResource

/**
 * Picks a daily upload cap in megabytes: unlimited, a quick preset (10/20/50/100 MB) or a custom
 * value typed in by the user (for example 500 MB).
 */
@Composable
fun DailyLimitDialog(
    title: String,
    currentMb: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val presets = UploadDataBudget.PRESETS_MB
    var customSelected by remember { mutableStateOf(currentMb !in presets) }
    var selectedMb by remember { mutableStateOf(currentMb) }
    var customText by remember { mutableStateOf(if (currentMb !in presets) currentMb.toString() else "") }
    val customValue = customText.toLongOrNull()
    val canSave = !customSelected || (customValue != null && customValue > 0L)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                presets.forEach { mb ->
                    LimitRow(
                        label = if (mb == 0L) stringResource(Res.string.cloud_backup_daily_limit_unlimited)
                        else stringResource(Res.string.cloud_backup_daily_limit_value, mb.toInt()),
                        selected = !customSelected && selectedMb == mb,
                        onClick = { customSelected = false; selectedMb = mb }
                    )
                }
                LimitRow(
                    label = stringResource(Res.string.cloud_backup_daily_limit_custom),
                    selected = customSelected,
                    onClick = { customSelected = true }
                )
                if (customSelected) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { text -> customText = text.filter { it.isDigit() }.take(7) },
                        placeholder = { Text(stringResource(Res.string.cloud_backup_daily_limit_custom_hint)) },
                        suffix = { Text("MB") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { onConfirm(if (customSelected) customValue ?: 0L else selectedMb) }
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        }
    )
}

@Composable
private fun LimitRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}
