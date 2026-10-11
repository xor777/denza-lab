package dev.denza.dipilotkey.probe;

import static org.junit.Assert.*;
import org.junit.Test;

public class ReportSummaryTest {
    @Test public void targetKeyStatusIsVisibleAndNeverTurnsIntoATargetShellClaim() {
        String summary = ReportSummary.from("Denza Apps: ключ сохранён системой\n"
                + "Shell после поиска окон: ДА — shell есть\n");
        assertTrue(summary.contains("Denza Apps: ключ сохранён системой"));
        assertEquals(4, summary.split("\n").length);
        assertFalse(summary.contains("Denza Apps: shell"));
    }
    @Test
    public void ownersPhotoBecomesFourShortLinesWithoutHidingAnUnknownQueue() {
        String text = "Служба подключена: ДА\n"
                + "Проверок окон: 29; окон в последней проверке: 6\n"
                + "Найдено окон ADB с кнопкой: 0\n"
                + "Попыток «Разрешить»: 0; ACTION_CLICK принят: 0\n"
                + "Shell после поиска окон: ДА — shell есть\n"
                + "Очередь после (последнее событие журнала): НЕИЗВЕСТНО; отсутствие сообщений не означает пустую очередь.\n"
                + "Итоговая повторная проверка shell: ДА — shell есть\n";
        String summary = ReportSummary.from(text);
        assertEquals("Shell: ДА · служба: подключена\n"
                + "Denza Apps: не проверен\n"
                + "Окон: 6 · ADB: 0 · нажатий: 0/0\n"
                + "Очередь (журнал): неизвестно", summary);
        for (String line : summary.split("\n")) {
            assertTrue(line.length() <= 48);
        }
    }

    @Test
    public void finalFailureAndLaterPendingObservationReplaceEarlierSuccessAndEmptyMarker() {
        String text = "Первая проверка shell: ДА — shell есть\n"
                + "Очередь до разрешений (последнее событие журнала): сообщение «no prompts to send» — очередь была пуста.\n"
                + "После разрешения 1 (последнее событие журнала): ЕСТЬ ожидающий запрос; размер очереди неизвестен.\n"
                + "Итоговая повторная проверка shell: НЕТ — проверка не удалась\n";
        String summary = ReportSummary.from(text);
        assertTrue(summary.startsWith("Shell: НЕТ"));
        assertTrue(summary.endsWith("есть ожидающий запрос"));
    }

    @Test
    public void historicalEmptyMarkerStaysHistoricalInTheCompactStatus() {
        assertTrue(ReportSummary.from("Очередь после (последнее событие журнала): "
                + "сообщение «no prompts to send» — очередь была пуста.\n").endsWith("была пуста"));
        assertTrue(ReportSummary.from("Подключаюсь ключом Dipilot…").contains("Shell: не проверен"));
    }
}
