package dev.isatlas

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AtlasFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun csr(name: String) = compose.onNode(hasText(name) and hasText("M · CSR"))

    private fun closeFilters() {
        val panel = compose.onNodeWithTag("filter-panel").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(panel.left / 2f, centerY)) }
        compose.onNodeWithTag("filter-width").assertIsNotDisplayed()
    }

    @Before fun ready() {
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("search-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("RV64").performClick()
        compose.onNodeWithTag("filter-permission-all").performClick()
        closeFilters()
    }

    @Test fun searchAddressAndQualifiedField() {
        compose.onNodeWithTag("search-input").performTextReplacement("0x300")
        compose.onNodeWithTag("search-group-CSR").assertIsDisplayed()
        compose.onNodeWithTag("search-group-FIELD").assertDoesNotExist()
        compose.onNodeWithTag("search-group-DESCRIPTION").assertDoesNotExist()
        compose.onNodeWithText("当前数据范围内没有匹配项").assertDoesNotExist()
        csr("mstatus").performClick()
        compose.onNodeWithText("desc: status").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("search-input").performTextReplacement("zz_no_csr_matches_zz")
        compose.onNodeWithText("当前数据范围内没有匹配项").assertIsDisplayed()
        compose.onNodeWithTag("search-group-CSR").assertDoesNotExist()
        compose.onNodeWithTag("search-group-FIELD").assertDoesNotExist()
        compose.onNodeWithTag("search-group-DESCRIPTION").assertDoesNotExist()
        compose.onNodeWithTag("search-input").performTextReplacement("mstatus.MIE")
        compose.onNodeWithTag("search-group-CSR").assertDoesNotExist()
        compose.onNodeWithTag("search-group-FIELD").assertIsDisplayed()
        compose.onNode(hasText("mstatus.MIE") and hasText("M · FIELD")).performClick()
        compose.onNodeWithText("MIE  [3]").assertIsDisplayed()
    }

    @Test fun searchGroupsCollapseAndRetainStateWhenReturning() {
        compose.onNodeWithTag("search-input").performTextReplacement("MIE")
        val grid = compose.onNodeWithTag("search-results")
        val open = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已展开")
        val closed = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已收起")
        compose.onNodeWithTag("search-group-CSR").assert(open).performClick().assert(closed)
        compose.onNode(hasText("mie") and hasText("M · CSR")).assertDoesNotExist()
        compose.onNodeWithTag("search-group-FIELD").assert(open)
        grid.performScrollToNode(hasText("mstatus.MIE"))
        compose.onNode(hasText("mstatus.MIE") and hasText("M · FIELD")).performClick()
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("search-group-CSR").assert(closed)
        compose.onNodeWithTag("search-group-FIELD").performScrollTo().performClick().assert(closed)
        compose.onNodeWithTag("search-group-DESCRIPTION").assert(open).performClick().assert(closed)
        compose.onNodeWithText("当前数据范围内没有匹配项").assertDoesNotExist()
        compose.onNodeWithTag("search-group-CSR").performScrollTo().performClick().assert(open)
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), "search-groups.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("search-group-FIELD").performScrollTo().performClick()
        compose.onNodeWithTag("search-group-DESCRIPTION").performScrollTo().performClick()
    }

    @Test fun widthFilterAndNoResults() {
        compose.onNodeWithTag("search-input").performTextReplacement("mstatush")
        compose.onNodeWithText("当前数据范围内没有匹配项").assertIsDisplayed()
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("RV32").performClick()
        closeFilters()
        csr("mstatush").performClick()
        compose.onNodeWithText("desc: status high").assertIsDisplayed()
    }

    @Test fun rightFilterPanelSupportsDraggingAndSelectsPrerequisites() {
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("完成").assertDoesNotExist()
        val heading = compose.onNodeWithText("筛选").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("filter-panel").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(panel.left + 30f, heading.center.y),
                androidx.compose.ui.geometry.Offset(width - 5f, heading.center.y), 300)
        }
        compose.onNodeWithTag("filter-width").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("筛选").performClick()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(panel.left > root.left + root.width * 0.05f)
        org.junit.Assert.assertEquals(root.right, panel.right, 2f)
        compose.onNodeWithTag("filter-width").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * 0.85f, centerY), androidx.compose.ui.geometry.Offset(width * 0.15f, centerY), 300)
        }
        compose.onNodeWithTag("filter-width-RV32").assertIsSelected()
        compose.onNodeWithTag("filter-permission").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * 0.1f, centerY), androidx.compose.ui.geometry.Offset(width * 0.95f, centerY), 300)
        }
        compose.onNodeWithTag("filter-permission-D").assertIsSelected()
        compose.onNodeWithTag("filter-permission").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * 0.95f, centerY), androidx.compose.ui.geometry.Offset(width * 0.35f, centerY), 300)
        }
        compose.onNodeWithTag("filter-permission-S").assertIsSelected()
        compose.onNodeWithTag("filter-permission-all").performClick()
        compose.onNodeWithTag("extension-search").performScrollTo().performTextReplacement("d")
        compose.onNodeWithTag("extension-d").performClick().assertIsSelected()
        compose.onNodeWithTag("extension-search").performTextReplacement("f")
        compose.onNodeWithTag("extension-f").assertIsSelected().performClick().assertIsSelected()
        compose.onNodeWithTag("extension-search").performTextReplacement("d")
        compose.onNodeWithTag("extension-d").performClick().assertIsNotSelected()
        compose.onNodeWithTag("extension-search").performTextReplacement("f")
        compose.onNodeWithTag("extension-f").assertIsNotSelected()
        compose.onNodeWithTag("extension-search").performTextReplacement("h")
        compose.onNodeWithTag("extension-h").performClick()
        compose.onNodeWithTag("extension-search").performTextReplacement("s")
        compose.onNodeWithTag("extension-s").assertIsSelected()
        compose.onNodeWithTag("extension-search").performTextReplacement("u")
        compose.onNodeWithTag("extension-u").assertIsSelected()
        compose.onNodeWithText("全部扩展").performScrollTo().performClick()
        compose.onNodeWithTag("extension-search").performScrollTo().performTextReplacement("Zba")
        compose.onNodeWithTag("extension-zba").assertDoesNotExist()
        compose.onNodeWithText("没有收录 CSR 的匹配扩展").assertIsDisplayed()
        compose.onNodeWithTag("extension-search").performScrollTo().performTextReplacement("Zicfiss")
        compose.onNodeWithTag("extension-zicfiss").assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithText("全部扩展").performScrollTo().performClick()
        compose.onNodeWithText("筛选").performScrollTo()
        saveFilterScreen("filters-right.png")
        try {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithTag("filter-panel").assertIsDisplayed()
            compose.onNodeWithTag("filter-width").assertIsDisplayed()
            compose.onNodeWithTag("filter-permission").assertIsDisplayed()
            compose.onNodeWithTag("source-all").assertIsDisplayed()
            compose.onNodeWithTag("extension-search").assertIsDisplayed()
            org.junit.Assert.assertTrue(compose.onNodeWithTag("extension-search").fetchSemanticsNode().boundsInRoot.left >
                compose.onNodeWithTag("filter-width").fetchSemanticsNode().boundsInRoot.right)
            compose.onNodeWithTag("source-standard").performClick().assertIsSelected()
            saveFilterScreen("filters-right-landscape.png")
            compose.onNodeWithText("重置筛选").performClick()
            closeFilters()
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        }
    }

    private fun saveFilterScreen(name: String) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun returningFromDetailPreservesListPosition() {
        compose.onNodeWithTag("search-input").performTextReplacement("")
        val grid = compose.onNodeWithTag("search-results")
        grid.performScrollToNode(hasText("mhpmcounter10"))
        fun scrollPosition() = grid.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        val position = scrollPosition()
        org.junit.Assert.assertTrue(position > 0f)
        val cardTop = csr("mhpmcounter10").fetchSemanticsNode().boundsInRoot.top
        csr("mhpmcounter10").performClick()
        compose.onNodeWithContentDescription("返回搜索").performClick()
        org.junit.Assert.assertEquals(position, scrollPosition(), 0.01f)
        org.junit.Assert.assertEquals(cardTop, csr("mhpmcounter10").fetchSemanticsNode().boundsInRoot.top, 1f)
        csr("mhpmcounter10").performClick()
        compose.onNodeWithContentDescription("返回搜索").assertIsDisplayed()
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        org.junit.Assert.assertEquals(position, scrollPosition(), 0.01f)
        compose.onNodeWithTag("search-input").performTextReplacement("m")
        org.junit.Assert.assertEquals(0f, scrollPosition(), 0.01f)
        grid.performScrollToNode(hasText("mhpmcounter10"))
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("RV32").performClick()
        closeFilters()
        org.junit.Assert.assertEquals(0f, scrollPosition(), 0.01f)
    }

    @Test fun importedVendorFieldsAndAddressOnlyEntriesAreDistinct() {
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("RV32").performClick()
        compose.onNodeWithTag("source-andes").performScrollTo().performClick().assertIsSelected()
        saveFilterScreen("filters-sources.png")
        closeFilters()
        compose.onNodeWithTag("search-input").performTextReplacement("mcache_ctl")
        csr("mcache_ctl").performClick()
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("重置筛选").performScrollTo().performClick()
        closeFilters()
        compose.onNodeWithTag("search-input").performTextReplacement("stopei")
        compose.onNode(hasText("stopei") and hasText("S · CSR")).performClick()
        compose.onNodeWithText("仅收录地址，字段布局待补充。").assertIsDisplayed()
        compose.onNodeWithContentDescription("数据包信息").performClick()
        compose.onNodeWithText("数据来源与许可").performClick()
        compose.onNodeWithText("数据来源与许可").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
    }

    @Test fun compactHomeAndDrawerFilters() {
        compose.onNodeWithText("从名字到每一个 bit。").assertDoesNotExist()
        compose.onNodeWithText("收藏").assertDoesNotExist()
        compose.onNodeWithText("最近").assertDoesNotExist()
        compose.onNodeWithText("RV32").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("filter-permission-S").performClick()
        closeFilters()
        compose.onNodeWithTag("search-input").performTextReplacement("sscratch")
        compose.onNode(hasText("sscratch") and hasText("S · CSR")).assertIsDisplayed()
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("filter-permission-M").performClick()
        closeFilters()
        compose.onNodeWithText("当前数据范围内没有匹配项").assertIsDisplayed()
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("重置筛选").performScrollTo().performClick()
        closeFilters()
        compose.onNodeWithContentDescription("清空搜索").performClick()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), "home.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun landscapeHomeUsesInlineSearchAndMultipleColumns() {
        compose.onNodeWithTag("search-input").performTextReplacement("")
        try {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) {
                val title = compose.onAllNodesWithTag("app-title").fetchSemanticsNodes().firstOrNull()?.boundsInRoot
                val search = compose.onAllNodesWithTag("search-input").fetchSemanticsNodes().firstOrNull()?.boundsInRoot
                title != null && search != null && search.left > title.right && search.top < title.bottom
            }
            val first = csr("mstatus").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("search-input").assertHeightIsEqualTo(androidx.compose.ui.unit.Dp(40f))
            org.junit.Assert.assertTrue(compose.onNodeWithTag("search-input").fetchSemanticsNode().boundsInRoot.width <= 480f * compose.activity.resources.displayMetrics.density + 1f)
            compose.waitUntil(5_000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars()) == false
            }
            val second = csr("misa").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertEquals(first.top, second.top, 2f)
            org.junit.Assert.assertTrue(second.left > first.right)
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(context.getExternalFilesDir(null), "home-landscape.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("search-input").performTextReplacement("0x300")
            csr("mstatus").performClick()
            compose.onNodeWithContentDescription("返回搜索").performClick()
            compose.onNodeWithTag("search-input").assertTextContains("0x300")
            compose.onNodeWithContentDescription("筛选").performClick()
            compose.onNodeWithText("重置筛选").performClick()
            closeFilters()
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        }
    }
}
