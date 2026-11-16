package com.drinkcat.nekopost.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.token.NekoTokens

/**
 * List + detail on material3-adaptive's ListDetailPaneScaffold, driven by
 * hoisted state rather than a navigator so the selection survives every
 * posture change (it lives in ShellMemory):
 *  - [twoPane]: both panes, list at ~42 % of the width;
 *  - otherwise one pane: the detail while [showDetail], else the list; system
 *    back returns to the list.
 * A separating hinge (book posture) is passed to the scaffold as an excluded
 * area, so neither pane is laid out across it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AdaptiveListDetail(
    fold: FoldState,
    twoPane: Boolean,
    showDetail: Boolean,
    onBack: () -> Unit,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = !twoPane && showDetail, onBack = onBack)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val listWidth = (maxWidth * 0.42f).coerceIn(300.dp, 420.dp)
        val hinge = if (fold.isSeparating) fold.hinge else null
        val directive = PaneScaffoldDirective(
            maxHorizontalPartitions = if (twoPane) 2 else 1,
            horizontalPartitionSpacerSize = if (twoPane) NekoTokens.Spacing.s else 0.dp,
            maxVerticalPartitions = 1,
            verticalPartitionSpacerSize = 0.dp,
            defaultPanePreferredWidth = listWidth,
            defaultPanePreferredHeight = PaneScaffoldDirective.Default.defaultPanePreferredHeight,
            excludedBounds = hinge?.let { listOf(it) } ?: emptyList(),
            // Moving focus into the detail pane would focus its first text field
            // (app search, rule pattern) and pop up the keyboard on every selection.
            shouldAutoFocusCurrentDestination = false,
        )
        val destination = ThreePaneScaffoldDestinationItem<Nothing>(
            if (showDetail) ListDetailPaneScaffoldRole.Detail else ListDetailPaneScaffoldRole.List,
        )
        val value = calculateThreePaneScaffoldValue(
            maxHorizontalPartitions = directive.maxHorizontalPartitions,
            adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
            currentDestination = destination,
        )
        ListDetailPaneScaffold(
            directive = directive,
            value = value,
            listPane = { AnimatedPane { list() } },
            detailPane = { AnimatedPane { detail() } },
        )
    }
}
