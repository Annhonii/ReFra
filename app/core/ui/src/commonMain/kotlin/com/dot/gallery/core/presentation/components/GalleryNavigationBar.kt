/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.presentation.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dot.gallery.core.presentation.util.LocalHazeState
import com.dot.gallery.core.presentation.util.NavigationItem
import com.dot.gallery.core.presentation.util.hazeEffectScaled
import com.dot.gallery.core.presentation.util.sharedSearchBounds
import com.dot.gallery.core.ui.settings.rememberPreference
import com.dot.gallery.core.ui.util.supportsBlur
import com.dot.gallery.domain.settings.SettingsKeys
import com.dot.gallery.ui.theme.ComponentSize
import com.dot.gallery.ui.theme.GalleryChrome
import com.dot.gallery.ui.theme.Spacing
import dev.chrisbanes.haze.blur.LocalHazeBlurStyle
import com.dot.gallery.core.ui.generated.resources.Res
import com.dot.gallery.core.ui.generated.resources.search_media
import org.jetbrains.compose.resources.stringResource

const val GalleryNavigationDockTag = "Gallery.NavigationDock"
const val GalleryNavigationSearchTag = "Gallery.NavigationSearch"
private const val GalleryNavigationItemTagPrefix = "Gallery.NavigationItem."

fun galleryNavigationItemTag(route: String): String = GalleryNavigationItemTagPrefix + route

/** Spring shared by the dock and rail indicators so both feel identical while switching tabs. */
internal val GalleryIndicatorSpring = spring<Float>(
    dampingRatio = 0.72f,
    stiffness = Spring.StiffnessMediumLow,
)

/** Blurred floating surface shared by the dock, its search action and the wide-window rail. */
@Composable
fun galleryFloatingSurface(shape: Shape = CircleShape): Modifier {
    val allowBlur by rememberPreference(SettingsKeys.allowBlur.withDefault(false))
    // A translucent base under the blur (the reference uses an 80% surface plus blur) keeps the
    // controls visible even where the blur has no content behind it to sample.
    val backgroundModifier = if (allowBlur) {
        Modifier.background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
    } else {
        Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
    }
    return Modifier
        .clip(shape)
        .then(backgroundModifier)
        .hazeEffectScaled(
            state = LocalHazeState.current,
            style = LocalHazeBlurStyle.current,
        )
}

/**
 * Floating destination group with a separate, thumb-reachable search action.
 * Its bounded width keeps the dock compact on both phones and large previews.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun GalleryNavigationBarContent(
    modifier: Modifier = Modifier,
    selectedRoute: String?,
    navigationItems: List<NavigationItem>,
    onClick: (route: String) -> Unit,
    onSearchClick: (() -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val surfaceModifier = galleryFloatingSurface()
    val itemPadding = 8.dp
    Box(
        modifier = modifier
            .padding(horizontal = Spacing.ScreenHorizontal, vertical = Spacing.Medium),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = GalleryChrome.DockMaxWidth)
                .fillMaxWidth()
                .testTag(GalleryNavigationDockTag),
            horizontalArrangement = Arrangement.spacedBy(GalleryChrome.DockGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(ComponentSize.NavigationBarHeight)
                    .then(surfaceModifier)
                    .selectableGroup()
                    .padding(horizontal = itemPadding),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navigationItems.forEach { item ->
                    GalleryNavBarItem(
                        navItem = item,
                        isSelected = item.route == selectedRoute,
                        onClick = onClick,
                    )
                }
            }
            if (onSearchClick != null) {
                val searchBoundsModifier =
                    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        Modifier.sharedSearchBounds(sharedTransitionScope, animatedVisibilityScope)
                    } else Modifier
                IconButton(
                    onClick = onSearchClick,
                    modifier = Modifier
                        .then(searchBoundsModifier)
                        .size(ComponentSize.NavigationBarHeight)
                        .then(surfaceModifier)
                        .testTag(GalleryNavigationSearchTag),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(Res.string.search_media),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
fun RowScope.GalleryNavBarItem(
    navItem: NavigationItem,
    isSelected: Boolean,
    onClick: (route: String) -> Unit,
) {
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "navItemContent"
    )
    val capsuleColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "navItemCapsule"
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .height(ComponentSize.NavigationBarHeight - 16.dp)
            .clip(CircleShape)
            .background(capsuleColor)
            .testTag(galleryNavigationItemTag(navItem.route))
            .selectable(
                selected = isSelected,
                role = Role.Tab,
                onClick = { onClick(navItem.route) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isSelected) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = navItem.icon,
                    contentDescription = null,
                    tint = contentColor,
                )
            }
            Text(
                text = navItem.name,
                color = contentColor,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
