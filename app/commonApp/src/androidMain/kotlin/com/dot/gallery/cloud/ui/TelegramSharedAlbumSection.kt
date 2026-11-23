/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dot.gallery.generated.resources.Res
import com.dot.gallery.generated.resources.cloud_shared_album_dialog_title
import com.dot.gallery.generated.resources.cloud_shared_album_invalid_invite
import com.dot.gallery.generated.resources.cloud_shared_album_join
import com.dot.gallery.generated.resources.cloud_shared_album_name
import com.dot.gallery.generated.resources.cloud_shared_album_paste_hint
import com.dot.gallery.generated.resources.cloud_shared_album_share
import com.dot.gallery.generated.resources.cloud_shared_album_share_chooser
import com.dot.gallery.generated.resources.cloud_shared_album_share_warning
import com.dot.gallery.generated.resources.cloud_shared_album_switch
import com.dot.gallery.generated.resources.cloud_shared_album_switch_desc
import com.dot.gallery.generated.resources.cloud_telegram_join_confirm
import org.jetbrains.compose.resources.stringResource

private const val SHARED_PREFIX = "telegram-shared://"

/** Id part of a Telegram channel field, whatever scheme the user typed. */
private fun bareChatId(url: String): String =
    url.trim().removePrefix(SHARED_PREFIX).removePrefix("telegram://").trim('/')

/**
 * Telegram-only controls of the add-account form: turn the account into a shared family album,
 * join one from an invite, and share this account's invite with family.
 */
@Composable
fun TelegramSharedAlbumSection(
    state: AddServerUiState,
    actions: CloudAddServerActions,
) {
    val context = LocalContext.current
    val isShared = state.serverUrl.trim().startsWith(SHARED_PREFIX, ignoreCase = true)
    var showJoin by remember { mutableStateOf(false) }
    var inviteText by remember { mutableStateOf("") }
    var inviteError by remember { mutableStateOf(false) }
    val sharedName = stringResource(Res.string.cloud_shared_album_name)

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.cloud_shared_album_switch), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(Res.string.cloud_shared_album_switch_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isShared,
                onCheckedChange = { on ->
                    val id = bareChatId(state.serverUrl)
                    actions.updateServerUrl(if (on) SHARED_PREFIX + id else id)
                    if (on && state.displayName.isBlank()) actions.updateDisplayName(sharedName)
                }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { inviteText = ""; inviteError = false; showJoin = true }) {
                Text(stringResource(Res.string.cloud_shared_album_join))
            }
            if (isShared && state.apiKey.isNotBlank() && state.password.isNotBlank()) {
                TextButton(onClick = {
                    val invite = SharedAlbumInvite(state.apiKey, bareChatId(state.serverUrl), state.password).encode()
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, invite)
                    }
                    context.startActivity(
                        Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) {
                    Text(stringResource(Res.string.cloud_shared_album_share))
                }
            }
        }
        if (isShared) {
            Text(
                stringResource(Res.string.cloud_shared_album_share_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showJoin) {
        AlertDialog(
            onDismissRequest = { showJoin = false },
            title = { Text(stringResource(Res.string.cloud_shared_album_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = inviteText,
                    onValueChange = { inviteText = it; inviteError = false },
                    placeholder = { Text(stringResource(Res.string.cloud_shared_album_paste_hint)) },
                    isError = inviteError,
                    supportingText = if (inviteError) {
                        { Text(stringResource(Res.string.cloud_shared_album_invalid_invite)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val invite = SharedAlbumInvite.decode(inviteText)
                    if (invite == null) {
                        inviteError = true
                    } else {
                        actions.updateServerUrl(SHARED_PREFIX + invite.chatId)
                        actions.updateApiKey(invite.botToken)
                        actions.updatePassword(invite.albumKey)
                        if (state.displayName.isBlank()) actions.updateDisplayName(sharedName)
                        showJoin = false
                    }
                }) { Text(stringResource(Res.string.cloud_telegram_join_confirm)) }
            }
        )
    }
}
