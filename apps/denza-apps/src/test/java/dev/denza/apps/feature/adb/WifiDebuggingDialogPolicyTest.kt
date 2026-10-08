package dev.denza.apps.feature.adb

import org.junit.Assert.*
import org.junit.Test

class WifiDebuggingDialogPolicyTest {
    private class Node(override val checked: Boolean = false, val takesClick: Boolean = true) : WifiDialogNode {
        val nodes = mutableMapOf<String, Node>()
        var clicks = 0
        override fun find(id: String) = nodes[id]
        override fun click(): Boolean { clicks++; return takesClick }
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
}
