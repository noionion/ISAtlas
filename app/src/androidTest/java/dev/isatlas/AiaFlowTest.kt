package dev.isatlas

import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AiaFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var officialSession: OfficialPackageTestSession? = null
    @After fun restoreOfficialSelection() { officialSession?.close() }
    private fun closeFilters() {
        val panel = compose.onNodeWithTag("filter-panel").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(Offset(panel.left / 2, centerY)) }
        compose.waitForIdle()
    }

    @Before fun ready() {
        officialSession = OfficialPackageTestSession(compose, setOf("aia"))
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("catalog-tab-CSR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("重置筛选").performScrollTo().performClick()
        closeFilters()
        compose.onNodeWithTag("catalog-tab-CSR").performClick()
    }

    @Test fun topInterruptAndHighHalfLayoutsAreAvailable() {
        compose.onNodeWithTag("search-input").performTextReplacement("mtopi")
        compose.onNode(hasText("mtopi") and hasText("M · CSR")).performClick()
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("search-input").performTextReplacement("mieh")
        compose.onNodeWithText("当前数据范围内没有匹配项").assertIsDisplayed()
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("filter-width-RV32").performClick()
        closeFilters()
        compose.onNode(hasText("mieh") and hasText("M · CSR")).performClick()
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
    }

    @Test fun aiaFilterIncludesSupplementedManualCsrAndPreservesDiagramInLandscape() {
        compose.onNodeWithTag("search-input").performTextReplacement("mip")
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("source-aia").performScrollTo().performClick()
        closeFilters()
        compose.onNode(hasText("mip") and hasText("M · CSR")).performClick()
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
        compose.onNodeWithTag("register-detail-list").performScrollToNode(hasTestTag("csr-source-aia"))
        compose.onNodeWithTag("csr-source-aia").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        compose.onNodeWithTag("register-detail-list").performScrollToNode(hasTestTag("register-diagram"))
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
    }

    @Test fun aboutShowsAiaVersionAndLicenseAlongsideManual() {
        compose.onNodeWithContentDescription("关于 ISAtlas").performClick()
        compose.onNodeWithTag("about-database-version").performScrollTo().assertTextEquals("0.5.4")
        compose.onNodeWithTag("about-sources").performScrollTo().performClick()
        compose.onNodeWithTag("source-revision-aia").performScrollTo().assertTextEquals("20318d1b03f0ac8253458b82f8244d0d0db074e9")
        compose.onNodeWithTag("source-license-aia-CC-BY-4.0").performScrollTo().performClick()
        compose.onNodeWithTag("source-license-text-aia-CC-BY-4.0").assertTextContains("Attribution", substring = true)
        compose.onNodeWithTag("source-license-aia-CC-BY-4.0").performScrollTo().performClick()
        compose.onNodeWithTag("source-revision-manual").performScrollTo().assertIsDisplayed()
    }
}
