package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.resources.Res
import com.venbiasa.wailo.shared.resources.ic_delete
import com.venbiasa.wailo.shared.resources.ic_link
import com.venbiasa.wailo.shared.theme.WailoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

// The Devices panel stacks a plain section band ("Connected") on one carrying icon buttons ("Paired"), so
// any height difference between them reads as a misaligned panel. Material's IconButton reserves a 48.dp
// touch target regardless of the size it's given, which is exactly how the compact fields and switches
// drifted before (see CompactFieldHeightTest) — so the two bands are only equal by construction, and this
// pins it. Each header is wrapped in a Box, which overlays rather than stacks, so the measured height is
// the band itself and not the trailing divider.
class SectionHeaderHeightTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun sectionActionsDoNotChangeHeaderHeight() = runComposeUiTest {
        setContent {
            WailoTheme(darkTheme = false) {
                Column(Modifier.width(340.dp)) {
                    Box(Modifier.testTag("plain")) { SectionHeader("Connected") }
                    Box(Modifier.testTag("withActions")) {
                        SectionHeader("Paired") {
                            PanelIconButton(Res.drawable.ic_link, "Pair a device", {})
                            PanelIconButton(Res.drawable.ic_delete, "Forget all paired devices", {})
                        }
                    }
                }
            }
        }

        fun height(tag: String) =
            onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertEquals(height("plain").value, height("withActions").value, 0.5f)
    }
}
