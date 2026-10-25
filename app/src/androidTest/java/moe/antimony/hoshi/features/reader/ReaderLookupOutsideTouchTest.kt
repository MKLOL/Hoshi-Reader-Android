package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderLookupOutsideTouchTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun outsideWordAndControlsOnlyDismissUntilTheNextPress() {
        var open by mutableStateOf(true)
        var pending by mutableStateOf(false)
        var words = 0
        var controls = 0
        show({ open || pending }, { open = false; pending = false }, { words++; open = true }, { controls++ })
        compose.onNodeWithTag("word").performTouchInput { click() }
        compose.runOnIdle { assertFalse(open); assertEquals(0, words) }
        compose.onNodeWithTag("word").performTouchInput { click() }
        compose.runOnIdle { assertTrue(open); assertEquals(1, words) }
        compose.onNodeWithTag("control").performTouchInput { click() }
        compose.runOnIdle { assertFalse(open); assertEquals(0, controls) }
        compose.onNodeWithTag("control").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, controls); pending = true }
        compose.onNodeWithTag("control").performTouchInput { click() }
        compose.runOnIdle { assertFalse(pending); assertEquals(1, controls) }
    }

    @Test
    fun rootAndNestedPopupControlsStillReceiveTheirPresses() {
        var open by mutableStateOf(true)
        var popupPresses = 0
        show({ open }, { open = false }, {}, {}, { popupPresses++ })
        compose.onNodeWithTag("popup-action").performTouchInput { click() }
        compose.runOnIdle { assertTrue(open); assertEquals(1, popupPresses) }
    }

    private fun show(active: () -> Boolean, dismiss: () -> Unit, word: () -> Unit, control: () -> Unit, popup: () -> Unit = {}) {
        compose.setContent {
            val target = remember { ReaderLookupTouchTarget() }
            val density = LocalDensity.current.density
            val currentActive by rememberUpdatedState(active)
            val currentDismiss by rememberUpdatedState(dismiss)
            Box(Modifier.fillMaxWidth().height(300.dp)
                .onGloballyPositioned { target.rootCoordinates = it }
                .dismissReaderLookupOnOutsideTouch({ currentActive() }, { target.containsRootPoint(it, density) }, { currentDismiss() })) {
                Column(Modifier.fillMaxSize()) {
                    Text("Word", Modifier.fillMaxWidth().height(60.dp).testTag("word").clickable(onClick = word))
                    Text("Next page", Modifier.fillMaxWidth().height(60.dp).testTag("control").clickable(onClick = control))
                }
                Box(Modifier.align(Alignment.BottomEnd).size(100.dp).onGloballyPositioned {
                    target.popupCoordinates = it
                    target.popupRects = listOf(ReaderSelectionRect(0.0, 0.0, it.size.width / density.toDouble(), it.size.height / density.toDouble()))
                }) {
                    Text("Popup action", Modifier.fillMaxSize().testTag("popup-action").clickable(onClick = popup))
                }
            }
        }
    }
}
