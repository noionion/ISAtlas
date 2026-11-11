package dev.isatlas

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PackedDraftFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun packedDraftCanBeEnabledAndDecoded() {
        OfficialPackageTestSession(compose, setOf("p")).use {
            lateinit var model: AppViewModel
            compose.runOnUiThread {
                model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
                model.xlen(64)
                model.namespace("p")
                model.extension("p")
                model.query("0x84c5853b")
            }
            assertTrue(model.state.value.instructionResults.hits.any { it.instruction.name == "padd.b" })
            compose.onNodeWithTag("catalog-tab-INST").performClick()
            compose.waitUntil(10_000) {
                model.databaseUpdates.dismiss()
                compose.onNodeWithText("padd.b", substring = false).isDisplayed()
            }
            compose.onNodeWithText("padd.b", substring = false).performClick()
            compose.onNodeWithTag("instruction-detail").assertIsDisplayed()
            assertTrue(model.state.value.extensions.containsAll(listOf("p", "zba", "zbb", "zmmul")))
            assertEquals("padd.b", model.state.value.selectedInstruction?.instruction?.name)
            assertEquals("x12", model.state.value.selectedInstruction?.encoding?.operands(0x84c5853b)?.get("rs2"))
            compose.runOnUiThread { model.close(); model.query("pneg.b") }
            assertTrue(model.state.value.instructionResults.pseudoHits.any { it.instruction.name == "pneg.b" })
            compose.onNodeWithTag("catalog-tab-INST").performClick()
            compose.onNodeWithTag("instruction-results").performScrollToNode(hasTestTag("pseudo-p-pseudo-pneg.b"))
            compose.onNodeWithTag("pseudo-p-pseudo-pneg.b").performClick()
            compose.onNodeWithTag("pseudo-instruction-detail").assertIsDisplayed()
        }
    }
}
