package moe.antimony.hoshi.features.news

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsSourceCatalogTest {
    @Test
    fun defaultSourcesIncludeCurrentEasyNewsAndLeaveTheArchiveOptional() {
        val settings = NewsSettings()
        assertTrue(settings.isEnabled(NewsSourceCatalog.nhkEasier))
        assertTrue(settings.isEnabled(NewsSourceCatalog.slowCommunication))
        assertFalse(settings.isEnabled(NewsSourceCatalog.watanoc))
    }

    @Test
    fun explicitSourceChoicesSurviveTheDefaultChanges() {
        val settings = NewsSettings(
            enabledSourceIds = setOf(NewsSourceCatalog.WATANOC_ID),
            disabledSourceIds = setOf(NewsSourceCatalog.NHK_EASIER_ID, NewsSourceCatalog.SLOW_COMMUNICATION_ID),
        )
        assertTrue(settings.isEnabled(NewsSourceCatalog.watanoc))
        assertFalse(settings.isEnabled(NewsSourceCatalog.nhkEasier))
        assertFalse(settings.isEnabled(NewsSourceCatalog.slowCommunication))
    }
}
