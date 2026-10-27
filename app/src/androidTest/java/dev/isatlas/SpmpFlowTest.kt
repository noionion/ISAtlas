package dev.isatlas

import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SpmpFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun closeFilters() {
        val panel = compose.onNodeWithTag("filter-panel").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(Offset(panel.left / 2, centerY)) }
        compose.waitForIdle()
    }

    @Before fun ready() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("catalog-tab-CSR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("重置筛选").performScrollTo().performClick()
        closeFilters()
        compose.onNodeWithTag("catalog-tab-CSR").performClick()
    }

    @Test fun sourceFindsExistingPmpAndIndirectConfigSurvivesRotation() {
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("source-spmp").performScrollTo().performClick()
        closeFilters()
        compose.onNodeWithTag("search-input").performTextReplacement("pmpaddr0")
        compose.onNode(hasText("pmpaddr0") and hasText("M · CSR")).assertIsDisplayed()
        compose.onNodeWithTag("search-input").performTextReplacement("sireg2.spmpcfg.SHARED")
        compose.onNode(hasText("sireg2.spmpcfg.SHARED") and hasText("S · FIELD")).performClick()
        compose.onNodeWithTag("register-detail-list").performScrollToIndex(1)
        compose.onNodeWithTag("csr-view-spmpcfg").assertIsSelected()
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
        compose.onNodeWithTag("csr-view-generic").performClick().assertIsSelected()
        compose.onNodeWithTag("csr-view-spmpcfg").performClick().assertIsSelected()
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        compose.onNodeWithTag("csr-view-spmpcfg").assertIsSelected()
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
    }

    @Test fun delegationUsesItsDifferentRv32Boundary() {
        compose.onNodeWithTag("search-input").performTextReplacement("mpmpdeleg.PMPNUM")
        compose.onNode(hasText("mpmpdeleg.PMPNUM") and hasText("M · FIELD")).performClick()
        compose.onNodeWithText("PMPNUM  [6:0]").assertIsDisplayed()
        compose.onNodeWithTag("register-detail-list").performScrollToIndex(0)
        compose.onNode(hasText("RV32") and hasAnyAncestor(hasTestTag("register-detail-list"))).performClick()
        compose.onNodeWithTag("register-detail-list").performScrollToNode(hasText("PMPNUM  [6:2]"))
        compose.onNodeWithText("PMPNUM  [6:2]").assertIsDisplayed()
    }

    @Test fun sourceShowsRatifiedVersionAndLicense() {
        compose.onNodeWithContentDescription("关于 ISAtlas").performClick()
        compose.onNodeWithTag("about-database-version").performScrollTo().assertTextEquals("0.5.4")
        compose.onNodeWithTag("about-sources").performScrollTo().performClick()
        compose.onNodeWithTag("source-revision-spmp").performScrollTo().assertTextEquals("4ce228207193c55247200110e3d5b6881a17c367")
        compose.onNodeWithTag("source-license-spmp-CC-BY-4.0").performScrollTo().performClick()
        compose.onNodeWithTag("source-license-text-spmp-CC-BY-4.0").assertTextContains("Attribution", substring = true)
    }
}
