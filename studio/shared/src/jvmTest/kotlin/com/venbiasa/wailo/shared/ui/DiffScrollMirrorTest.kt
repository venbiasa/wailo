package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Deep enough that the positions the test asks for are real places rather than the end of a short list, where
// both panes would clamp together and agree for the wrong reason.
private const val ROWS = 400

// Stands in for the compare panel's two panes: rows of one height in both, which is what the panel's shared
// row grid buys and the reason one index + offset names the same place in either. Laid out for real, because
// a `LazyListState` refuses to scroll before its list's first layout.
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.setPanes(): Pair<LazyListState, LazyListState> {
    lateinit var left: LazyListState
    lateinit var right: LazyListState
    setContent {
        left = rememberLazyListState()
        right = rememberLazyListState()
        Row(Modifier.size(400.dp, 200.dp)) {
            Pane(left, Modifier.weight(1f))
            Pane(right, Modifier.weight(1f))
        }
    }
    waitForIdle()
    return left to right
}

@Composable
private fun Pane(state: LazyListState, modifier: Modifier) {
    LazyColumn(state = state, modifier = modifier.fillMaxHeight()) {
        items(List(ROWS) { it }) { Box(Modifier.fillMaxWidth().height(20.dp)) }
    }
}

// A pane held the way `scrollable` holds one under a gesture: its mutex taken at UserInput priority for as
// long as the drag and its fling last. Everything the mirror does is Default priority, so this is what a
// follow has to lose to.
private fun kotlinx.coroutines.CoroutineScope.holdAsGesture(pane: LazyListState): Job =
    launch { pane.scroll(MutatePriority.UserInput) { awaitCancellation() } }

// `snapshotFlow` wakes on an apply notification, which an app gets from the frame clock and a test sends for
// itself. Bounded, so a mirror that never settles fails an assertion rather than hanging the suite.
private suspend fun pump() {
    repeat(10) {
        Snapshot.sendApplyNotifications()
        yield()
    }
}

/** The scroll mirror that keeps the compare panel's two columns describing the same row. */
class DiffScrollMirrorTest {

    /**
     * The bug the panel shipped with: scrolling one pane carried the other, while scrolling *that* one carried
     * nothing back. Both directions start alive, so the asymmetry was earned at runtime — one wheel or drag on
     * either pane was enough to end the opposite watcher for as long as the panel stayed open.
     *
     * Moving a pane makes the opposite watcher copy the position back at it, and that copy lands on the pane
     * the gesture still holds: `scrollToItem` is a Default-priority mutation, so it loses that list's mutex and
     * is cancelled — and the cancellation used to travel out through the collector, ending a watcher that
     * nothing restarts. The mirror is run from here rather than from a `LaunchedEffect` because its surviving
     * *is* the assertion, and that needs a handle on the job.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aFollowItCannotWinDoesNotEndTheWatcher() = runComposeUiTest {
        val (left, right) = setPanes()

        coroutineScope {
            val toLeft = launch { mirrorScroll(right, left) }
            val toRight = launch { mirrorScroll(left, right) }
            val gesture = holdAsGesture(left)
            pump()

            // The echo: the panel copying the other pane's position back at the pane being dragged.
            right.scrollToItem(7)
            pump()
            assertTrue(toLeft.isActive, "the watcher outlived a follow it could not win")
            assertTrue(toRight.isActive, "and so did the one that was mirroring the dragged pane")

            // Which is what surviving is worth: the direction still works once the gesture lets go.
            gesture.cancelAndJoin()
            right.scrollToItem(9)
            pump()
            assertEquals(9, left.firstVisibleItemIndex, "the left pane followed the right")

            toLeft.cancel()
            toRight.cancel()
        }
    }
}
