package dev.denza.apps.feature.hud;

import dev.denza.apps.R;
import dev.denza.apps.core.AppDecimal;
import dev.denza.apps.core.AppLanguage;
import dev.denza.apps.core.AppText;
import dev.denza.hudlink.HudProtocol;

/**
 * The words of the route summary the capture sends to the stock HUD ({@code SUMMARY_DISTANCE} and
 * {@code SUMMARY_TIME}). A publication asks here once per poll, and the compositor's frames reuse
 * the fields it made, so these lookups never run per frame and each is the memoised
 * {@link AppText#s(int)}. The YandexHUD module has none of this: it keeps
 * {@link HudProtocol.SummaryWords#RUSSIAN}.
 */
final class HudSummaryWords {
    /**
     * Which words go to the glass when the app speaks English. Nobody knows yet whether the stock HUD
     * draws Latin in these fields, or at what width: the car test in docs/system-language.md,
     * "The first car test", settles it. If it fails, set this to false and English mode keeps
     * Russian words in these two fields. It governs only the text fields the stock HUD draws: the radar
     * ({@link HudRadarBadge}) is a PNG we draw with our own fonts, so its English units stay.
     */
    static final boolean SEND_ENGLISH_TO_GLASS = true;

    private HudSummaryWords() {
    }

    static HudProtocol.SummaryWords forGlass() {
        return forGlass(AppText.INSTANCE.getLanguage(), SEND_ENGLISH_TO_GLASS);
    }

    static HudProtocol.SummaryWords forGlass(AppLanguage language, boolean sendEnglish) {
        if (language != AppLanguage.RUSSIAN && !sendEnglish) {
            return HudProtocol.SummaryWords.RUSSIAN;
        }
        return new HudProtocol.SummaryWords(
                AppText.s(R.string.hud_summary_metres),
                AppText.s(R.string.hud_summary_kilometres),
                AppText.s(R.string.hud_summary_minutes),
                AppText.s(R.string.hud_summary_hours),
                AppDecimal.mark());
    }

    /**
     * Whether the time is printed here instead of passing on Yandex's own text. Russian on the glass
     * has always been Yandex's text and stays so, byte for byte.
     */
    static boolean printsTime() {
        return printsTime(AppText.INSTANCE.getLanguage(), SEND_ENGLISH_TO_GLASS);
    }

    static boolean printsTime(AppLanguage language, boolean sendEnglish) {
        return language != AppLanguage.RUSSIAN && sendEnglish;
    }
}
