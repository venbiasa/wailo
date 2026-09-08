package com.venbiasa.wailo.shared.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.venbiasa.wailo.shared.ResponseHeader
import com.venbiasa.wailo.shared.theme.WailoTheme
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred

// Save keeps the editor open (ADR-0092), so the footer verdict and the Save button are the only things
// saying whether the form matches the store — and a wrong answer there is silent: the rule still saves, the
// caption just lies about it. That is how the first cut shipped broken, comparing the live
// SnapshotStateList of headers against a stored copy (identity equality, never true), which pinned every
// rule in "Unsaved changes" behind a Save button that appeared to do nothing.
class ResponseRuleSaveStateTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aStoredRuleOpensSavedAndGoesUnsavedOnEdit() = runComposeUiTest {
        setContent {
            WailoTheme(darkTheme = false) {
                ResponseRuleEditor(
                    title = "Mapping Rule",
                    initial = ResponseDraft(
                        urlPattern = "https://api.example.com/v1/*",
                        method = "GET",
                        statusCode = 200,
                        delayMillis = 120,
                        headers = listOf(ResponseHeader("Content-Type", "application/json")),
                    ),
                    initialName = "Login",
                    persisted = true,
                    enabled = true,
                    enabledToggleable = true,
                    onToggleEnabled = {},
                    bodySeed = null,
                    onLoadBody = { """{"ok":true}""".encodeToByteArray() },
                    onPickFile = { null },
                    onSave = { _, _, _ -> },
                    onBack = {},
                    onClose = {},
                )
            }
        }

        // The baseline is only recorded once the body lands, so wait for the verdict rather than for idle.
        waitUntilExactlyOneExists(hasText("Saved"))
        onNodeWithText("Save").assertIsNotEnabled()

        onNodeWithText("120").performTextInput("5")

        waitUntilExactlyOneExists(hasText("Unsaved changes"))
        onNodeWithText("Save").assertIsEnabled()

        // The half the bug made look inert: committing has to retire the caption, or Save reads as a dead
        // button that writes nothing.
        onNodeWithText("Save").performClick()

        waitUntilExactlyOneExists(hasText("Saved"))
        onNodeWithText("Save").assertIsNotEnabled()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aDraftNothingHasWrittenOpensReadyToCommit() = runComposeUiTest {
        setContent {
            WailoTheme(darkTheme = false) {
                ResponseRuleEditor(
                    title = "Mapping Rule",
                    initial = ResponseDraft(
                        urlPattern = "https://api.example.com/v1/orders",
                        method = "GET",
                        statusCode = 200,
                        headers = listOf(ResponseHeader("Content-Type", "application/json")),
                    ),
                    initialName = "Untitled",
                    persisted = false,
                    enabled = true,
                    enabledToggleable = true,
                    onToggleEnabled = {},
                    // A row-seeded draft: everything is pre-filled and valid, and none of it is written yet,
                    // so Save has to be live before the user touches anything.
                    bodySeed = """{"orders":[]}""".encodeToByteArray(),
                    onLoadBody = { ByteArray(0) },
                    onPickFile = { null },
                    onSave = { _, _, _ -> },
                    onBack = {},
                    onClose = {},
                )
            }
        }
        waitForIdle()

        onNodeWithText("Save").assertIsEnabled()
        onNodeWithText("Saved").assertDoesNotExist()
        onNodeWithText("Unsaved changes").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anEditWhileTheBodyLoadsIsNotAdoptedAsTheBaseline() = runComposeUiTest {
        // The baseline lands with the body, which arrives from the host over IPC — so the fields can already
        // carry an edit by then, and taking them live would file that edit as stored: caption says "Saved",
        // Save greys out, and the only way back to a live button is to type more and undo it.
        val body = CompletableDeferred<ByteArray>()
        setContent {
            WailoTheme(darkTheme = false) {
                ResponseRuleEditor(
                    title = "Mapping Rule",
                    initial = ResponseDraft(
                        urlPattern = "https://api.example.com/v1/*",
                        method = "GET",
                        statusCode = 200,
                        headers = listOf(ResponseHeader("Content-Type", "application/json")),
                    ),
                    initialName = "Login",
                    persisted = true,
                    enabled = true,
                    enabledToggleable = true,
                    onToggleEnabled = {},
                    bodySeed = null,
                    onLoadBody = { body.await() },
                    onPickFile = { null },
                    onSave = { _, _, _ -> },
                    onBack = {},
                    onClose = {},
                )
            }
        }

        onNodeWithText("https://api.example.com/v1/*").performTextInput("x")
        body.complete("""{"ok":true}""".encodeToByteArray())

        waitUntilExactlyOneExists(hasText("Unsaved changes"))
        onNodeWithText("Save").assertIsEnabled()
    }
}
