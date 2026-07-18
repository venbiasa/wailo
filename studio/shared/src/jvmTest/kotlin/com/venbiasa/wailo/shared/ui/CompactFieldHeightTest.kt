package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.theme.WailoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

// Regression guard for a bug that kept coming back: the Map Local editor's Method picker (a compact field
// with a trailing dropdown arrow) rendered taller than the Status code text field beside it, because
// Material's trailing-icon slot forces a 48.dp touch target onto the whole field. Both now flow through
// CompactFieldDecoration, which drops that slop — so a field with a trailing icon must measure exactly as
// tall as one without. If this fails, the two controls have drifted apart again.
class CompactFieldHeightTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun trailingIconDoesNotChangeCompactFieldHeight() = runComposeUiTest {
        setContent {
            WailoTheme(darkTheme = false) {
                Row {
                    Box(Modifier.width(120.dp).testTag("textField")) {
                        CompactOutlinedTextField(
                            value = "200",
                            onValueChange = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // Mirrors MethodDropdown: a compact field whose only extra is a trailing arrow icon.
                    Box(Modifier.width(120.dp).testTag("dropdown")) {
                        val src = remember { MutableInteractionSource() }
                        CompactFieldDecoration(
                            value = "GET",
                            interactionSource = src,
                            trailingIcon = { Box(Modifier.size(20.dp)) },
                            innerTextField = { Text("GET", style = MaterialTheme.typography.bodyMedium) },
                        )
                    }
                }
            }
        }

        fun height(tag: String) =
            onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertEquals(height("textField").value, height("dropdown").value, 0.5f)
    }
}
