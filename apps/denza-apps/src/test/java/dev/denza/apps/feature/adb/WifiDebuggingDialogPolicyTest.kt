package dev.denza.apps.feature.adb

import org.junit.Assert.*
import org.junit.Test

class WifiDebuggingDialogPolicyTest {
    private class Node(override val checked: Boolean = false, val takesClick: Boolean = true) : WifiDialogNode {
        val nodes = mutableMapOf<String, Node>()
        val texts = mutableListOf<String>()
        var clicks = 0
        override fun find(id: String) = nodes[id]
        override fun says(text: String) = text in texts
        override fun click(): Boolean { clicks++; return takesClick }
    }
    private fun window(root: Node?, title: String? = WIFI_TITLE, pkg: String = "com.android.systemui") =
        WifiDebuggingDialogPolicy.allow(true, pkg, null, root, title)
    private fun dialog(title: String): Pair<Node, Node> {
        val root = Node(); val button = Node()
        root.texts += title; root.nodes[WifiDebuggingDialogPolicy.ALLOW_ID] = button
        root.nodes["android:id/alwaysUse"] = Node()
        return root to button
    }

    /**
     * A window found already up when the service connects carries no activity name; it is the
     * network dialog when SystemUI's window says SystemUI's own title for it (2026-11-19).
     */
    @Test fun aWindowAlreadyUpIsTheNetworkDialogByItsSystemTitle() {
        val (root, button) = dialog(WIFI_TITLE)
        assertEquals(WifiDialogOutcome.ALLOWED, window(root))
        assertEquals(1, button.clicks)
    }
    @Test fun aWindowAlreadyUpWithAnotherTitleOrOwnerIsLeftAlone() {
        // The USB authorization dialog: the same button and checkbox ids, its own title.
        val (usb, usbButton) = dialog(USB_TITLE)
        assertEquals(WifiDialogOutcome.IGNORED, window(usb))
        val (other, otherButton) = dialog(WIFI_TITLE)
        assertEquals(WifiDialogOutcome.IGNORED, window(other, pkg = "com.other"))
        // No title read from SystemUI: nothing is recognised.
        assertEquals(WifiDialogOutcome.IGNORED, window(other, title = null))
        assertEquals(WifiDialogOutcome.IGNORED, window(other, title = ""))
        assertEquals(WifiDialogOutcome.IGNORED, window(null))
        assertEquals(WifiDialogOutcome.IGNORED,
            WifiDebuggingDialogPolicy.allow(false, "com.android.systemui", null, other, WIFI_TITLE))
        assertEquals(0, usbButton.clicks + otherButton.clicks)
    }
    @Test fun anEventIsJudgedByItsActivityAsBefore() {
        // The event names the activity; a title, matching or not, changes nothing.
        val (usb, button) = dialog(USB_TITLE)
        assertEquals(WifiDialogOutcome.IGNORED,
            WifiDebuggingDialogPolicy.allow(true, "com.android.systemui", "com.android.systemui.usb.UsbDebuggingActivity", usb, USB_TITLE))
        assertEquals(WifiDialogOutcome.ALLOWED,
            WifiDebuggingDialogPolicy.allow(true, "com.android.systemui", "com.android.systemui.wifi.WifiDebuggingActivity", usb, null))
        assertEquals(1, button.clicks)
    }
    private fun allow(root: Node?, enabled: Boolean = true, pkg: String = "com.android.systemui", cls: String = "com.android.systemui.wifi.WifiDebuggingActivity") =
        WifiDebuggingDialogPolicy.allow(enabled, pkg, cls, root)

    @Test fun onlyTheExactSystemNetworkDialogCanBeClicked() {
        val root = Node(); val button = Node(); root.nodes[WifiDebuggingDialogPolicy.ALLOW_ID] = button
        for ((pkg, cls) in listOf("com.other" to "WifiDebuggingActivity", "com.android.systemui" to "UsbDebuggingActivity",
            "com.android.systemui" to "WifiDebuggingSecondaryUserActivity")) {
            assertEquals(WifiDialogOutcome.IGNORED, allow(root, pkg = pkg, cls = cls))
        }
        assertEquals(WifiDialogOutcome.IGNORED, allow(root, enabled = false)); assertEquals(0, button.clicks)
    }
    @Test fun checkboxIsMarkedBeforeAllowAndCheckedBoxIsLeftAlone() {
        for (checked in listOf(false, true)) {
            val root = Node(); val box = Node(checked); val button = Node()
            root.nodes["android:id/alwaysUse"] = box; root.nodes[WifiDebuggingDialogPolicy.ALLOW_ID] = button
            assertEquals(WifiDialogOutcome.ALLOWED, allow(root))
            assertEquals(if (checked) 0 else 1, box.clicks); assertEquals(1, button.clicks)
        }
    }
    @Test fun missingOrRejectedCheckboxStillAllowsThisNetworkOnce() {
        val root = Node(); root.nodes[WifiDebuggingDialogPolicy.ALLOW_ID] = Node()
        assertEquals(WifiDialogOutcome.ALLOWED_ONCE, allow(root))
        root.nodes["android:id/alwaysUse"] = Node(takesClick = false)
        assertEquals(WifiDialogOutcome.ALLOWED_ONCE, allow(root))
    }
    @Test fun absentOrRejectedAllowIsNotReportedAsSuccess() {
        assertEquals(WifiDialogOutcome.NO_ALLOW_BUTTON, allow(null))
        val root = Node(); val box = Node(); root.nodes["android:id/alwaysUse"] = box
        assertEquals(WifiDialogOutcome.NO_ALLOW_BUTTON, allow(root)); assertEquals(0, box.clicks)
        root.nodes[WifiDebuggingDialogPolicy.ALLOW_ID] = Node(takesClick = false)
        assertEquals(WifiDialogOutcome.ALLOW_CLICK_REJECTED, allow(root))
    }

    private companion object {
        // SystemUI's own strings on this car (`wifi_debugging_title`, `usb_debugging_title`).
        const val WIFI_TITLE = "Allow wireless debugging on this network?"
        const val USB_TITLE = "Allow USB debugging?"
    }
}
