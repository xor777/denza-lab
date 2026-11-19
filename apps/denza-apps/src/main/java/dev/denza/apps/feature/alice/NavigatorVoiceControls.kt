package dev.denza.apps.feature.alice

/** Exact Navigator controls; an Alice panel button has different, state-dependent semantics. */
internal object NavigatorVoiceControls {
    const val MAP_VOICE_ICON_ID = "ru.yandex.yandexnavi:id/control_voice_search_image"

    val viewIds = listOf(
        "ru.yandex.yandexnavi:id/navi_service_open_voice_search",
        "ru.yandex.yandexnavi:id/guidance_open_voice_search",
    )

    fun isMapVoiceButton(viewId: String?, className: CharSequence?): Boolean =
        viewId.isNullOrEmpty() && className?.toString() == "android.widget.Button"

    fun <T> findUnique(
        find: (String) -> List<T>,
        usable: (T) -> Boolean,
        mapButtonForIcon: (T) -> T?,
    ): T? {
        val named = viewIds.flatMap(find)
        if (named.isNotEmpty()) return named.singleOrNull(usable)
        return find(MAP_VOICE_ICON_ID).singleOrNull()?.let(mapButtonForIcon)?.takeIf(usable)
    }
}
