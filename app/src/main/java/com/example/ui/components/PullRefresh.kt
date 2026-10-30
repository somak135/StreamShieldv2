package com.example.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.example.ui.PULL_DRAG_RATE
import com.example.ui.PULL_REFRESH_THRESHOLD_DP
import com.example.ui.theme.ShieldCoral

private val INDICATOR_SIZE = 40.dp
/** The indicator stops following the finger here (1.6 x the refresh threshold). */
private const val PULL_MAX_FACTOR = 1.6f

/**
 * The pull-to-refresh circle: drawn [offsetPx] below the top of its box. While pulling, the ring
 * fills with [progress] (1 = release refreshes); while [refreshing] it spins. Used by the native
 * screens and the YouTube pages alike, so both look the same.
 */
@Composable
fun PullIndicator(offsetPx: Float, progress: Float, refreshing: Boolean, modifier: Modifier = Modifier) {
    if (offsetPx <= 0f && !refreshing) return
    val density = LocalDensity.current
    val sizePx = with(density) { INDICATOR_SIZE.toPx() }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Surface(
            shape = CircleShape,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .size(INDICATOR_SIZE)
                .graphicsLayer {
                    translationY = offsetPx - sizePx
                    val p = progress.coerceIn(0f, 1f)
                    alpha = if (refreshing) 1f else (0.3f + 0.7f * p)
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = ShieldCoral
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer { rotationZ = progress.coerceIn(0f, 1.6f) * 200f },
                        strokeWidth = 2.5.dp,
                        color = ShieldCoral
                    )
                }
            }
        }
    }
}

/** Where the circle sits for a web page pull of [distanceDp] (CSS px), or while refreshing. */
fun webPullOffsetPx(distanceDp: Float, refreshing: Boolean, density: Float): Float {
    val threshold = PULL_REFRESH_THRESHOLD_DP * density
    val pulled = (distanceDp * PULL_DRAG_RATE * density).coerceAtMost(threshold * PULL_MAX_FACTOR)
    return if (refreshing) maxOf(pulled, threshold) else pulled
}

/**
 * Pull-to-refresh for a scrolling native screen (a LazyColumn inside [content]): pulling down
 * past the top shows [PullIndicator]; releasing beyond the threshold calls [onRefresh]. Built on
 * nested scrolling, so the list behaves exactly as before otherwise.
 */
@Composable
fun PullToRefreshContainer(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { PULL_REFRESH_THRESHOLD_DP.dp.toPx() }
    val maxPx = thresholdPx * PULL_MAX_FACTOR
    var pullPx by remember { mutableFloatStateOf(0f) }
    val currentRefreshing by rememberUpdatedState(refreshing)
    val currentOnRefresh by rememberUpdatedState(onRefresh)

    val connection = remember(thresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Scrolling back up while the circle is out: move the circle first.
                if (source != NestedScrollSource.UserInput || available.y >= 0f || pullPx <= 0f) return Offset.Zero
                val used = maxOf(available.y, -pullPx)
                pullPx += used
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Dragging down at the top of the list: what the list could not use pulls.
                if (source != NestedScrollSource.UserInput || currentRefreshing || available.y <= 0f) return Offset.Zero
                pullPx = (pullPx + available.y * PULL_DRAG_RATE).coerceAtMost(maxPx)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullPx <= 0f) return Velocity.Zero
                if (pullPx >= thresholdPx && !currentRefreshing) currentOnRefresh()
                animate(initialValue = pullPx, targetValue = 0f, animationSpec = tween(200)) { value, _ -> pullPx = value }
                return available
            }
        }
    }

    Box(modifier = modifier.nestedScroll(connection)) {
        content()
        PullIndicator(
            offsetPx = if (refreshing) maxOf(pullPx, thresholdPx) else pullPx,
            progress = pullPx / thresholdPx,
            refreshing = refreshing
        )
    }
}
