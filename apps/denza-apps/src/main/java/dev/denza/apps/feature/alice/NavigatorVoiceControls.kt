package dev.denza.apps.feature.alice

/** Exact Navigator controls; an Alice panel button has different, state-dependent semantics. */
internal object NavigatorVoiceControls {
    val viewIds = listOf(
        "ru.yandex.yandexnavi:id/navi_service_open_voice_search",
        "ru.yandex.yandexnavi:id/guidance_open_voice_search",
    )

    fun <T> findUnique(find: (String) -> List<T>, usable: (T) -> Boolean): T? =
        viewIds.flatMap(find).filter(usable).singleOrNull()
}
