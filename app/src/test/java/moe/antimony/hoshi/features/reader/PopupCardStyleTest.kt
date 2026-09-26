package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.dictionary.LookupPopupLayout
import moe.antimony.hoshi.ui.theme.PopupReadability
import moe.antimony.hoshi.ui.theme.popupReadability
import org.junit.Assert.*
import org.junit.Test

class PopupCardStyleTest {
    private val fold = popupReadability(1848, 2448, 2.625, 403.0, 403.0, "samsung", "SM-F966B")
    private val boox = popupReadability(1860, 2480, 2.25, 300.0, 300.0, "ONYX", "NoteAir6C")

    @Test fun unchangedDefaultsKeepTheApprovedFoldAndBooxDimensions() {
        assertEquals(ResolvedPopupCardStyle(320.0, 250.0, 1.0, 1.0), ReaderSettings().dictionaryCardStyle(fold))
        assertEquals(ResolvedPopupCardStyle(448.0, 350.0, 1.6, 1.0), ReaderSettings().dictionaryCardStyle(boox))
        val phone = PopupCardStyle().resolveTranslation(fold, 704.0, 932.5714)
        assertEquals(520.0, phone.width, 0.0)
        assertEquals(1.0, phone.fontScale, 0.0)
        val tablet = PopupCardStyle().resolveTranslation(boox, 826.6667, 1102.2222)
        assertEquals(826.6667 * 0.86, tablet.width, 0.0)
        assertEquals(1102.2222 * 0.78, tablet.height, 0.0)
        assertEquals(1.6, tablet.fontScale, 0.0)
    }

    @Test fun oldCustomSettingsKeepTheirVisibleSizeUntilExplicitlyChanged() {
        val settings = ReaderSettings(popupWidth = 430, popupHeight = 310, popupScale = 1.25)
        assertEquals(ResolvedPopupCardStyle(430.0, 310.0, 1.25, 1.0), settings.dictionaryCardStyle(fold))
        assertEquals(ResolvedPopupCardStyle(602.0, 434.0, 2.0, 1.0), settings.dictionaryCardStyle(boox))
    }

    @Test fun overridesAreAbsoluteAndNeverGetAnotherTabletMultiplier() {
        val overrides = PopupCardStyle(width = 500.0, height = 400.0, scale = 1.75, fontScale = 1.2)
        val settings = ReaderSettings(dictionaryCard = overrides)
        assertEquals(settings.dictionaryCardStyle(fold), settings.dictionaryCardStyle(boox))
        assertEquals(ResolvedPopupCardStyle(500.0, 400.0, 1.75, 1.2), settings.dictionaryCardStyle(boox))
        assertEquals(PopupCardStyle(), settings.translationCard)
    }

    @Test fun editingOnlyTextKeepsGeometryResponsiveOnRotationAndSplitScreen() {
        val overrides = PopupCardStyle(fontScale = 1.9)
        val portrait = overrides.resolveTranslation(boox, 827.0, 1102.0)
        val landscape = overrides.resolveTranslation(boox, 1102.0, 827.0)
        val split = overrides.resolveTranslation(PopupReadability(), 400.0, 1102.0)
        assertEquals(827 * 0.86, portrait.width, 0.0)
        assertEquals(1102 * 0.86, landscape.width, 0.0)
        assertEquals(1.9, split.fontScale, 0.0)
        assertEquals(520.0, split.width, 0.0)
    }

    @Test fun persistedOverridesNormalizeAndTolerateFutureOrCorruptStorage() {
        val style = PopupCardStyle(500.0, null, 1.75, 1.3)
        assertEquals(style, PopupCardStyle.decode(style.encode()))
        assertEquals(PopupCardStyle(), PopupCardStyle.decode("invalid"))
        assertEquals(PopupCardStyle(width = 100.0, scale = 3.0), PopupCardStyle.decode("""{"width":-1,"scale":999,"future":true}"""))
        assertEquals(PopupCardStyle(), PopupCardStyle(width = Double.NaN).normalized())
    }

    @Test fun resettingDictionaryRestoresAppearanceAndBehaviorWithoutChangingTranslationOrManga() {
        val translation = PopupCardStyle(fontScale = 2.0)
        val settings = ReaderSettings(popupWidth = 600, popupHeight = 400, popupScale = 1.2,
            dictionaryCard = PopupCardStyle(width = 520.0), translationCard = translation,
            popupFullWidth = true, popupActionBar = true, popupSwipeToDismiss = false,
            popupReducedMotionScrolling = true, mangaEnlargeSmallText = false)
        val reset = settings.resetDictionaryCard()
        assertEquals(ReaderSettings().dictionaryCardStyle(boox), reset.dictionaryCardStyle(boox))
        assertFalse(reset.popupFullWidth)
        assertFalse(reset.popupActionBar)
        assertTrue(reset.popupSwipeToDismiss)
        assertFalse(reset.popupReducedMotionScrolling)
        assertEquals(translation, reset.translationCard)
        assertFalse(reset.mangaEnlargeSmallText)
    }

    @Test fun largeCustomCardsStayInsideASmallWindow() {
        for (vertical in listOf(true, false)) {
            for (fullWidth in listOf(true, false)) {
                val frame = LookupPopupLayout(ReaderSelectionRect(100.0, 250.0, 20.0, 20.0),
                    360.0, 600.0, 1600.0, 2000.0, vertical, fullWidth, topInset = 24.0, bottomInset = 24.0).calculate()
                assertTrue(frame.width <= 348.0)
                assertTrue(frame.height <= 540.0)
                assertTrue(frame.centerY - frame.height / 2 >= 24.0)
                assertTrue(frame.centerY + frame.height / 2 <= 576.0)
            }
        }
    }
}
