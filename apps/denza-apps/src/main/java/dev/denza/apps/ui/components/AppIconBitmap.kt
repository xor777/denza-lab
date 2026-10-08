package dev.denza.apps.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import dev.denza.apps.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An application's icon for a tile or a row, by package, rasterised at [sizePx].
 *
 * The state carries the package and nothing else ([AppIcons] says why). What the catalog already
 * read is drawn on the first frame; anything else is read off the main thread and drawn when it
 * lands, the initial standing in until then. Keyed by the package and by the one icon held for it,
 * so a republished state never re-rasterises a picture that did not change.
 */
@Composable
internal fun rememberAppIcon(packageName: String?, sizePx: Int): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val icon = remember(packageName) { mutableStateOf(packageName?.let(AppIcons::cached)) }
    LaunchedEffect(packageName) {
        if (packageName == null || icon.value != null || AppIcons.isMissing(packageName)) {
            return@LaunchedEffect
        }
        icon.value = withContext(Dispatchers.IO) { AppIcons.load(context, packageName) }
    }
    val drawable = icon.value
    return remember(packageName, drawable, sizePx) {
        drawable?.let { runCatching { it.toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull() }
    }
}
