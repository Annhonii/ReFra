/*
 * SPDX-FileCopyrightText: 2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dot.gallery.data.dao.CloudServerConfigDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What the home top-bar backup pill needs to know about the user's cloud accounts. */
data class BackupPillConfig(
    /** At least one active cloud account exists, so a backup status is worth showing. */
    val hasActiveAccount: Boolean = false,
    /** Uploads are restricted to Wi-Fi (Wi-Fi only, or cellular upload of photos switched off). */
    val wifiRestricted: Boolean = false,
    /** A shared family album account exists, so the Shared tab is worth showing. */
    val hasSharedAccount: Boolean = false,
)

class BackupPillViewModel @Inject constructor(
    configDao: CloudServerConfigDao,
) : ViewModel() {
    val config: StateFlow<BackupPillConfig> = configDao.getActive()
        .map { configs ->
            val active = configs.filter { it.isActive }
            BackupPillConfig(
                hasActiveAccount = active.isNotEmpty(),
                wifiRestricted = active.any { it.wifiOnly || !it.cellularPhotos },
                hasSharedAccount = active.any { it.serverUrl.trim().startsWith("telegram-shared://", ignoreCase = true) },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BackupPillConfig())
}
