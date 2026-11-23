/*
 * SPDX-FileCopyrightText: 2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.presentation.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshotFlow

/** Offsets closer than this to the very top count as "at the top". */
private const val TopSlopPx = 24

/** Smallest offset change inside one item that counts as a deliberate scroll. */
private const val DirectionSlopPx = 6

/**
 * Google-Photos-style header behaviour: [hidden] turns true when the user scrolls forward
 * (down the list) and stays true — even after the scroll stops — until they scroll back up or
 * reach the very top.
 */
@Composable
fun TrackHeaderHiddenOnScroll(state: LazyGridState, hidden: MutableState<Boolean>) {
    TrackHeaderHidden(
        key = state,
        hidden = hidden,
        position = { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
    )
}

@Composable
fun TrackHeaderHiddenOnScroll(state: LazyListState, hidden: MutableState<Boolean>) {
    TrackHeaderHidden(
        key = state,
        hidden = hidden,
        position = { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
    )
}

@Composable
private fun TrackHeaderHidden(
    key: Any,
    hidden: MutableState<Boolean>,
    position: () -> Pair<Int, Int>,
) {
    LaunchedEffect(key) {
        var previous = position()
        hidden.value = false
        snapshotFlow(position).collect { current ->
            val (index, offset) = current
            val (previousIndex, previousOffset) = previous
            when {
                index == 0 && offset <= TopSlopPx -> {
                    hidden.value = false
                    previous = current
                }
                index > previousIndex || (index == previousIndex && offset - previousOffset >= DirectionSlopPx) -> {
                    hidden.value = true
                    previous = current
                }
                index < previousIndex || (index == previousIndex && previousOffset - offset >= DirectionSlopPx) -> {
                    hidden.value = false
                    previous = current
                }
            }
        }
    }
    DisposableEffect(hidden) {
        onDispose { hidden.value = false }
    }
}
