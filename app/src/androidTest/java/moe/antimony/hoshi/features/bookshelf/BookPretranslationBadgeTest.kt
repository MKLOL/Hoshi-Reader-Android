package moe.antimony.hoshi.features.bookshelf

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.antimony.hoshi.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookPretranslationBadgeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun translatedCoverShowsAccessibleBadgeAndRefreshRemovesIt() {
        val hasTranslations = mutableStateOf(false)
        val description = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.bookshelf_pretranslated)
        compose.setContent {
            MaterialTheme {
                BookCoverCard(
                    coverSource = null,
                    hasPretranslations = hasTranslations.value,
                    modifier = Modifier.width(140.dp),
                )
            }
        }

        compose.onNodeWithContentDescription(description).assertDoesNotExist()
        compose.runOnIdle { hasTranslations.value = true }
        compose.onNodeWithContentDescription(description).assertIsDisplayed()
        compose.runOnIdle { hasTranslations.value = false }
        compose.onNodeWithContentDescription(description).assertDoesNotExist()
    }
}
