package dev.denza.dipilotkey.probe;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.List;

public class WindowDiagnosticsTest {
    @Test
    public void missingRootAndUnrecognizedTextAreDifferentFromMissingButton() {
        WindowDiagnostics report = new WindowDiagnostics();
        report.observe(0, 1, 1, null, null, null);
        report.observe(0, 2, 3, "com.android.systemui", "Громкость", List.of());
        report.observe(0, 3, 3, "com.android.systemui", "Отладка по USB", List.of());
        String text = report.render();
        assertTrue(text.contains("w=1 type=1 pkg=? nodes=0: НЕТ дерева окна"));
        assertTrue(text.contains("w=2 type=3 pkg=com.android.systemui nodes=0: текст не распознан"));
        assertTrue(text.contains("w=3 type=3 pkg=com.android.systemui nodes=0: ADB распознан, кнопка"));
    }

    @Test
    public void reportsLocalizedAdbHintsWithoutChangingTheApprovalDecision() {
        WindowDiagnostics report = new WindowDiagnostics();
        String title = "允许USB调试吗？";
        PromptDecision decision = report.observe(2, 4, 3, "com.android.systemui", title,
                List.of(new PromptDecision.Node("android:id/button1", "允许", false, false)));
        assertEquals(-1, decision.clickIndex);
        assertTrue(report.render().contains("текст не распознан"));
        assertTrue(report.render().contains(title));
        assertTrue(report.render().contains("button1: 允许"));
    }

    @Test
    public void sameWindowIdOnDifferentDisplaysIsRecordedSeparatelyAndUpdatesReplaceOlderRows() {
        WindowDiagnostics report = new WindowDiagnostics();
        report.observe(0, 1, 1, "old.app", "", List.of());
        report.observe(2, 1, 1, "passenger.app", "", List.of());
        report.observe(0, 1, 1, "new.app", "", List.of());
        assertFalse(report.render().contains("old.app"));
        assertTrue(report.render().contains("d=2 w=1"));
        assertTrue(report.render().contains("new.app"));
    }

    @Test
    public void privateAppTextAndAdbKeyBodiesAreNotRetained() {
        WindowDiagnostics report = new WindowDiagnostics();
        report.observe(0, 1, 1, "messenger.app", "private message", List.of());
        report.observe(0, 2, 3, "com.android.systemui", "private notification\nADB " + "A".repeat(700),
                List.of());
        String text = report.render();
        assertFalse(text.contains("private message"));
        assertFalse(text.contains("private notification"));
        assertFalse(text.contains("A".repeat(100)));
        assertTrue(text.contains("ADB [ADB key]"));
    }

    @Test
    public void windowDetailsAreBoundedAndClearedForTheNextRun() {
        WindowDiagnostics report = new WindowDiagnostics();
        for (int id = 0; id < 30; id++) {
            report.observe(0, id, 1, "some.app", "", List.of());
        }
        assertFalse(report.render().contains("w=0 "));
        assertTrue(report.render().contains("w=29 "));
        assertEquals(WindowDiagnostics.LIMIT, report.render().split("pkg=", -1).length - 1);
        report.clear();
        assertTrue(report.render().contains("Наблюдений нет"));
    }
}
