package dev.denza.apps.ui.components

/**
 * What kind of thing the line under a tile's name is saying.
 *
 * The design boards paint two live tiles' captions in champagne ("Приборы · на экране", "Следят за
 * поворотниками") and two others' in grey ("6 приложений", "Яндекс Навигатор") - on tiles that are
 * equally live. The first cut read one of them and generalised: every live tile got an accent
 * caption, four of the six lines on the screen turned champagne at once, and the accent stopped
 * meaning anything.
 *
 * The distinction the board is actually drawing is this one. A caption takes the accent when it
 * reports the feature **acting on the world** - something that started when the feature started and
 * stops when it stops. It stays muted when it names a **choice** the driver made, which is just as
 * true whether the feature is running or not, and equally when it merely restates the switch: the
 * tone already says whether the feature is on, and saying it twice in colour is how a screen runs
 * out of ways to say anything.
 */
enum class DenzaTileCaption {

    /** What the feature is doing right now. Champagne on a live tile. */
    READING,

    /** What it is set up with, or a restatement of the switch. Muted whatever the tone. */
    SETTING,
}
