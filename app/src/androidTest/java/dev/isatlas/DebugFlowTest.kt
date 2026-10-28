package dev.isatlas

import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DebugFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var officialSession: OfficialPackageTestSession? = null
    @After fun restoreOfficialSelection() { officialSession?.close() }
    private fun closeFilters() {
        val panel = compose.onNodeWithTag("filter-panel").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(Offset(panel.left / 2, centerY)) }
        compose.waitForIdle()
    }

    @Before fun ready() {
        officialSession = OfficialPackageTestSession(compose, setOf("debug"))
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("catalog-tab-CSR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("重置筛选").performScrollTo().performClick()
        closeFilters()
        compose.onNodeWithTag("catalog-tab-CSR").performClick()
    }

    @Test fun debugSourceAndPrivilegeFindExistingDcsrWithFields() {
        compose.onNodeWithTag("search-input").performTextReplacement("dcsr")
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("source-debug").performScrollTo().performClick()
        compose.onNodeWithTag("filter-permission-D").performScrollTo().performClick()
        closeFilters()
        compose.onNode(hasText("dcsr") and hasText("D · CSR")).performClick()
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
        compose.onNodeWithTag("register-detail-list").performScrollToNode(hasTestTag("csr-source-debug"))
        compose.onNodeWithTag("csr-source-debug").assertIsDisplayed()
    }

    @Test fun fieldSearchSelectsTheCorrectTriggerFormatAndSurvivesRotation() {
        compose.onNodeWithTag("search-input").performTextReplacement("tdata1.mcontrol6.ACTION")
        compose.onNode(hasText("tdata1.mcontrol6.ACTION") and hasText("M · FIELD")).performClick()
        compose.onNodeWithTag("register-detail-list").performScrollToIndex(1)
        compose.onNodeWithTag("csr-view-mcontrol6").assertIsSelected()
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
        compose.onNodeWithTag("csr-view-generic").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("csr-view-mcontrol6").performScrollTo().performClick().assertIsSelected()
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        compose.onNodeWithTag("csr-view-mcontrol6").assertIsSelected()
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
    }

    @Test fun textraFieldsUseDifferentRv32AndRv64BitPositions() {
        compose.onNodeWithTag("search-input").performTextReplacement("tdata3.textra.MHSELECT")
        compose.onNode(hasText("tdata3.textra.MHSELECT") and hasText("M · FIELD")).performClick()
        compose.onNodeWithText("textra.MHSELECT  [50:48]").assertIsDisplayed()
        compose.onNodeWithTag("register-detail-list").performScrollToIndex(0)
        compose.onNode(hasText("RV32") and hasAnyAncestor(hasTestTag("register-detail-list"))).performClick()
        compose.onNodeWithTag("register-detail-list").performScrollToNode(hasText("textra.MHSELECT  [25:23]"))
        compose.onNodeWithText("textra.MHSELECT  [25:23]").assertIsDisplayed()
    }

    @Test fun sourcesShowPinnedDebugVersionAndLicense() {
        compose.onNodeWithContentDescription("关于 ISAtlas").performClick()
        compose.onNodeWithTag("about-database-version").performScrollTo().assertTextEquals("0.5.4")
        compose.onNodeWithTag("about-sources").performScrollTo().performClick()
        compose.onNodeWithTag("source-revision-debug").performScrollTo().assertTextEquals("5695c0aaa7a3c228e14702e1268eb5a5e50e3cb8")
        compose.onNodeWithTag("source-license-debug-CC-BY-4.0").performScrollTo().performClick()
        compose.onNodeWithTag("source-license-text-debug-CC-BY-4.0").assertTextContains("Attribution", substring = true)
    }
}
