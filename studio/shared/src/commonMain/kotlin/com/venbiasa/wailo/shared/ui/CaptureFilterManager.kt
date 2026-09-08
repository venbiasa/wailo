package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.shared.CaptureFilterState

/**
 * The capture-filter panel: two host-pattern lists — an allowlist and a blocklist — each with its own
 * on/off switch, that decide which traffic devices capture and stream (ADR-0029). Modeled on Map Local's
 * groups: each list is a header band (a collapse caret, its switch, and a `+` to add a host) over its item
 * rows (a host with a delete affordance), collapsible like a group but with no drag and no per-item switch
 * — the whole list is armed by its one switch.
 *
 * Stateless over its inputs: the host owns [filter] and its persistence and pushes it to devices; this
 * renders it and hands back a new [CaptureFilterState] for every change via [onFilterChange] (add/remove a
 * host, flip a list, or flip the whole feature). A list's switch is disabled while it has no entries, so an
 * empty list can't be armed. `*` matches any run of characters, so `*.example.com` covers a whole subdomain.
 * The header switch is the feature master (ADR-0030): off dims both lists and disables their switches while
 * keeping their hosts and armed state, and the host then captures everything. It fills whatever surface it's
 * given (the studio's right tool panel).
 */
@Composable
internal fun CaptureFilterManager(
    filter: CaptureFilterState,
    onFilterChange: (CaptureFilterState) -> Unit,
    onClose: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    // A background surface with a surfaceContainer header band (not a fully-tinted panel) so the top bar
    // matches the other panels exactly (same fill and the shared [TopBarHeight]). Tapping empty space
    // drops any open add-field's focus — the click-away counterpart to Esc; child controls hit-test first.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .height(TopBarHeight)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Capture Filter",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                // The feature master heads the right-side cluster, before Close: one switch arms/disarms the
                // whole filter, above the two per-list switches below (ADR-0030). Off keeps every host and
                // each list's armed state — it only pauses what the host pushes — so flipping it back on
                // restores what was armed.
                HoverTooltip(if (filter.masterEnabled) "On" else "Off") {
                    CompactSwitch(
                        checked = filter.masterEnabled,
                        onCheckedChange = { onFilterChange(filter.setMasterEnabled(it)) },
                    )
                }
                Spacer(Modifier.width(4.dp))
                CloseButton(onClose, contentDescription = "Close capture filter")
            }
            RowDivider()

            // Dim + disarm the lists while the master is off: the sections stay readable but plainly inert,
            // and their switches go non-interactive (each list keeps its own armed state underneath).
            Column(
                Modifier.fillMaxSize()
                    .alpha(if (filter.masterEnabled) 1f else DisabledFeatureAlpha)
                    .verticalScroll(rememberScrollState()),
            ) {
                MutedText(
                    "Choose which hosts devices capture: an allowlist captures only matches, a blocklist skips them.",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                )
                RowDivider()
                HostPatternSection(
                    title = "Allowlist",
                    hosts = filter.allowHosts,
                    emptyText = "No hosts — add one to capture only those hosts.",
                    addTooltip = "Add to allowlist",
                    onToggleEnabled = { onFilterChange(filter.setAllowEnabled(it)) },
                    onAddHost = { onFilterChange(filter.addAllow(it)) },
                    onRemoveHost = { onFilterChange(filter.removeAllow(it)) },
                    showEnabledSwitch = true,
                    enabled = filter.allowEnabled,
                    armable = filter.masterEnabled,
                )
                HostPatternSection(
                    title = "Blocklist",
                    hosts = filter.blockHosts,
                    emptyText = "No hosts — add one to skip capturing it.",
                    addTooltip = "Add to blocklist",
                    onToggleEnabled = { onFilterChange(filter.setBlockEnabled(it)) },
                    onAddHost = { onFilterChange(filter.addBlock(it)) },
                    onRemoveHost = { onFilterChange(filter.removeBlock(it)) },
                    showEnabledSwitch = true,
                    enabled = filter.blockEnabled,
                    armable = filter.masterEnabled,
                )
            }
        }
    }
}
