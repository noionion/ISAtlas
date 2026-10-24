package dev.isatlas

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class InstructionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun closeFilters() {
        val panel = compose.onNodeWithTag("filter-panel").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(panel.left / 2, centerY)) }
        compose.waitForIdle()
    }
    @Before fun ready() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("catalog-tab-INST").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("重置筛选").performScrollTo().performClick()
        closeFilters()
        compose.onNodeWithTag("catalog-tab-INST").performClick()
        compose.waitForIdle()
    }
    @Test fun reverseLookupAndCompressedLayout() {
        compose.onNodeWithTag("search-input").performTextReplacement("0x00b50533")
        compose.onNodeWithTag("inst-riscv/manual/inst/add:0").assertIsDisplayed().performClick()
        compose.onNodeWithTag("instruction-detail").assertIsDisplayed()
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("catalog-tab-INST").assertIsSelected()
        compose.onNodeWithTag("search-input").performTextReplacement("c.nop")
        compose.onNodeWithTag("inst-riscv/manual/inst/c.nop:0").performClick()
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回搜索").performClick()
    }
    @Test fun swipePagesAndSharePermissionFilter() {
        compose.onNodeWithTag("search-input").performTextReplacement("mret")
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("filter-permission-U").performClick()
        closeFilters()
        compose.onNodeWithText("当前数据范围内没有匹配项").assertIsDisplayed()
        compose.onNodeWithTag("catalog-pager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("catalog-tab-CSR").assertIsSelected()
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("filter-permission-U").assertIsSelected()
        compose.onNodeWithTag("filter-permission-M").performClick()
        closeFilters()
        compose.onNodeWithTag("catalog-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("catalog-tab-INST").assertIsSelected()
        compose.onNodeWithTag("inst-riscv/manual/inst/mret:0").assertIsDisplayed()
    }
    @Test fun landscapeAndInstructionExtensions() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        compose.onNodeWithTag("catalog-tab-INST").performClick()
        compose.onNodeWithTag("search-input").performTextReplacement("vadd")
        compose.onNodeWithTag("instruction-results").assertIsDisplayed()
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("extension-search").performTextReplacement("zba")
        compose.onNodeWithTag("extension-zba").assertIsDisplayed().performClick()
        closeFilters()
        compose.onNodeWithText("当前数据范围内没有匹配项").assertIsDisplayed()
    }

    @Test fun assemblyNamesAndCopy() {
        compose.onNodeWithTag("search-input").performTextReplacement("0x00b50533")
        compose.onNodeWithTag("inst-riscv/manual/inst/add:0").performClick()
        compose.onNodeWithTag("register-names-abi").performScrollTo().performClick()
        compose.onNodeWithTag("instruction-assembly").assertTextEquals("add a0, a0, a1")
        compose.onNodeWithTag("register-names-number").performClick()
        compose.onNodeWithTag("instruction-assembly").assertTextEquals("add x10, x10, x11")
        compose.onNodeWithText("复制汇编").performClick()
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            org.junit.Assert.assertEquals("add x10, x10, x11", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun instructionCsrRoundTripPreservesWordAndDetail() {
        compose.onNodeWithTag("search-input").performTextReplacement("0x300110f3")
        compose.onNodeWithTag("inst-riscv/manual/inst/csrrw:0").performClick()
        compose.onNodeWithTag("register-names-abi").performScrollTo().performClick()
        compose.onNodeWithTag("instruction-assembly").assertTextEquals("csrrw ra, mstatus, sp")
        compose.onNodeWithText("mstatus · manual ↗").performScrollTo().performClick()
        compose.onNodeWithTag("app-title").assertTextEquals("mstatus")
        compose.onNodeWithContentDescription("返回指令").performClick()
        compose.onNodeWithTag("instruction-assembly").assertIsDisplayed().assertTextEquals("csrrw ra, mstatus, sp")
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("catalog-tab-INST").assertIsSelected()
        compose.onNodeWithTag("search-input").assertTextContains("0x300110f3")
    }

    @Test fun diagnosisCanRecoverFiltersWithoutChangingInput() {
        compose.onNodeWithTag("search-input").performTextReplacement("mret")
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("filter-permission-U").performClick()
        closeFilters()
        compose.onNodeWithText("已收录的匹配项被权限筛选排除。").assertIsDisplayed()
        compose.onNodeWithTag("relax-instruction-filters").performClick()
        compose.onNodeWithTag("inst-riscv/manual/inst/mret:0").assertIsDisplayed()
        compose.onNodeWithTag("search-input").assertTextContains("mret")
        compose.onNodeWithTag("search-input").performTextReplacement("0b102")
        compose.onNodeWithText("二进制编码只能包含 0 和 1。").assertIsDisplayed()
        compose.onNodeWithTag("relax-instruction-filters").assertDoesNotExist()
    }

    @Test fun compressedMopRequiresShadowStackExtension() {
        compose.onNodeWithTag("search-input").performTextReplacement("6081")
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("extension-search").performTextReplacement("zcmop")
        compose.onNodeWithTag("extension-zcmop").performClick()
        closeFilters()
        compose.onNodeWithTag("inst-riscv/manual/inst/c.mop.1:0").assertIsDisplayed()
        compose.onNodeWithTag("inst-riscv/manual/inst/c.sspush:0").assertDoesNotExist()
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("extension-search").performTextReplacement("zicfiss")
        compose.onNodeWithTag("extension-zicfiss").performClick()
        closeFilters()
        compose.onNodeWithTag("inst-riscv/manual/inst/c.sspush:0").assertIsDisplayed()
        compose.onNodeWithTag("inst-riscv/manual/inst/c.mop.1:0").assertIsDisplayed()
    }
}
