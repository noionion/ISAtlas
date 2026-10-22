package dev.isatlas

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertTrue

class RegisterDiagramFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun startPortrait() {
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
    }

    @Test fun diagramSelectionAndLaneSwitchPreserveFieldMeaning() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("search-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("RV64").performClick()
        compose.onNodeWithTag("filter-permission-all").performClick()
        val panel = compose.onNodeWithTag("filter-panel").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(Offset(panel.left / 2f, centerY)) }
        compose.onNodeWithTag("search-input").performTextReplacement("mstatus")
        compose.onNode(hasText("mstatus") and hasText("M · CSR")).performClick()
        compose.onNodeWithTag("register-detail-list").performScrollToIndex(1)
        compose.onNodeWithTag("diagram-MPP-12-11").assertIsDisplayed().performClick()
        compose.onNodeWithTag("diagram-MPP-12-11").assertIsSelected()
        saveDiagram("register-16.png")
        compose.onNodeWithText("MPP [12:11] · WARL").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("32 位/行").performScrollTo().performClick()
        compose.onNodeWithTag("diagram-MPP-12-11").assertIsSelected()
        compose.onNodeWithTag("register-detail-list").performScrollToIndex(1)
        saveDiagram("register-32.png")
        compose.onNodeWithText("查看字段说明").performScrollTo().performClick()
        compose.onNodeWithText("MPP  [12:11]").assertIsDisplayed()
    }

    @Test fun dragSelectsOneBitFieldsAndWideLabelsRemainReadable() {
        open("mstatus")
        val strip = compose.onNodeWithTag("diagram-strip-15-0")
        strip.performTouchInput {
            down(Offset(width * 8.5f / 16, centerY)) // MPIE
            moveTo(Offset(width * 12.5f / 16, centerY), 200) // MIE
        }
        compose.onNodeWithTag("diagram-MIE-3-3").assertIsSelected()
        strip.performTouchInput {
            moveTo(Offset(width * 14.5f / 16, centerY), 200) // SIE, across WPRI
            up()
        }
        compose.onNodeWithTag("diagram-SIE-1-1").assertIsSelected()
        compose.onNodeWithText("已选：SIE [1]", substring = true).assertIsDisplayed()
        saveDiagram("register-drag.png")
        compose.onNodeWithText("32 位/行").performScrollTo().performClick()
        compose.onNodeWithTag("register-detail-list").performScrollToIndex(1)
        val lanes = compose.onNodeWithTag("diagram-lanes")
        lanes.performTouchInput {
            swipe(Offset(width * 0.8f, 2f), Offset(width * 0.2f, 2f), 300)
        }
        assertTrue(lanes.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
        compose.onNodeWithContentDescription("返回搜索").performClick()
        open("mcause")
        saveDiagram("register-mcause.png")
        compose.onNodeWithContentDescription("返回搜索").performClick()
        open("sscratch", "S")
        saveDiagram("register-sscratch.png")
    }

    private fun open(name: String, privilege: String = "M") {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("search-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("RV64").performClick()
        compose.onNodeWithTag("filter-permission-all").performClick()
        val panel = compose.onNodeWithTag("filter-panel").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(Offset(panel.left / 2f, centerY)) }
        compose.onNodeWithTag("search-input").performTextReplacement(name)
        compose.onNode(hasText(name) and hasText("$privilege · CSR")).performClick()
        compose.onNodeWithTag("register-detail-list").performScrollToIndex(1)
    }

    @Test fun compactDetailShowsDiagramAndFollowsOrientation() {
        open("mcause")
        compose.onNodeWithTag("register-detail-list").performScrollToIndex(0)
        compose.onNodeWithText("0x342 | MRW").assertIsDisplayed()
        compose.onNodeWithText("desc: 机器模式陷阱原因").assertIsDisplayed()
        compose.onNodeWithText("16 位/行").assertIsSelected()
        compose.onNodeWithTag("diagram-CODE-15-0").assertIsDisplayed()
        saveScreen("register-detail-portrait.png")
        try {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("diagram-CODE-31-0").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("32 位/行").assertIsSelected()
            compose.onNodeWithText("0x342 | MRW").assertIsDisplayed()
            compose.onNodeWithTag("diagram-CODE-31-0").assertIsDisplayed()
            saveScreen("register-detail-landscape.png")
            compose.onNode(hasText("RV32") and hasAnyAncestor(hasTestTag("register-detail-list")) ).performClick()
            compose.onNodeWithTag("diagram-CODE-30-0").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        }
        compose.onNodeWithText("16 位/行").assertIsSelected()
    }

    private fun saveScreen(name: String) {
        val folder = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun saveDiagram(name: String) {
        val folder = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        val bitmap = compose.onNodeWithTag("register-diagram").captureToImage().asAndroidBitmap()
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
