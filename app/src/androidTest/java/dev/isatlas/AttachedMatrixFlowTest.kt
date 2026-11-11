package dev.isatlas

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AttachedMatrixFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun attachedMatrixPackageCanBeQueriedAndDecoded() {
        OfficialPackageTestSession(compose, setOf("ame")).use {
            lateinit var model: AppViewModel
            compose.runOnUiThread {
                model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
                model.xlen(64); model.namespace("ame"); model.extension("ztt"); model.query("0x045201ab")
            }
            assertEquals(setOf("ztt"), model.state.value.extensions)
            assertTrue(model.state.value.instructionResults.hits.any { it.instruction.name == "madd.ew" })
            compose.onNodeWithTag("catalog-tab-INST").performClick()
            compose.waitUntil(10_000) {
                model.databaseUpdates.dismiss()
                compose.onNodeWithText("madd.ew", substring = false).isDisplayed()
            }
            compose.onNodeWithText("madd.ew", substring = false).performClick()
            compose.onNodeWithTag("instruction-detail").assertIsDisplayed()
            assertEquals("m3", model.state.value.selectedInstruction?.encoding?.operands(0x045201ab)?.get("md"))
            compose.runOnUiThread { model.close(); model.query("amefflags") }
            compose.onNodeWithTag("catalog-tab-CSR").performClick()
            val csr = hasText("amefflags") and hasText("U · CSR")
            compose.waitUntil(10_000) { compose.onNode(csr).isDisplayed() }
            compose.onNode(csr).performClick()
            compose.onNodeWithTag("register-diagram").assertIsDisplayed()
        }
    }
}
