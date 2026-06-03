package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryRowInteractionTest {
    @Test
    fun longPressDeleteEntryOnlyComesFromContentArea() {
        assertTrue(DictionaryRowInteraction.canRevealDeleteOnLongPress(DictionaryRowInteraction.Area.Content))
        assertFalse(DictionaryRowInteraction.canRevealDeleteOnLongPress(DictionaryRowInteraction.Area.DragHandle))
        assertFalse(DictionaryRowInteraction.canRevealDeleteOnLongPress(DictionaryRowInteraction.Area.EnableSwitch))
    }
}
