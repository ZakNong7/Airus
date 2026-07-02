package com.zaknong.airus.ui.component

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zaknong.airus.R
import com.zaknong.airus.ui.Screens

@Composable
fun FloatingNavigationToolbar(
    items: List<Screens>,
    pureBlack: Boolean,
    modifier: Modifier = Modifier,
    isSelected: (Screens) -> Boolean,
    onItemClick: (Screens, Boolean) -> Unit,
) {
    val toolbarContainerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val showSelectedLabels = maxWidth >= 360.dp

        Surface(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(32.dp),
            color = toolbarContainerColor,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            ToolbarItemsContainer(
                items = items,
                pureBlack = pureBlack,
                showSelectedLabels = showSelectedLabels,
                isSelected = isSelected,
                onItemClick = onItemClick,
            )
        }
    }
}

@Composable
private fun ToolbarItemsContainer(
    items: List<Screens>,
    pureBlack: Boolean,
    showSelectedLabels: Boolean,
    isSelected: (Screens) -> Boolean,
    onItemClick: (Screens, Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val itemWidths = remember { mutableStateMapOf<Screens, Dp>() }
    val itemPositions = remember { mutableStateMapOf<Screens, Dp>() }

    val activeScreen = items.find { isSelected(it) }
    val targetWidth = itemWidths[activeScreen] ?: 0.dp
    val targetPosition = itemPositions[activeScreen] ?: 0.dp

    val slidingPillWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pillWidth",
    )

    val slidingPillOffset by animateDpAsState(
        targetValue = targetPosition,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pillOffset",
    )

    Box(modifier = Modifier.height(IntrinsicSize.Min)) {
        if (targetWidth > 0.dp) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(slidingPillOffset.roundToPx(), 0) }
                    .width(slidingPillWidth)
                    .fillMaxHeight()
                    .background(
                        color = if (pureBlack) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(24.dp),
                    ),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            items.forEach { screen ->
                val selected = isSelected(screen)
                FloatingNavigationToolbarItem(
                    screen = screen,
                    selected = selected,
                    showSelectedLabel = showSelectedLabels,
                    pureBlack = pureBlack,
                    onClick = { onItemClick(screen, selected) },
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        itemWidths[screen] = with(density) { coordinates.size.width.toDp() }
                        itemPositions[screen] = with(density) { coordinates.positionInParent().x.toDp() }
                    },
                )
            }
        }
    }
}

@Composable
private fun FloatingNavigationToolbarItem(
    screen: Screens,
    selected: Boolean,
    showSelectedLabel: Boolean,
    pureBlack: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    val showLabel = selected && showSelectedLabel
    
    val contentColor = if (selected) {
        if (pureBlack) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        if (pureBlack) Color.White.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant
    }

    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1.0f,
        label = "iconScale",
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.91f else 1f,
        label = "pressScale",
    )

    Row(
        modifier = modifier
            .scale(pressScale)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                role = Role.Tab,
                onClick = onClick,
            ).widthIn(min = 48.dp)
            .padding(horizontal = if (showLabel) 16.dp else 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(if (selected) screen.iconIdActive else screen.iconIdInactive),
            contentDescription = stringResource(screen.titleId),
            tint = contentColor,
            modifier = Modifier.scale(iconScale)
        )

        AnimatedVisibility(
            visible = showLabel,
            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(screen.titleId),
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
