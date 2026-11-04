package de.tetnavigator.core.designsystem

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spezifikation 1.4: jede Touch-Fläche ≥ 64 dp, kritische Aktionen per Lang-Drücken. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComponentsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun buttonsAreGloveSized() {
        rule.setContent {
            TetTheme {
                androidx.compose.foundation.layout.Column {
                    PrimaryButton("Losfahren", {})
                    SecondaryButton("Ok", {})
                    TetIconButton(TetIcons.Center, "Zentrieren", {})
                }
            }
        }
        rule.onNodeWithText("LOSFAHREN").assertHeightIsAtLeast(64.dp).assertWidthIsAtLeast(64.dp)
        rule.onNodeWithText("OK").assertHeightIsAtLeast(64.dp).assertWidthIsAtLeast(64.dp)
        rule.onNodeWithContentDescription("Zentrieren").assertHeightIsAtLeast(64.dp).assertWidthIsAtLeast(64.dp)
    }

    @Test fun criticalActionNeedsLongPress() {
        var fired = 0
        var hints = 0
        rule.setContent {
            TetTheme {
                TetButton("Stopp", { fired++ }, requireLongPress = true, onShortTapHint = { hints++ })
            }
        }
        rule.onNodeWithText("STOPP").performClick()
        rule.waitForIdle()
        assertThat(fired).isEqualTo(0)
        assertThat(hints).isEqualTo(1)
        rule.onNodeWithText("STOPP").performTouchInput { longClick(durationMillis = LONG_PRESS_MS + 300L) }
        rule.waitForIdle()
        assertThat(fired).isEqualTo(1)
    }
}
