package dev.denza.tools;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;

/** Shell-only probe: inspect Navigator's real controls, or click one exact visible view ID. */
public final class YandexAliceUiProbe {
    private static final String PACKAGE = "ru.yandex.yandexnavi";

    public static void main(String[] args) throws Exception {
        boolean waitClick = args.length == 2 && "wait-click".equals(args[0]);
        boolean query = args.length == 2 && "query".equals(args[0]);
        boolean compare = args.length == 2 && "compare".equals(args[0]);
        boolean visibleSplit = args.length == 1 && "reveal-visible".equals(args[0]);
        boolean revealClick = args.length == 1 && ("reveal-click".equals(args[0]) || visibleSplit);
        boolean click = args.length == 2 && ("click".equals(args[0]) || waitClick);
        boolean dump = args.length == 1 && "dump".equals(args[0]);
        if (!click && !query && !compare && !revealClick && !dump && !(args.length == 1 && "inspect".equals(args[0]))) {
            throw new IllegalArgumentException("inspect | dump | reveal-click | reveal-visible | {query|compare|click|wait-click} ru.yandex.yandexnavi:id/VIEW_ID");
        }
        if (click && !args[1].startsWith(PACKAGE + ":id/")) {
            throw new IllegalArgumentException("Only Navigator view IDs are allowed");
        }
        HandlerThread thread = new HandlerThread("alice-ui-probe");
        thread.start();
        UiAutomation automation = null;
        boolean connected = false;
        List<AccessibilityNodeInfo> retained = new ArrayList<>();
        try {
            Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
            Object connection = Class.forName("android.app.UiAutomationConnection")
                    .getConstructor().newInstance();
            automation = UiAutomation.class.getConstructor(Looper.class, connectionType)
                    .newInstance(thread.getLooper(), connection);
            UiAutomation.class.getMethod("connect", int.class).invoke(automation,
                    UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            connected = true;
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            if (revealClick) {
                revealAndClick(automation, visibleSplit);
                return;
            }
            if (compare) {
                compareLookups(automation, args[1]);
                return;
            }
            if (query) {
                for (AccessibilityWindowInfo window : automation.getWindows()) {
                    try {
                        if (window.getDisplayId() != 0 || !window.isFocused()) continue;
                        AccessibilityNodeInfo root = window.getRoot();
                        if (root == null) continue;
                        try {
                            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(args[1]);
                            System.out.println("QUERY window=" + window.getId() + " count=" + nodes.size());
                            for (AccessibilityNodeInfo node : nodes) {
                                System.out.println("view=" + node.getViewIdResourceName() + " visible=" + node.isVisibleToUser()
                                        + " clickable=" + node.isClickable() + " enabled=" + node.isEnabled());
                                node.recycle();
                            }
                        } finally { root.recycle(); }
                    } finally { window.recycle(); }
                }
                return;
            }
            if (waitClick) {
                waitAndClick(automation, args[1]);
                return;
            }
            List<AccessibilityNodeInfo> matches = new ArrayList<>();
            // No waitForIdle: Navigator keeps animating even when no user is touching it.
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                retained.add(root);
                if (!PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                    continue;
                }
                System.out.println("WINDOW id=" + window.getId() + " display=" + window.getDisplayId()
                        + " focused=" + window.isFocused() + " rootVisible=" + root.isVisibleToUser());
                List<AccessibilityNodeInfo> queue = new ArrayList<>();
                queue.add(root);
                for (int i = 0; i < queue.size() && i < 2000; i++) {
                    AccessibilityNodeInfo node = queue.get(i);
                    String id = node.getViewIdResourceName();
                    if (dump) {
                        System.out.println("node=" + id + " class=" + node.getClassName()
                                + " visible=" + node.isVisibleToUser() + " clickable=" + node.isClickable()
                                + " enabled=" + node.isEnabled() + " actions=" + node.getActionList()
                                + " description=" + node.getContentDescription());
                    }
                    if (id != null && node.isVisibleToUser()) {
                        if (id.contains("alice") || id.contains("voice") || id.contains("mic")) {
                            Rect nodeBounds = new Rect(); node.getBoundsInScreen(nodeBounds);
                            System.out.println("view=" + id + " bounds=" + nodeBounds + " clickable=" + node.isClickable()
                                    + " enabled=" + node.isEnabled()
                                    + " description=" + node.getContentDescription());
                        }
                        if (click && args[1].equals(id) && node.isClickable() && node.isEnabled()) {
                            matches.add(node);
                        }
                    }
                    for (int child = 0; child < node.getChildCount(); child++) {
                        AccessibilityNodeInfo next = node.getChild(child);
                        if (next != null) { queue.add(next); retained.add(next); }
                    }
                }
            }
            if (click) {
                if (matches.size() != 1) {
                    System.out.println("ACTION_CLICK accepted=false reason=target-count count=" + matches.size());
                    return;
                }
                AccessibilityNodeInfo target = matches.get(0);
                if (!target.refresh() || !target.isVisibleToUser() || !target.isEnabled()
                        || !args[1].equals(target.getViewIdResourceName())
                        || !PACKAGE.contentEquals(target.getPackageName())) {
                    throw new IllegalStateException("Button changed before click");
                }
                boolean accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                System.out.println("ACTION_CLICK accepted=" + accepted + " view=" + args[1]);
                if (!accepted) throw new IllegalStateException("Navigator rejected the action");
            }
        } catch (IllegalStateException unavailable) {
            System.out.println("ACTION_CLICK accepted=false reason=" + unavailable.getMessage());
        } finally {
            for (AccessibilityNodeInfo node : retained) node.recycle();
            if (connected) UiAutomation.class.getMethod("disconnect").invoke(automation);
            thread.quitSafely();
        }
    }

    /** One source-backed touch of the speed indicator, then the exact route microphone. */
    private static void revealAndClick(UiAutomation automation, boolean visibleSplit) throws Exception {
        int windowId = navigatorWindow(automation, visibleSplit);
        if (windowId < 0) throw new IllegalStateException("Navigator is not focused");
        String microphone = PACKAGE + ":id/guidance_open_voice_search";
        AccessibilityNodeInfo existing = exactVisible(automation, windowId, microphone);
        if (existing != null) {
            existing.recycle();
            throw new IllegalStateException("Controls must already be hidden for this probe");
        }
        AccessibilityNodeInfo speed = exactVisible(automation, windowId, PACKAGE + ":id/speedview_guidance");
        if (speed == null) throw new IllegalStateException("No unique speed indicator");
        Rect bounds = new Rect();
        try {
            if (!speed.refresh() || !speed.isVisibleToUser()) throw new IllegalStateException("Speed view changed");
            speed.getBoundsInScreen(bounds);
        } finally { speed.recycle(); }
        if (bounds.isEmpty() || navigatorWindow(automation, visibleSplit) != windowId) throw new IllegalStateException("Window changed");
        List<AccessibilityWindowInfo> allWindows = automation.getWindows();
        try {
            AccessibilityWindowInfo targetWindow = null;
            for (AccessibilityWindowInfo window : allWindows) if (window.getId() == windowId) targetWindow = window;
            if (targetWindow == null) throw new IllegalStateException("Window disappeared");
            for (AccessibilityWindowInfo window : allWindows) {
                android.graphics.Region region = new android.graphics.Region();
                window.getRegionInScreen(region);
                if (window.getDisplayId() == 0 && window.getLayer() > targetWindow.getLayer()
                        && region.contains(bounds.centerX(), bounds.centerY())) throw new IllegalStateException("Touch point covered");
            }
        } finally { for (AccessibilityWindowInfo window : allWindows) window.recycle(); }
        System.out.println("REVEAL window=" + windowId + " view=speedview_guidance bounds=" + bounds);
        long down = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try {
                if (!automation.injectInputEvent(event, true)) throw new IllegalStateException("Touch rejected");
            } finally { event.recycle(); }
            if (action == MotionEvent.ACTION_DOWN) Thread.sleep(40);
        }
        long deadline = SystemClock.uptimeMillis() + 1500;
        while (SystemClock.uptimeMillis() < deadline) {
            if (navigatorWindow(automation, visibleSplit) != windowId) throw new IllegalStateException("Window changed after reveal");
            AccessibilityNodeInfo node = exactVisible(automation, windowId, microphone);
            if (node != null) {
                try {
                    if (node.refresh() && node.isVisibleToUser() && node.isEnabled() && node.isClickable()) {
                        System.out.println("ACTION_CLICK accepted=" + node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                + " view=" + microphone + " afterMs=" + (SystemClock.uptimeMillis() - down));
                        return;
                    }
                } finally { node.recycle(); }
            }
            Thread.sleep(50);
        }
        System.out.println("ACTION_CLICK accepted=false reason=reveal-timeout");
    }

    private static int navigatorWindow(UiAutomation automation, boolean visibleSplit) {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        try {
            int candidate = -1;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getDisplayId() != 0 || (!visibleSplit && !window.isFocused())
                        || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    if (root.isVisibleToUser() && PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                        if (candidate != -1) return -1;
                        candidate = window.getId();
                    }
                }
                finally { root.recycle(); }
            }
            return candidate;
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private static AccessibilityNodeInfo exactVisible(UiAutomation automation, int windowId, String viewId) {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getId() != windowId) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) return null;
                try {
                    List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(viewId);
                    try {
                        AccessibilityNodeInfo result = null;
                        for (AccessibilityNodeInfo node : matches) {
                            if (!node.isVisibleToUser() || !node.isEnabled()) continue;
                            if (result != null) return null;
                            result = node;
                        }
                        return result == null ? null : AccessibilityNodeInfo.obtain(result);
                    } finally { for (AccessibilityNodeInfo node : matches) node.recycle(); }
                } finally { root.recycle(); }
            }
            return null;
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private static void compareLookups(UiAutomation automation, String viewId) throws Exception {
        System.out.println("COMPARE_READY");
        long deadline = android.os.SystemClock.uptimeMillis() + 120000;
        String previous = "";
        int tick = 0;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            AccessibilityServiceInfo info = automation.getServiceInfo();
            boolean include = (tick++ % 2) == 1;
            if (include) info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            else info.flags &= ~AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            automation.setServiceInfo(info);
            List<AccessibilityWindowInfo> windows = automation.getWindows();
            try {
                for (AccessibilityWindowInfo window : windows) {
                    if (!window.isFocused() || window.getDisplayId() != 0) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    List<AccessibilityNodeInfo> retained = new ArrayList<>();
                    retained.add(root);
                    try {
                        List<AccessibilityNodeInfo> direct = root.findAccessibilityNodeInfosByViewId(viewId);
                        int directCount = direct.size();
                        for (AccessibilityNodeInfo n : direct) n.recycle();
                        int walked = 0;
                        String target = "";
                        for (int i = 0; i < retained.size() && i < 1000; i++) {
                            AccessibilityNodeInfo n = retained.get(i);
                            if (viewId.equals(n.getViewIdResourceName())) {
                                walked++;
                                target = " visible=" + n.isVisibleToUser() + " clickable=" + n.isClickable();
                            }
                            for (int c = 0; c < n.getChildCount(); c++) {
                                AccessibilityNodeInfo child = n.getChild(c);
                                if (child != null) retained.add(child);
                            }
                        }
                        String value = " include=" + include + " window=" + window.getId() + " root=" + root.getClassName()
                                + " direct=" + directCount + " walked=" + walked + " nodes=" + retained.size() + target;
                        if (!value.equals(previous)) {
                            System.out.println("COMPARE time=" + System.currentTimeMillis() + value);
                            previous = value;
                        }
                    } finally { for (AccessibilityNodeInfo n : retained) n.recycle(); }
                }
            } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
            Thread.sleep(200);
        }
    }

    /** One bounded, exact-ID click after the owner reveals a control; never activates another pane. */
    private static void waitAndClick(UiAutomation automation, String viewId) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 60000;
        System.out.println("WAITING_FOR_VISIBLE_TARGET " + viewId);
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            List<AccessibilityWindowInfo> windows = automation.getWindows();
            try {
                for (AccessibilityWindowInfo window : windows) {
                    if (window.getDisplayId() != 0 || !window.isFocused()
                            || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    try {
                        if (!PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(viewId);
                        try {
                            AccessibilityNodeInfo target = null;
                            int count = 0;
                            for (AccessibilityNodeInfo node : matches) {
                                if (node.isVisibleToUser() && node.isEnabled() && node.isClickable()) {
                                    target = node; count++;
                                }
                            }
                            if (count == 1 && target.refresh() && target.isVisibleToUser()
                                    && target.isEnabled() && target.isClickable()) {
                                System.out.println("TARGET window=" + window.getId() + " view=" + viewId
                                        + " visible=" + target.isVisibleToUser() + " clickable=" + target.isClickable());
                                boolean accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                System.out.println("ACTION_CLICK accepted=" + accepted + " view=" + viewId);
                                return;
                            }
                        } finally { for (AccessibilityNodeInfo node : matches) node.recycle(); }
                    } finally { root.recycle(); }
                }
            } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
            Thread.sleep(100);
        }
        System.out.println("ACTION_CLICK accepted=false reason=target-timeout");
    }
}
