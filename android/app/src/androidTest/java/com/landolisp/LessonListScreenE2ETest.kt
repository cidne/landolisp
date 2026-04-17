package com.landolisp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/**
 * Launches the real [MainActivity], lets the lesson list hydrate from the
 * asset-packaged curriculum, asserts at least one lesson card is drawn,
 * taps it, and asserts the lesson screen shows the corresponding title.
 *
 * Assumes the sync-curriculum build task has populated
 * `app/src/main/assets/curriculum/`. If the assets are empty on-device, this
 * test surfaces the issue loudly.
 */
class LessonListScreenE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun lessonListRenders_andFirstLessonOpens() {
        // Title of the first bundled lesson (001-atoms). The numeric prefix is
        // applied by the card renderer ("1. Atoms and S-expressions"). Match
        // by the human title substring so re-numbering upstream doesn't break
        // the test.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodesWithText("Atoms and S-expressions", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Assert rendered.
        composeRule.onAllNodesWithText("Atoms and S-expressions", substring = true)
            .onFirst()
            .assertIsDisplayed()

        // Tap it.
        composeRule.onAllNodesWithText("Atoms and S-expressions", substring = true)
            .onFirst()
            .performClick()

        // Lesson screen shows the title somewhere (app bar or body).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodesWithText("Atoms", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithText("Atoms", substring = true).assertIsDisplayed()
    }
}
