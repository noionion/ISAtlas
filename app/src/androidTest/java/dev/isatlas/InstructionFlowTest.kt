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

    @Test fun nameLookupShowsConcreteExampleAndCopiesIt() {
        compose.onNodeWithTag("search-input").performTextReplacement("add")
        compose.onNodeWithTag("inst-riscv/manual/inst/add:0").performClick()
        compose.onNodeWithTag("instruction-example-copy").performScrollTo().performClick()
        compose.onNodeWithTag("instruction-example-code").assertTextContains("add ", substring = true)
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            org.junit.Assert.assertTrue(copied.startsWith("add "))
            org.junit.Assert.assertTrue(copied.contains("= 8"))
        }
    }

    @Test fun partialLookupExampleDoesNotReplaceUnknownOperands() {
        compose.onNodeWithTag("search-input").performTextReplacement("0x00?50533")
        compose.onNodeWithTag("inst-riscv/manual/inst/add:0").performClick()
        compose.onNodeWithTag("instruction-example-code").performScrollTo().assertTextContains("add ", substring = true)
        compose.onNodeWithText("独立示例，与上方查询编码无关。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("instruction-partial-operands").performScrollTo().assertTextContains("rs2 = 未确定", substring = true)
        compose.onNodeWithTag("instruction-assembly").assertDoesNotExist()
    }

    @Test fun examplesRemainReadableInLandscapeAndPseudoDetails() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        compose.onNodeWithTag("search-input").performTextReplacement("c.addi4spn")
        compose.onNodeWithTag("inst-riscv/manual/inst/c.addi4spn:0").performClick()
        compose.onNodeWithTag("instruction-example-code").performScrollTo().assertTextContains("c.addi4spn ", substring = true)
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("search-input").performTextReplacement("call")
        compose.onNodeWithTag("pseudo-riscv/manual/pseudo/call").performClick()
        compose.onNodeWithTag("instruction-example-code").performScrollTo().assertTextEquals("call target")
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

    @Test fun vectorFloatingDependenciesSelectFWithoutForcingFullV() {
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("extension-search").performScrollTo().performTextReplacement("zvfh")
        compose.onNodeWithTag("extension-zvfh").performClick().assertIsSelected()
        compose.onNodeWithTag("extension-search").performTextReplacement("zve32f")
        compose.onNodeWithTag("extension-zve32f").assertIsSelected()
        compose.onNodeWithTag("extension-search").performTextReplacement("f")
        compose.onNodeWithTag("extension-f").assertIsSelected().performClick().assertIsSelected()
        compose.onNodeWithTag("extension-search").performTextReplacement("v")
        compose.onNodeWithTag("extension-v").assertIsNotSelected()
        compose.onNodeWithTag("extension-search").performTextReplacement("zvfh")
        compose.onNodeWithTag("extension-zvfh").performClick().assertIsNotSelected()
        compose.onNodeWithTag("extension-search").performTextReplacement("f")
        compose.onNodeWithTag("extension-f").assertIsNotSelected()
        closeFilters()
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

    @Test fun completeNameFirstCategoriesAndEmptyResults() {
        compose.onNodeWithTag("search-input").performTextReplacement("ret")
        compose.onNodeWithTag("instruction-search-options").assertDoesNotExist()
        compose.onNodeWithTag("instruction-exact-name").assertDoesNotExist()
        compose.onNodeWithTag("search-input").assertTextContains("ret")
        compose.onNodeWithTag("inst-riscv/manual/inst/ret:0").assertIsDisplayed()

        compose.onNodeWithTag("instruction-category-DESCRIPTION").assertDoesNotExist()
        compose.onNodeWithTag("instruction-category-NAME").performClick()
        compose.onNodeWithTag("inst-riscv/manual/inst/ret:0").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.recreate() }
        compose.waitForIdle()
        compose.onNodeWithTag("instruction-category-NAME").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已收起")).performClick()
        compose.onNodeWithTag("inst-riscv/manual/inst/ret:0").performClick()
        compose.onNodeWithTag("instruction-base-riscv/manual/inst/jalr:0").performScrollTo().performClick()
        compose.onNodeWithTag("app-title").assertTextEquals("jalr")
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("search-input").assertTextContains("ret")
        compose.onNodeWithTag("search-input").performTextReplacement("no.such.instruction")
        compose.onNodeWithText("当前数据范围内没有匹配项").assertIsDisplayed()
        compose.onNodeWithTag("instruction-category-NAME").assertDoesNotExist()
        compose.onNodeWithTag("instruction-category-DESCRIPTION").assertDoesNotExist()
    }

    @Test fun sameNameVariantsExpandAndSurviveDetailReturn() {
        compose.onNodeWithTag("search-input").performTextReplacement("fadd.s")
        compose.onNodeWithTag("inst-group-NAME:fadd.s").assertIsDisplayed()
        compose.onNodeWithTag("inst-riscv/manual/inst/fadd.s:0").assertDoesNotExist()
        compose.onNodeWithTag("inst-variants-NAME:fadd.s").performClick()
        compose.onNodeWithTag("instruction-results").performScrollToNode(hasTestTag("inst-riscv/manual/inst/fadd.s:1"))
        compose.onNodeWithTag("inst-riscv/manual/inst/fadd.s:1").performClick()
        compose.onNodeWithTag("app-title").assertTextEquals("fadd.s")
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("inst-riscv/manual/inst/fadd.s:1").assertIsDisplayed()
        compose.onNodeWithTag("instruction-results").performScrollToNode(hasTestTag("inst-variants-NAME:fadd.s"))
        compose.onNodeWithTag("inst-variants-NAME:fadd.s").performClick()
        compose.onNodeWithTag("inst-riscv/manual/inst/fadd.s:1").assertDoesNotExist()
    }

    @Test fun reverseLookupExplainsAndLinksAliasWithoutDroppingWord() {
        compose.onNodeWithTag("search-input").performTextReplacement("0x00008067")
        compose.onNodeWithTag("instruction-candidate-explanation").performClick()
        compose.onNodeWithText("ret 是 jalr 的别名；两者表示同一编码的不同写法。").assertIsDisplayed()
        compose.onNodeWithTag("instruction-results").performScrollToNode(hasTestTag("inst-riscv/manual/inst/ret:0"))
        compose.onNodeWithTag("inst-riscv/manual/inst/ret:0").performClick()
        compose.onNodeWithTag("instruction-related-toggle").performClick()
        compose.onNodeWithTag("instruction-related-riscv/manual/inst/jalr:0").performScrollTo().performClick()
        compose.onNodeWithTag("app-title").assertTextEquals("jalr")
        compose.onNodeWithTag("register-names-number").performScrollTo().performClick()
        compose.onNodeWithTag("instruction-assembly").assertTextEquals("jalr x0, 0(x1)")
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("search-input").assertTextContains("0x00008067")
    }

    @Test fun descriptionCategoryAndLandscapeGroups() {
        compose.onNodeWithTag("search-input").performTextReplacement("and")
        compose.onNodeWithTag("instruction-category-NAME").performClick()
        compose.onNodeWithTag("instruction-category-DESCRIPTION").assertIsDisplayed().performClick()
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        compose.onNodeWithTag("instruction-category-NAME").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已收起"))
        compose.onNodeWithTag("instruction-category-DESCRIPTION").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已收起"))
        compose.onNodeWithTag("search-input").performTextReplacement("fadd.s")
        compose.onNodeWithTag("instruction-search-options").assertDoesNotExist()
        compose.onNodeWithTag("instruction-category-NAME").performClick()
        compose.onNodeWithTag("inst-variants-NAME:fadd.s").performClick()
        compose.onNodeWithTag("instruction-results").performScrollToNode(hasTestTag("inst-riscv/manual/inst/fadd.s:0"))
        compose.onNodeWithTag("inst-riscv/manual/inst/fadd.s:0").assertIsDisplayed().performClick()
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
    }

    @Test fun sameMnemonicCandidateChangesEncodingInPlace() {
        compose.onNodeWithTag("search-input").performTextReplacement("0x00b50553")
        compose.onNodeWithTag("inst-variants-ENCODING:fadd.s").performClick()
        compose.onNodeWithTag("inst-riscv/manual/inst/fadd.s:0").performClick()
        compose.onNodeWithTag("instruction-related-toggle").performClick()
        compose.onNodeWithTag("instruction-related-riscv/manual/inst/fadd.s:0").assertIsNotEnabled()
        compose.onNodeWithTag("instruction-related-riscv/manual/inst/fadd.s:1").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("instruction-related-riscv/manual/inst/fadd.s:0").performClick().assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("search-input").assertTextContains("0x00b50553")
    }

    @Test fun pseudoCallCopyAndBaseRoundTrip() {
        compose.onNodeWithTag("search-input").performTextReplacement("call")
        compose.onNodeWithTag("pseudo-riscv/manual/pseudo/call").assertIsDisplayed().performClick()
        compose.onNodeWithTag("pseudo-instruction-detail").assertIsDisplayed()
        compose.onNodeWithTag("register-diagram").assertDoesNotExist()
        compose.onNodeWithTag("pseudo-sequence-0").assertTextEquals("auipc ra, imm20\njalr ra, imm12(ra)")
        compose.onNodeWithTag("pseudo-copy-0").performScrollTo().performClick()
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            org.junit.Assert.assertEquals("auipc ra, imm20\njalr ra, imm12(ra)", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithTag("pseudo-base-riscv/manual/inst/auipc:0").performScrollTo().performClick()
        compose.onNodeWithTag("app-title").assertTextEquals("auipc")
        compose.runOnUiThread { compose.activity.recreate() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回伪指令").performClick()
        compose.onNodeWithTag("app-title").assertTextEquals("call")
        compose.onNodeWithTag("pseudo-base-riscv/manual/inst/auipc:0").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("search-input").assertTextContains("call")
        compose.onNodeWithTag("pseudo-riscv/manual/pseudo/call").assertIsDisplayed()
    }

    @Test fun pseudoVectorConditionsAndExtensionRecovery() {
        compose.onNodeWithTag("search-input").performTextReplacement("vmsge.vx")
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("extension-search").performTextReplacement("zba")
        compose.onNodeWithTag("extension-zba").performClick()
        closeFilters()
        compose.onNodeWithText("已收录的匹配项被扩展筛选排除。").assertIsDisplayed()
        compose.onNodeWithTag("relax-instruction-filters").performClick()
        compose.onNodeWithTag("pseudo-riscv/manual/pseudo/vmsge.vx").performClick()
        compose.onNodeWithTag("pseudo-sequence-2").performScrollTo().assertTextEquals("vmslt.vx vt, va, x\nvmandn.mm vd, vd, vt")
        compose.onNodeWithTag("pseudo-sequence-3").performScrollTo().assertTextEquals("vmslt.vx vt, va, x\nvmandn.mm vt, v0, vt\nvmandn.mm vd, vd, v0\nvmor.mm vd, vt, vd")
        compose.onNodeWithTag("register-diagram").assertDoesNotExist()
    }

    @Test fun csrAliasAssemblyAndNavigation() {
        compose.onNodeWithTag("search-input").performTextReplacement("0x30002573")
        compose.onNodeWithTag("instruction-results").performScrollToNode(hasTestTag("inst-riscv/manual/inst/csrr:0"))
        compose.onNodeWithTag("inst-riscv/manual/inst/csrr:0").performClick()
        compose.onNodeWithTag("register-names-abi").performScrollTo().performClick()
        compose.onNodeWithTag("instruction-assembly").assertTextEquals("csrr a0, mstatus")
        compose.onNodeWithText("mstatus · manual ↗").performScrollTo().performClick()
        compose.onNodeWithTag("app-title").assertTextEquals("mstatus")
        compose.onNodeWithContentDescription("返回指令").performClick()
        compose.onNodeWithTag("instruction-assembly").assertTextEquals("csrr a0, mstatus")
    }

    @Test fun floatingAliasRendersOnlyItsTwoAssemblyOperands() {
        compose.onNodeWithTag("search-input").performTextReplacement("0x20b59553")
        compose.onNodeWithTag("inst-variants-ENCODING:fneg.s").performClick()
        compose.onNodeWithTag("instruction-results").performScrollToNode(hasTestTag("inst-riscv/manual/inst/fneg.s:0"))
        compose.onNodeWithTag("inst-riscv/manual/inst/fneg.s:0").performClick()
        compose.onNodeWithTag("register-names-number").performScrollTo().performClick()
        compose.onNodeWithTag("instruction-assembly").assertTextEquals("fneg.s f10, f11")
    }

    @Test fun mentionOnlyAndSymbolicImmediateReferences() {
        compose.onNodeWithTag("search-input").performTextReplacement("li")
        compose.onNodeWithTag("pseudo-riscv/manual/pseudo/li").performClick()
        compose.onNodeWithText("仅名称参考 · 手册未给出完整展开").assertIsDisplayed()
        compose.onNodeWithTag("register-diagram").assertDoesNotExist()
        compose.onNodeWithTag("pseudo-copy-0").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("search-input").performTextReplacement("vmsltu.vi")
        compose.onNodeWithTag("pseudo-riscv/manual/pseudo/vmsltu.vi").performClick()
        compose.onNodeWithTag("pseudo-sequence-0").assertTextEquals("vmsleu.vi vd, va, i-1, vm")
        compose.onNodeWithTag("pseudo-base-riscv/manual/inst/vmsleu.vi:0").performScrollTo().performClick()
        compose.onNodeWithTag("app-title").assertTextEquals("vmsleu.vi")
    }

    @Test fun partialHexKeepsUnknownOperandsAndSurvivesRecreation() {
        compose.onNodeWithTag("search-input").performTextReplacement("00?50533")
        compose.onNodeWithTag("instruction-partial-notice").assertTextContains("十六进制", substring = true).assertTextContains("28/32", substring = true)
        compose.onNodeWithTag("inst-riscv/manual/inst/add:0").performClick()
        compose.onNodeWithTag("instruction-partial-query").assertTextEquals("部分编码 · 0x00?50533")
        compose.onNodeWithTag("instruction-partial-operands").performScrollTo()
            .assertTextContains("rd = x10", substring = true).assertTextContains("rs1 = x10", substring = true)
            .assertTextContains("rs2 = 未确定", substring = true)
        compose.onNodeWithTag("instruction-assembly").assertDoesNotExist()
        compose.onNodeWithText("复制汇编").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.recreate() }
        compose.waitForIdle()
        compose.onNodeWithTag("instruction-partial-operands").assertTextContains("rs2 = 未确定", substring = true)
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("search-input").assertTextContains("00?50533")
    }

    @Test fun partialCandidatesKeepPatternWhenSwitchingFromAliasToBase() {
        compose.onNodeWithTag("search-input").performTextReplacement("0x0000?067")
        compose.onNodeWithTag("instruction-results").performScrollToNode(hasTestTag("inst-riscv/manual/inst/ret:0"))
        compose.onNodeWithTag("inst-riscv/manual/inst/ret:0").performClick()
        compose.onNodeWithTag("instruction-related-toggle").performClick()
        compose.onNodeWithTag("instruction-related-riscv/manual/inst/jalr:0").performScrollTo().performClick()
        compose.onNodeWithTag("app-title").assertTextEquals("jalr")
        compose.onNodeWithTag("instruction-partial-query").assertTextEquals("部分编码 · 0x0000?067")
        compose.onNodeWithTag("instruction-partial-operands").performScrollTo().assertTextContains("rs1 = 未确定", substring = true)
        compose.onNodeWithTag("instruction-assembly").assertDoesNotExist()
    }

    @Test fun partialBinaryLandscapeAndFilterRecovery() {
        val binary = "00000000????01010000010100110011"
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        compose.onNodeWithTag("search-input").performTextReplacement(binary)
        compose.onNodeWithTag("instruction-partial-notice").assertTextContains("二进制", substring = true)
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithTag("extension-search").performTextReplacement("zba")
        compose.onNodeWithTag("extension-zba").performClick()
        closeFilters()
        compose.onNodeWithTag("instruction-results").performScrollToNode(hasTestTag("relax-instruction-filters"))
        compose.onNodeWithTag("relax-instruction-filters").performClick()
        compose.onNodeWithTag("instruction-results").performScrollToNode(hasTestTag("inst-riscv/manual/inst/add:0"))
        compose.onNodeWithTag("inst-riscv/manual/inst/add:0").performClick()
        compose.onNodeWithTag("instruction-partial-query").assertTextEquals("部分编码 · 0b$binary")
    }

    @Test fun partialPatternsExplainBroadInputsAndLengthConflicts() {
        compose.onNodeWithTag("search-input").performTextReplacement("0x????????")
        compose.onNodeWithText("已知位太少，请至少填写 7 个已知位，缩小部分编码查询范围。").assertIsDisplayed()
        compose.onNodeWithTag("instruction-category-ENCODING").assertDoesNotExist()
        compose.onNodeWithTag("search-input").performTextReplacement("0x000000?1")
        compose.onNodeWithText("部分编码的已知低位与 32 位指令长度不符；请检查输入长度。").assertIsDisplayed()
        compose.onNodeWithTag("search-input").performTextReplacement("0x00?0")
        compose.onNodeWithTag("instruction-results").performScrollToNode(hasTestTag("inst-riscv/manual/inst/c.addi4spn:0"))
        compose.onNodeWithTag("inst-riscv/manual/inst/c.addi4spn:0").assertIsDisplayed()
    }

    @Test fun pseudoLandscapeAndRecreation() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        compose.onNodeWithTag("search-input").performTextReplacement("tail")
        compose.onNodeWithTag("pseudo-riscv/manual/pseudo/tail").performClick()
        compose.onNodeWithTag("pseudo-sequence-0").performScrollTo().assertTextEquals("auipc x7, imm20\njalr x0, x7")
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitForIdle()
        compose.onNodeWithTag("app-title").assertTextEquals("tail")
        compose.onNodeWithTag("pseudo-instruction-detail").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.onNodeWithTag("catalog-tab-INST").assertIsSelected()
        compose.onNodeWithTag("search-input").assertTextContains("tail")
    }
}
