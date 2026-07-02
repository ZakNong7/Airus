package com.zaknong.airus.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

const val DISMISSED_ANCHOR = 0
const val COLLAPSED_ANCHOR = 1
const val EXPANDED_ANCHOR = 2

@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    onDismiss: (() -> Unit)? = null,
    collapsedContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(state.value)
            .clip(RoundedCornerShape(topStart = if (!state.isExpanded) 16.dp else 0.dp, topEnd = if (!state.isExpanded) 16.dp else 0.dp))
            .background(backgroundColor.copy(alpha = backgroundColor.alpha * state.progress.coerceIn(0f, 1f)))
            .bottomSheetDraggable(state, onDismiss),
    ) {
        if (state.isExpandedOrExpanding) {
            BackHandler(onBack = state::collapse)
        }

        if (!state.isCollapsed) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = ((state.progress - 0.25f) * 4).coerceIn(0f, 1f) },
                content = content,
            )
        }

        if (!state.isExpanded && (onDismiss == null || !state.isDismissed)) {
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = 1f - (state.progress * 4).coerceAtMost(1f) }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = state::expand,
                    )
                    .fillMaxWidth()
                    .height(state.collapsedBound),
                content = collapsedContent,
            )
        }
    }
}

@Stable
class BottomSheetState(
    draggableState: DraggableState,
    private val coroutineScope: CoroutineScope,
    private val animatable: Animatable<Dp, AnimationVector1D>,
    val collapsedBound: Dp,
) : DraggableState by draggableState {
    val dismissedBound: Dp get() = animatable.lowerBound!!
    val expandedBound: Dp get() = animatable.upperBound!!
    val value by animatable.asState()

    val isDismissed by derivedStateOf { value == dismissedBound }
    val isCollapsed by derivedStateOf { value == collapsedBound }
    val isExpanded by derivedStateOf { value == expandedBound }
    val isExpandedOrExpanding by derivedStateOf { value > collapsedBound }

    val progress by derivedStateOf {
        if (expandedBound == collapsedBound) 1f
        else (value - collapsedBound) / (expandedBound - collapsedBound)
    }

    fun collapse() {
        coroutineScope.launch { animatable.animateTo(collapsedBound) }
    }

    fun expand() {
        coroutineScope.launch { animatable.animateTo(expandedBound) }
    }

    fun dismiss() {
        coroutineScope.launch { animatable.animateTo(dismissedBound) }
    }

    fun snapTo(target: Dp) {
        coroutineScope.launch { animatable.snapTo(target) }
    }

    fun performFling(velocity: Float, onDismiss: (() -> Unit)?) {
        if (velocity > 500) expand()
        else if (velocity < -500) {
            if (value < collapsedBound && onDismiss != null) {
                dismiss()
                onDismiss()
            } else collapse()
        } else {
            if (value > (collapsedBound + expandedBound) / 2) expand()
            else if (value > (dismissedBound + collapsedBound) / 2) collapse()
            else {
                if (onDismiss != null) {
                    dismiss()
                    onDismiss()
                } else collapse()
            }
        }
    }
}

@Composable
fun rememberBottomSheetState(
    dismissedBound: Dp,
    expandedBound: Dp,
    collapsedBound: Dp = dismissedBound,
    initialAnchor: Int = DISMISSED_ANCHOR,
): BottomSheetState {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val animatable = remember { Animatable(0.dp, Dp.VectorConverter) }

    return remember(dismissedBound, expandedBound, collapsedBound, coroutineScope) {
        val initialValue = when (initialAnchor) {
            EXPANDED_ANCHOR -> expandedBound
            COLLAPSED_ANCHOR -> collapsedBound
            else -> dismissedBound
        }

        animatable.updateBounds(dismissedBound.coerceAtMost(expandedBound), expandedBound)
        if (animatable.value == 0.dp) {
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                animatable.snapTo(initialValue)
            }
        }

        BottomSheetState(
            draggableState = DraggableState { delta ->
                coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    animatable.snapTo(animatable.value - with(density) { delta.toDp() })
                }
            },
            coroutineScope = coroutineScope,
            animatable = animatable,
            collapsedBound = collapsedBound,
        )
    }
}

@Composable
fun Modifier.bottomSheetDraggable(
    state: BottomSheetState,
    onDismiss: (() -> Unit)? = null,
): Modifier = this.pointerInput(state) {
    val velocityTracker = VelocityTracker()
    detectVerticalDragGestures(
        onVerticalDrag = { change, dragAmount ->
            velocityTracker.addPointerInputChange(change)
            state.dispatchRawDelta(dragAmount)
        },
        onDragEnd = {
            val velocity = -velocityTracker.calculateVelocity().y
            velocityTracker.resetTracking()
            state.performFling(velocity, onDismiss)
        },
        onDragCancel = {
            velocityTracker.resetTracking()
            state.collapse()
        }
    )
}
