package dev.denza.dipilotkey.probe;

import java.util.List;
import java.util.Locale;

/**
 * Which node, if any, is the Allow button of a SystemUI ADB dialog.
 *
 * <p>On this firmware the USB dialog is titled {@code Отладка по USB} and the positive button
 * reads {@code Разрешить} ({@code android:id/button1}). The same button id is the structural
 * signal, so a label this table does not list still gets pressed when it is that button and
 * the window is the debugging dialog. The negative button is never pressed.
 */
final class PromptDecision {
    static final int MAX_CLICKS = 8;
    static final PromptDecision NONE = new PromptDecision(-1, -1);

    final int checkIndex;
    final int clickIndex;

    private PromptDecision(int checkIndex, int clickIndex) {
        this.checkIndex = checkIndex;
        this.clickIndex = clickIndex;
    }

    static final class Node {
        final String viewId;
        final String text;
        final boolean checkable;
        final boolean checked;

        Node(String viewId, String text, boolean checkable, boolean checked) {
            this.viewId = viewId;
            this.text = text == null ? "" : text;
            this.checkable = checkable;
            this.checked = checked;
        }
    }

    static PromptDecision decide(String packageName, String windowText, List<Node> nodes) {
        if (!isDebuggingWindow(packageName, windowText) || nodes == null) {
            return NONE;
        }
        int check = -1;
        int button1 = -1;
        int labeled = -1;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            String text = node.text.trim();
            if (check < 0 && node.checkable && !node.checked && isAlwaysAllow(text)) {
                check = i;
            }
            if (isButton1(node.viewId) && !isNegative(text)) {
                button1 = i;
            }
            if (isAllowLabel(text)) {
                labeled = i;
            }
        }
        int click = button1 >= 0 ? button1 : labeled;
        if (click < 0) {
            return NONE;
        }
        return new PromptDecision(check, click);
    }

    static boolean isDebuggingWindow(String packageName, String windowText) {
        if (!"com.android.systemui".equals(packageName) || windowText == null) {
            return false;
        }
        String text = windowText.toLowerCase(Locale.ROOT);
        return text.contains("отлад")
                || text.contains("usb debugging")
                || text.contains("wireless debugging")
                || text.contains("отпечаток ключа rsa")
                || text.contains("rsa key fingerprint");
    }

    private static boolean isButton1(String viewId) {
        return viewId != null && (viewId.endsWith("/button1") || viewId.equals("button1"));
    }

    private static boolean isAllowLabel(String text) {
        String label = text.toLowerCase(Locale.ROOT);
        return label.equals("разрешить")
                || label.equals("ok")
                || label.equals("ок")
                || label.equals("allow");
    }

    private static boolean isNegative(String text) {
        String label = text.toLowerCase(Locale.ROOT);
        return label.equals("отмена")
                || label.equals("cancel")
                || label.equals("deny")
                || label.equals("запретить");
    }

    private static boolean isAlwaysAllow(String text) {
        String label = text.toLowerCase(Locale.ROOT);
        return label.contains("всегда") || label.contains("always allow");
    }
}
