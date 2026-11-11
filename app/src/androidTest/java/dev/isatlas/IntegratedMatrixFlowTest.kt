package dev.isatlas

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IntegratedMatrixFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun matrixPackageCanBeEnabledDecodedAndDisabled() {
        OfficialPackageTestSession(compose, setOf("ime")).use {
            lateinit var model: AppViewModel
            compose.runOnUiThread {
                model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
                model.xlen(64)
                model.namespace("ime")
                model.extension("zvvi8i64mm")
                model.query("0xee208457")
            }
            assertTrue(model.state.value.instructionResults.hits.any { it.instruction.name == "v8wmmacc.vv" })
            compose.onNodeWithTag("catalog-tab-INST").performClick()
            compose.waitUntil(10_000) {
                model.databaseUpdates.dismiss()
                compose.onNodeWithText("v8wmmacc.vv", substring = false).isDisplayed()
            }
            compose.onNodeWithText("v8wmmacc.vv", substring = false).performClick()
            compose.onNodeWithTag("instruction-detail").assertIsDisplayed()
            assertTrue(model.state.value.extensions.containsAll(listOf("zvvi8i64mm", "zve64x")))
            assertEquals("v8", model.state.value.selectedInstruction?.encoding?.operands(0xee208457)?.get("vd"))
        }
    }
}
