/*
 * SPDX-FileCopyrightText: 2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.app.presentation.main

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.sp
import dev.brewkits.kmpworkmanager.background.domain.TaskState
import com.dot.gallery.cloud.ui.backup.BackupPillViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import com.dot.gallery.core.presentation.components.GalleryHeaderRevealState
import com.dot.gallery.core.presentation.components.LocalGalleryHeaderReveal
import com.dot.gallery.core.presentation.components.LocalGalleryPaneChrome
import com.dot.gallery.core.presentation.components.rememberGalleryHeaderRevealState
import com.dot.gallery.generated.resources.gallery_home_backup_collapse
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.gallery.cloud.sync.CloudUploadWorker
import com.dot.gallery.cloud.sync.isActiveBackupWork
import com.dot.gallery.cloud.ui.backup.UploadDetailsUiState
import com.dot.gallery.cloud.ui.backup.UploadDetailsViewModel
import com.dot.gallery.cloud.ui.backup.visibleBackupProgressItems
import com.dot.gallery.app.presentation.timeline.components.TimelineNavActions
import com.dot.gallery.app.presentation.timeline.components.timelineBackupProgressFraction
import com.dot.gallery.core.LocalEventHandler
import com.dot.gallery.core.navigate
import com.dot.gallery.core.presentation.components.GalleryHomeTopBar
import com.dot.gallery.core.presentation.components.OptionItem
import com.dot.gallery.core.presentation.util.Screen
import com.dot.gallery.core.ui.settings.rememberPreference
import com.dot.gallery.core.work.taggedTasksFlow
import com.dot.gallery.core.work.workScheduler
import com.dot.gallery.domain.settings.SettingsKeys
import com.dot.gallery.generated.resources.Res
import com.dot.gallery.generated.resources.cloud_backup_uploading_short
import com.dot.gallery.generated.resources.cloud_backup_pill_backing_up
import com.dot.gallery.generated.resources.cloud_backup_pill_complete
import com.dot.gallery.generated.resources.cloud_backup_pill_waiting_wifi
import com.dot.gallery.generated.resources.cloud_backup_verifying_count
import com.dot.gallery.generated.resources.cloud_backup_verifying_existing
import com.dot.gallery.generated.resources.cloud_upload_uploading_count
import com.dot.gallery.generated.resources.cloud_upload_failed_count
import com.dot.gallery.generated.resources.gallery_home_backup_collapsed
import com.dot.gallery.generated.resources.gallery_home_backup_expanded
import com.dot.gallery.generated.resources.settings_title
import com.dot.gallery.injection.injectViewModel
import com.dot.gallery.ui.theme.GalleryChrome
import com.dot.gallery.ui.theme.Spacing
import com.github.panpf.sketch.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource

const val TimelineBackupChipTag = "Timeline.BackupChip"
const val TimelineBackupProgressTag = "Timeline.BackupProgress"
const val TimelineBackupTrackTag = "Timeline.BackupTrack"
const val TimelineBackupCollapseTag = "Timeline.BackupCollapse"

@Composable
fun MainScreenTopBar(
    isScrolling: MutableState<Boolean>,
    filter: (@Composable () -> Unit)? = null,
    statusBarInsets: WindowInsets = WindowInsets.statusBars,
    menuItems: List<OptionItem> = emptyList(),
) {
    val eventHandler = LocalEventHandler.current
    val autoHide by rememberPreference(SettingsKeys.autoHideSearchBar)
    val runState by remember {
        workScheduler().taggedTasksFlow(CloudUploadWorker.TAG_BACKUP)
            .map { tasks ->
                when {
                    tasks.any { it.state is TaskState.Running } -> BackupRunState.Running
                    tasks.any(::isActiveBackupWork) -> BackupRunState.Queued
                    else -> BackupRunState.Idle
                }
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = BackupRunState.Idle)
    val activeBackup = runState != BackupRunState.Idle
    val pillConfig by injectViewModel<BackupPillViewModel> { backupPillViewModel }
        .config.collectAsStateWithLifecycle()
    val onUnmetered by rememberOnUnmeteredNetwork()
    val pillState = when {
        !pillConfig.hasActiveAccount -> null
        runState == BackupRunState.Running -> BackupPillState.BackingUp
        runState == BackupRunState.Queued ->
            if (pillConfig.wifiRestricted && !onUnmetered) BackupPillState.WaitingForWifi else BackupPillState.BackingUp
        else -> BackupPillState.Complete
    }
    // Queue scanning/hashing is only needed while a real backup is active, never on idle startup.
    val backup = if (activeBackup) {
        injectViewModel<UploadDetailsViewModel> { uploadDetailsViewModel }
            .uiState.collectAsStateWithLifecycle().value
    } else null
    // Screens with a pullable grid share their state so a pull past the top opens the status.
    val revealState = LocalGalleryHeaderReveal.current ?: rememberGalleryHeaderRevealState()
    val hasBackup = backup != null && runState == BackupRunState.Running
    LaunchedEffect(revealState, hasBackup) {
        revealState.enabled = hasBackup
        if (!hasBackup) revealState.reset()
    }
    val revealed by remember(revealState) { derivedStateOf { revealState.fraction > 0f } }

    // Hiding only moves the header in its draw layer: the slot keeps its height, so the grid's
    // top padding never changes mid-scroll and cannot jolt the list into pull-to-refresh.
    val hidden = autoHide && isScrolling.value && !revealed
    val hideProgress by animateFloatAsState(
        targetValue = if (hidden) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "headerHide",
    )
    Box(
        modifier = Modifier.graphicsLayer {
            translationY = -size.height * hideProgress
            alpha = 1f - hideProgress
        },
    ) {
        MainScreenTopBarContent(
            backup = backup.takeIf { hasBackup },
            pillState = pillState,
            revealState = revealState,
            onBrandClick = { eventHandler.navigate(Screen.SettingsScreen()) },
            onOpenBackupDetails = { eventHandler.navigate(Screen.UploadDetailsScreen()) },
            menu = { TimelineNavActions(extraItems = menuItems) },
            filter = filter,
            statusBarInsets = statusBarInsets,
        )
    }
}

@Composable
internal fun MainScreenTopBarContent(
    backup: UploadDetailsUiState?,
    pillState: BackupPillState? = if (backup != null) BackupPillState.BackingUp else null,
    revealState: GalleryHeaderRevealState,
    onBrandClick: () -> Unit,
    onOpenBackupDetails: () -> Unit,
    menu: @Composable () -> Unit,
    filter: (@Composable () -> Unit)?,
    statusBarInsets: WindowInsets = WindowInsets.statusBars,
) {
    GalleryHomeTopBar(
        onBrandClick = onBrandClick,
        brandDescription = stringResource(Res.string.settings_title),
        statusBarInsets = if (LocalGalleryPaneChrome.current) WindowInsets(0, 0, 0, 0)
            else statusBarInsets,
        menu = menu,
        filter = filter,
        revealState = revealState,
        showBrand = false,
        status = if (pillState != null) {
            {
                TimelineBackupChip(
                    state = pillState,
                    expanded = revealState.expanded,
                    onClick = if (backup != null) revealState::toggle else onOpenBackupDetails,
                )
            }
        } else null,
        expandedStatus = if (backup != null) {
            {
                TimelineBackupProgress(
                    state = backup,
                    onClick = onOpenBackupDetails,
                    onCollapse = revealState::collapse,
                )
            }
        } else null,
    )
}

internal enum class BackupRunState { Idle, Queued, Running }

/** What the Google-Photos-style status pill at the top-left of the home screen says. */
internal enum class BackupPillState { BackingUp, WaitingForWifi, Complete }

/** True while the device is on an unmetered (Wi-Fi) network. */
@Composable
private fun rememberOnUnmeteredNetwork(): androidx.compose.runtime.State<Boolean> {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.produceState(initialValue = true, context) {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        fun current(): Boolean = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        value = current()
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { value = current() }
            override fun onLost(network: android.net.Network) { value = current() }
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                capabilities: android.net.NetworkCapabilities,
            ) { value = current() }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        awaitDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
}

@Composable
private fun TimelineBackupChip(state: BackupPillState, expanded: Boolean, onClick: () -> Unit) {
    val expansionDescription = stringResource(
        if (expanded) Res.string.gallery_home_backup_expanded else Res.string.gallery_home_backup_collapsed,
    )
    Surface(
        onClick = onClick,
        // Hidden behind the revealed card, so it must not stay tappable while invisible.
        enabled = !expanded,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .height(GalleryChrome.ToolbarButtonSize)
            .semantics { stateDescription = expansionDescription }
            .testTag(TimelineBackupChipTag),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                BackupPillState.BackingUp -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                BackupPillState.WaitingForWifi -> Icon(
                    imageVector = Icons.Outlined.WifiOff,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                BackupPillState.Complete -> Icon(
                    imageVector = Icons.Outlined.CloudDone,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(
                    when (state) {
                        BackupPillState.BackingUp -> Res.string.cloud_backup_pill_backing_up
                        BackupPillState.WaitingForWifi -> Res.string.cloud_backup_pill_waiting_wifi
                        BackupPillState.Complete -> Res.string.cloud_backup_pill_complete
                    },
                ),
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun TimelineBackupProgress(
    state: UploadDetailsUiState,
    onClick: () -> Unit,
    onCollapse: (() -> Unit)? = null,
) {
    val verifying = state.phase == CloudUploadWorker.PHASE_VERIFYING
    val processed = visibleBackupProgressItems(
        state.phase, state.checkedItems, state.completedItems, state.failedItems,
    )
    val progress = timelineBackupProgressFraction(processed, state.totalItems)
    // Worker updates arrive in bursts; easing between them keeps the track moving smoothly.
    val animatedProgress by animateFloatAsState(progress ?: 0f, label = "backupProgress")
    val collapseLabel = stringResource(Res.string.gallery_home_backup_collapse)
    val providers = remember(state.groups) { state.groups.map { it.providerType.displayName }.distinct() }
    val previews = remember(state.groups) {
        state.groups.flatMap { it.items }.distinctBy { it.mediaId }.take(16)
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(GalleryChrome.ToolbarCornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                if (onCollapse != null) {
                    customActions = listOf(CustomAccessibilityAction(collapseLabel) { onCollapse(); true })
                }
            }
            .testTag(TimelineBackupProgressTag),
    ) {
        Column(
            modifier = Modifier.padding(start = Spacing.Medium, end = Spacing.Medium, top = Spacing.Medium,
                bottom = if (onCollapse != null) 0.dp else Spacing.Medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        state.totalItems <= 0 -> stringResource(
                            if (verifying) Res.string.cloud_backup_verifying_existing
                            else Res.string.cloud_backup_uploading_short,
                        )
                        verifying -> stringResource(
                            Res.string.cloud_backup_verifying_count,
                            processed.coerceIn(0, state.totalItems), state.totalItems,
                        )
                        else -> stringResource(
                            Res.string.cloud_upload_uploading_count,
                            processed.coerceIn(0, state.totalItems), state.totalItems,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                providers.take(1).forEach { provider ->
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            provider,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                if (providers.size > 1) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(
                            "+${providers.size - 1}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            val trackModifier = Modifier.fillMaxWidth().height(12.dp).clip(CircleShape)
                .testTag(TimelineBackupTrackTag)
            if (progress == null) {
                LinearProgressIndicator(modifier = trackModifier)
            } else {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = trackModifier,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            if (state.failedItems > 0) {
                Text(
                    text = stringResource(Res.string.cloud_upload_failed_count, state.failedItems),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (previews.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    items(previews, key = { it.mediaId }) { item ->
                        AsyncImage(
                            uri = item.uri.toString(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).animateItem(),
                        )
                    }
                }
            }
            if (onCollapse != null) {
                // Doubles as the swipe affordance; the whole header also follows vertical drags.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 48.dp, height = 20.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = collapseLabel, onClick = onCollapse)
                        .testTag(TimelineBackupCollapseTag),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 32.dp, height = 4.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape),
                    )
                }
            }
        }
    }
}
