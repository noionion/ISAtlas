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
        compose.onNodeWithText("desc: 机器模式状态与陷阱控制").assertIsDisplayed()
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
        val hasDescription = compose.onAllNodesWithTag("search-group-DESCRIPTION").fetchSemanticsNodes().isNotEmpty()
        if (hasDescription) compose.onNodeWithTag("search-group-DESCRIPTION").assert(open).performClick().assert(closed)
        compose.onNodeWithText("当前数据范围内没有匹配项").assertDoesNotExist()
        compose.onNodeWithTag("search-group-CSR").performScrollTo().performClick().assert(open)
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), "search-groups.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("search-group-FIELD").performScrollTo().performClick()
    }

    @Test fun widthFilterAndNoResults() {
        compose.onNodeWithTag("search-input").performTextReplacement("mstatush")
        compose.onNodeWithText("当前数据范围内没有匹配项").assertIsDisplayed()
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("RV32").performClick()
        closeFilters()
        csr("mstatush").performClick()
        compose.onNodeWithText("desc: RV32 的机器状态高半部").assertIsDisplayed()
    }

    @Test fun fieldDescriptionsAndEnumsFollowSelectedWidth() {
        compose.onNodeWithTag("search-input").performTextReplacement("satp.MODE")
        compose.onNode(hasText("satp.MODE") and hasText("S · FIELD")).performClick()
        compose.onAllNodesWithText("选择地址转换模式；Bare 不执行本级分页，支持的分页模式由 XLEN 与实现决定。").onLast().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("8  →  Sv39").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1  →  Sv32").assertDoesNotExist()
        compose.onNodeWithTag("register-detail-list").performScrollToIndex(1)
        compose.onNode(hasText("RV32") and hasAnyAncestor(hasTestTag("register-detail-list"))).performClick()
        compose.onNodeWithText("1  →  Sv32").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("8  →  Sv39").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("search-input").performTextReplacement("mstatus.MIE")
        compose.onNode(hasText("mstatus.MIE") and hasText("M · FIELD")).performClick()
        compose.onAllNodesWithText("M 模式全局中断允许位。").onLast().performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("1  →  允许本级全局中断") and hasText("MIE  [3]")).performScrollTo().assertIsDisplayed()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.getExternalFilesDir(null), "field-description-zh.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
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
        compose.onNodeWithTag("extension-s").assertDoesNotExist()
        compose.onNodeWithTag("extension-search").performTextReplacement("u")
        compose.onNodeWithTag("extension-u").assertDoesNotExist()
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
            compose.onNodeWithTag("source-manual").performClick().assertIsSelected()
            saveFilterScreen("filters-right-landscape.png")
            compose.onNodeWithText("重置筛选").performClick()
            closeFilters()
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        }
    }

    private fun saveFilterScreen(name: String, tag: String? = null) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = (if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag)).captureToImage().asAndroidBitmap()
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

    @Test fun builtinDebugFieldsAndSourcesRemainAvailableWithoutVendorImport() {
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("RV64").performClick()
        compose.onNodeWithTag("source-andes").assertDoesNotExist()
        compose.onNodeWithTag("source-standard").assertDoesNotExist()
        compose.onNodeWithTag("source-manual").assertIsDisplayed()
        compose.onNodeWithTag("source-debug").performScrollTo().performClick().assertIsSelected()
        closeFilters()
        compose.onNodeWithTag("search-input").performTextReplacement("dcsr")
        compose.onNode(hasText("dcsr") and hasText("D · CSR")).performClick()
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
        compose.onNodeWithText("仅收录地址，字段布局待补充。").assertDoesNotExist()
        compose.onNodeWithContentDescription("关于 ISAtlas").performClick()
        compose.onNodeWithTag("about-sources").performScrollTo().performClick()
        compose.onNodeWithTag("sources-screen").assertIsDisplayed()
        compose.onNodeWithTag("source-license-manual-CC-BY-4.0").performScrollTo().performClick()
        compose.onNodeWithTag("source-license-text-manual-CC-BY-4.0").assertTextContains("Attribution 4.0 International", substring = true)
        compose.onNodeWithContentDescription("返回关于").performClick()
        compose.onNodeWithContentDescription("收起关于").performClick()
    }

    @Test fun aboutShowsIndependentVersionsSourcesAndLinksInBothOrientations() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName!!
        compose.onNodeWithContentDescription("关于 ISAtlas").performClick()
        compose.onNodeWithTag("about-app-version").assertTextEquals(appVersion)
        compose.onNodeWithTag("about-database-version").assertTextEquals("0.5.4")
        compose.onNodeWithTag("about-csr-count").assertDoesNotExist()
        compose.onNodeWithTag("about-field-count").assertDoesNotExist()
        compose.onNodeWithText("存储方式").assertDoesNotExist()
        saveFilterScreen("about-portrait.png", "about-screen")
        compose.onNodeWithTag("about-sources").performScrollTo().performClick()
        compose.onNodeWithTag("sources-screen").assertIsDisplayed()
        compose.onNodeWithTag("about-screen").assertDoesNotExist()
        compose.onNodeWithTag("source-revision-manual").performScrollTo().assertTextEquals("584390d9d872835b1bc45ab9ea946b56db3ad485")
        compose.onNodeWithTag("source-license-manual-CC-BY-4.0").performScrollTo().performClick()
        compose.onNodeWithTag("source-license-text-manual-CC-BY-4.0").assertTextContains("Attribution 4.0 International", substring = true)
        compose.onNodeWithTag("source-license-manual-CC-BY-4.0").performScrollTo().performClick()
        compose.onNodeWithTag("source-revision-spmp").performScrollTo().assertTextEquals("4ce228207193c55247200110e3d5b6881a17c367")
        try {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithTag("source-revision-manual").performScrollTo().assertIsDisplayed()
            saveFilterScreen("sources-landscape.png", "sources-screen")
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("sources-screen").assertDoesNotExist()
            compose.onNodeWithTag("about-app-version").performScrollTo().assertTextEquals(appVersion)
            compose.onNodeWithTag("about-github").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("about-feedback").performScrollTo().assertIsDisplayed()
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        compose.onNodeWithContentDescription("收起关于").performClick()
        compose.onNodeWithTag("search-input").performTextReplacement("mstatus.MIE")
        compose.onNode(hasText("mstatus.MIE") and hasText("M · FIELD")).performClick()
        val detail = compose.onNodeWithTag("register-detail-list")
        compose.onNodeWithText("MIE  [3]").assertIsDisplayed()
        val position = detail.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithContentDescription("关于 ISAtlas").performClick()
        compose.onNodeWithTag("about-sources").performScrollTo().performClick()
        compose.onNodeWithTag("sources-screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回关于").performClick()
        compose.onNodeWithTag("about-screen").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("about-screen").assertDoesNotExist()
        val restored = detail.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        org.junit.Assert.assertEquals("Detail position after returning from About", position, restored, 0.01f)
        compose.onNodeWithText("MIE  [3]").assertIsDisplayed()
    }

    @Test fun aboutRisesFromBelowAndDismissesDownward() {
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("关于 ISAtlas").performClick()
            compose.mainClock.advanceTimeBy(80)
            val entering = compose.onNodeWithTag("about-screen").fetchSemanticsNode().positionInRoot.y
            compose.mainClock.advanceTimeBy(400)
            val settled = compose.onNodeWithTag("about-screen").fetchSemanticsNode().positionInRoot.y
            org.junit.Assert.assertTrue("About should enter upward from below", entering > settled + 1f)
            compose.onNodeWithContentDescription("收起关于").performClick()
            compose.mainClock.advanceTimeBy(80)
            val leaving = compose.onNodeWithTag("about-screen").fetchSemanticsNode().positionInRoot.y
            org.junit.Assert.assertTrue("About should dismiss downward", leaving > settled + 1f)
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("about-screen").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
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
