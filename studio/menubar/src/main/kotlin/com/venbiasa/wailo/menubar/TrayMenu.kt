package com.venbiasa.wailo.menubar

import java.awt.CheckboxMenuItem
import java.awt.EventQueue
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon as AwtTrayIcon
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage

/** Everything the item renders, gathered so a poll hands over one value instead of a setter per row. */
internal data class MenuState(
    val listening: Boolean,
    val lanAddress: String,
    val capturePort: Int,
    val capturing: Boolean,
    val mapLocalEnabled: Boolean,
    val breakpointsEnabled: Boolean,
    val seedsEnabled: Boolean,
    val allowlistEnabled: Boolean,
    val blocklistEnabled: Boolean,
    // A list with no hosts cannot be armed (see CaptureFilterState), so its row is offered as disabled
    // rather than as a switch that flips back on the next poll.
    val allowlistConfigured: Boolean,
    val blocklistConfigured: Boolean,
    val studioAttached: Boolean,
) {
    val address: String get() = "$lanAddress:$capturePort"
}

internal class MenuActions(
    val showStudio: () -> Unit,
    val setCapturing: (Boolean) -> Unit,
    val setMapLocalEnabled: (Boolean) -> Unit,
    val setBreakpointsEnabled: (Boolean) -> Unit,
    val setSeedsEnabled: (Boolean) -> Unit,
    val setAllowlistEnabled: (Boolean) -> Unit,
    val setBlocklistEnabled: (Boolean) -> Unit,
    val quit: () -> Unit,
)

/**
 * The menu bar item itself: plain AWT, because this process exists for as long as any daemon does and a
 * Compose runtime resident purely to draw a handful of rows would cost more than the daemon it advertises
 * (ADR-0065).
 *
 * Every row reads or writes *daemon* state — there may be no Studio at all — and the whole item is a
 * projection of [MenuState], so the poll loop never touches an AWT control directly.
 */
internal class TrayMenu(
    private val mark: BufferedImage,
    private val actions: MenuActions,
) {
    // These two start disabled: neither means anything until a poll says whether a Studio can be raised
    // and what address there is to copy.
    private val showStudio = MenuItem("Show Studio").apply { isEnabled = false }
    private val recordTraffic = CheckboxMenuItem("Record Traffic")
    private val listenOn = Menu("Listen on").apply { isEnabled = false }
    // The parent row already spells the address out, so these say only what they do with it.
    private val copyAddress = MenuItem("Copy")
    private val copyHost = MenuItem("Copy only IP")
    // One row per feature master rather than a single switch over all of them: each is the same
    // non-destructive master the panel shows (ADR-0030), and only the daemon's own masters can appear
    // here (ADR-0066) — which now includes Seeds, since the daemon spends them (ADR-0067).
    private val enableTools = Menu("Enable Tools")
    private val mapLocal = CheckboxMenuItem("Map Local")
    private val breakpoints = CheckboxMenuItem("Breakpoints")
    private val seeds = CheckboxMenuItem("Seeds")
    private val allowlist = CheckboxMenuItem("Capture Filter: Allowlist")
    private val blocklist = CheckboxMenuItem("Capture Filter: Blocklist")
    private val quit = MenuItem("Quit")
    private val icon = AwtTrayIcon(mark, "Wailo", popup())

    // Null until the first poll lands, which is also what makes the address rows unclickable until there
    // is an address to copy.
    @Volatile
    private var state: MenuState? = null

    private fun popup() = PopupMenu().apply {
        add(showStudio)
        addSeparator()
        add(recordTraffic)
        addSeparator()
        listenOn.add(copyAddress)
        listenOn.add(copyHost)
        add(listenOn)
        addSeparator()
        enableTools.add(mapLocal)
        enableTools.add(breakpoints)
        enableTools.add(seeds)
        enableTools.add(allowlist)
        enableTools.add(blocklist)
        add(enableTools)
        addSeparator()
        add(quit)
    }

    fun install() {
        icon.isImageAutoSize = true
        icon.image = TrayIcon.render(mark)
        showStudio.addActionListener { actions.showStudio() }
        // Windows and Linux raise the app on a double-click of the icon itself; on macOS a click opens the
        // menu, so this never fires there.
        icon.addActionListener { actions.showStudio() }
        quit.addActionListener { actions.quit() }
        copyAddress.addActionListener { state?.let { copy(it.address) } }
        copyHost.addActionListener { state?.let { copy(it.lanAddress) } }
        // itemStateChanged fires after AWT has already flipped the box, so the daemon is told what the user
        // now sees. A refused write is corrected by the next poll rather than fought here.
        recordTraffic.addItemListener { actions.setCapturing(recordTraffic.state) }
        mapLocal.addItemListener { actions.setMapLocalEnabled(mapLocal.state) }
        breakpoints.addItemListener { actions.setBreakpointsEnabled(breakpoints.state) }
        seeds.addItemListener { actions.setSeedsEnabled(seeds.state) }
        allowlist.addItemListener { actions.setAllowlistEnabled(allowlist.state) }
        blocklist.addItemListener { actions.setBlocklistEnabled(blocklist.state) }
        SystemTray.getSystemTray().add(icon)
    }

    fun update(next: MenuState) = onEventQueue {
        // The poll is unconditional, so most ticks change nothing; rewriting the same labels underneath an
        // open menu is visible on macOS.
        if (next == state) return@onEventQueue
        state = next
        icon.toolTip = if (next.listening) "Wailo — listening on ${next.address}" else "Wailo — not listening"
        recordTraffic.state = next.capturing
        mapLocal.state = next.mapLocalEnabled
        breakpoints.state = next.breakpointsEnabled
        seeds.state = next.seedsEnabled
        allowlist.state = next.allowlistEnabled
        allowlist.isEnabled = next.allowlistConfigured
        blocklist.state = next.blocklistEnabled
        blocklist.isEnabled = next.blocklistConfigured
        listenOn.label = if (next.listening) "Listen on ${next.address}" else "Not listening"
        listenOn.isEnabled = next.listening
        // Disabled rather than hidden when there is no Studio to raise and no way to start one: a row that
        // does nothing when clicked is worse than one that says it cannot.
        showStudio.isEnabled = next.studioAttached || StudioLauncher.canLaunch()
    }

    fun remove() = onEventQueue { SystemTray.getSystemTray().remove(icon) }

    private fun copy(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    private fun onEventQueue(block: () -> Unit) {
        if (EventQueue.isDispatchThread()) block() else EventQueue.invokeLater { runCatching(block) }
    }
}
