package dev.denza.apps.feature.locale

import android.content.Context
import android.content.Intent
import java.util.Locale

/**
 * The language the whole car is speaking, and the door to the car's own list of them.
 *
 * This replaced a per-application override. The old tile asked Android to show one stock package -
 * `com.byd.carsettings` - in Russian, which needed `CHANGE_CONFIGURATION` granted over local ADB,
 * could not be read back without a signature permission, and translated exactly one application
 * while the rest of the car stayed English.
 *
 * The firmware has the real thing. `com.byd.carsettings` carries two language screens: the visible
 * one at `android.settings.LOCALE_SETTINGS`, whose list is two items long unless `Build.PRODUCT` is
 * one of `Di100VCP_IVI` / `Di150VCP_IVI` / `Di300VCP_IVI` - and this car reports `IVI`, which its
 * own log confirms - and an unlisted one at [PICKER_ACTION] with no such gate and forty languages
 * in it. Both are exported, neither is permission-guarded, and both apply the choice through the
 * vendor's `BYDAutoSettingDevice.setLanguage(int)`: the system locale, not one package's override.
 *
 * Proven on the car on 2026-09-18: opening [PICKER_ACTION] and choosing Russian moved
 * `persist.sys.locale` from `en-US` to `ru-RU` with no reboot - uptime carried straight through it -
 * and with the per-application override still empty. So this app has nothing to coordinate and no
 * permission to hold. It reads the locale and opens a door.
 */
data class SystemLanguageSnapshot(
    /**
     * What the car is speaking, in that language's own word for itself.
     *
     * Read by default rather than left blank. The locale is already in this process - there is no
     * call to make and nothing to wait for - so there is no honest "not read yet" state for the
     * tile to sit in, and it never has to draw an empty subtitle before the first refresh.
     */
    val name: String = SystemLanguage.currentName(),
)

object SystemLanguage {

    /**
     * The unlisted language screen, and the reason the tile is worth having at all.
     *
     * `LOCALE_SETTINGS1` is BYD's own action, not an AOSP one, and the activity behind it -
     * `com.byd.systemsettings.languageadb.view.LanguageSettings` - builds its list without the
     * platform check the visible screen applies.
     */
    const val PICKER_ACTION = "android.settings.LOCALE_SETTINGS1"

    /**
     * The visible language screen, for a car that has no unlisted one.
     *
     * A press has to end on a language screen. This firmware answers [PICKER_ACTION] and is the
     * only firmware this app ships to, so the fallback is not expected to run; it exists so that a
     * car without the unlisted screen lands on the stock two-item list instead of on nothing,
     * which is the one outcome a press is not allowed to have.
     */
    private const val STOCK_ACTION = "android.settings.LOCALE_SETTINGS"

    fun read(): SystemLanguageSnapshot = SystemLanguageSnapshot(name = currentName())

    fun currentName(): String = nameOf(Locale.getDefault())

    /**
     * Open the car's language list, preferring the long one.
     *
     * Resolved rather than caught: an unresolvable implicit intent throws on `startActivity`, and
     * the fallback has to be chosen before the throw rather than after it.
     */
    fun open(context: Context) {
        val intent = intent(context)
        runCatching { context.startActivity(intent) }
    }

    internal fun intent(context: Context): Intent {
        val picker = Intent(PICKER_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = context.packageManager.resolveActivity(picker, 0) != null
        return if (resolved) picker else Intent(STOCK_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The car's own word for a language, so the tile and the list it opens say the same thing.
     *
     * These are the forty names the firmware draws, read out of `CarSettingPlatform.apk`'s
     * `system_language_*` strings, keyed by the tags its presenter maps them to - which is why
     * Hebrew is `iw-IL` and Indonesian `in-ID` here; see [LEGACY_CODES].
     *
     * Not taken from [Locale.getDisplayLanguage]: it answers "русский" where the list says
     * "Русский язык", and a tile whose subtitle disagrees with the row the driver just tapped is
     * the sort of small lie this table exists to prevent. Its own answer is still the fallback,
     * for a locale set outside this list.
     *
     * One deliberate departure from the firmware: BYD's Russian string begins with a Latin `P`.
     * The glyph is indistinguishable on screen and the data is not, so this says Cyrillic.
     */
    private val FIRMWARE_NAMES: Map<String, String> = mapOf(
        "zh-CN" to "中文（简体）",
        "zh-TW" to "中文（繁體）",
        "en-US" to "English",
        "en-AU" to "English (Australia)",
        "es-ES" to "Español",
        "es-US" to "Español (América)",
        "pt-PT" to "Português (Portugal)",
        "pt-BR" to "Português (Brasil)",
        "ja-JP" to "日本語",
        "ko-KR" to "한국어",
        "fr-FR" to "Français",
        "fr-CA" to "Français (Canada)",
        "de-DE" to "Deutsch",
        "it-IT" to "Italiano",
        "hi-IN" to "हिंदी",
        "ar-IL" to "اللغة العربية",
        "nl-NL" to "Nederlands",
        "th-TH" to "ภาษาไทย",
        "sv-SE" to "Svenska",
        "nb-NO" to "Norsk",
        "fi-FI" to "Suomi",
        "da-DK" to "Dansk",
        "iw-IL" to "עִבְרִית",
        "ru-RU" to "Русский язык",
        "uz-UZ" to "O'zbek",
        "tr-TR" to "Türkçe",
        "hu-HU" to "Magyar",
        "sk-SK" to "Slovenské",
        "cs-CZ" to "Česky",
        "pl-PL" to "Polski",
        "vi-VN" to "Tiếng Việt",
        "in-ID" to "Bahasa Indonesia",
        "ms-MY" to "Bahasa Melayu",
        "ca-AD" to "Català",
        "hr-HR" to "Hrvatski",
        "ro-RO" to "Română",
        "el-GR" to "Ελληνικά",
        "uk-UA" to "Українська",
        "kk-KZ" to "Қазақ",
        "bg-BG" to "Български",
    )

    /**
     * Java's two spellings of three languages, reduced to the one the firmware uses.
     *
     * Android reports Hebrew as `iw` and Indonesian as `in`, which is what BYD's table is keyed by.
     * A desktop JVM from 17 onwards reports the modern `he` and `id` instead, so the same code
     * reads one way in a unit test and another on the car. Both spellings arrive here and one
     * leaves.
     */
    private val LEGACY_CODES = mapOf("he" to "iw", "id" to "in", "yi" to "ji")

    /**
     * The car's word for this locale, by exact tag first and by language second.
     *
     * The language-only pass is what makes `ru-UA` or a bare `de` still read as the car's own
     * "Русский язык" and "Deutsch". It takes the firmware's own regional pick for that language,
     * which is the one the list can actually be set to.
     */
    internal fun nameOf(locale: Locale): String {
        val reported = locale.language.lowercase(Locale.ROOT)
        val language = LEGACY_CODES[reported] ?: reported
        val country = locale.country.uppercase(Locale.ROOT)
        FIRMWARE_NAMES["$language-$country"]?.let { return it }
        FIRMWARE_NAMES.entries.firstOrNull { (tag, _) -> tag.startsWith("$language-") }
            ?.let { return it.value }
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }
}
