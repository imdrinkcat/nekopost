package com.drinkcat.nekopost.ui

import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.ui.shell.PaneMode
import com.drinkcat.nekopost.ui.shell.navigationTypeFor
import com.drinkcat.nekopost.ui.shell.paneModeFor
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellLayoutTest {
    @Test
    fun paneModes() {
        assertEquals(PaneMode.Single, paneModeFor(FoldState.Cover, 475.dp))
        assertEquals(PaneMode.TwoPane, paneModeFor(FoldState.Flat, 837.dp))
        assertEquals(PaneMode.TwoPane, paneModeFor(FoldState.Flat, 608.dp))
        assertEquals(PaneMode.TopBottom, paneModeFor(FoldState.tabletop(), 704.dp))
        assertEquals(PaneMode.Single, paneModeFor(FoldState.Window, 466.dp))
        assertEquals(PaneMode.TwoPane, paneModeFor(FoldState.book(), 837.dp))
    }

    @Test
    fun navigationTypes() {
        assertEquals(NavigationSuiteType.ShortNavigationBarCompact, navigationTypeFor(FoldState.Cover, 475.dp))
        assertEquals(NavigationSuiteType.WideNavigationRailCollapsed, navigationTypeFor(FoldState.Flat, 933.dp))
        assertEquals(NavigationSuiteType.ShortNavigationBarMedium, navigationTypeFor(FoldState.tabletop(), 704.dp))
        assertEquals(NavigationSuiteType.ShortNavigationBarCompact, navigationTypeFor(FoldState.Window, 411.dp))
    }
}
