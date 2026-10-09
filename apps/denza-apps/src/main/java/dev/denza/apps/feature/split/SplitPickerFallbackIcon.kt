package dev.denza.apps.feature.split

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * What a picker tile shows for an application whose own icon did not load: a three-by-three grid.
 *
 * It is Material's `Icons.Outlined.Apps` (material-icons-extended 1.7.8), copied path for path with
 * the builder's own settings, so the picker draws exactly what it drew when it took the icon from
 * that library - which the app no longer depends on for this one glyph.
 */
internal val SplitPickerFallbackIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Outlined.Apps",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = false,
    ).path(
        fill = SolidColor(Color.Black),
        fillAlpha = 1f,
        stroke = null,
        strokeAlpha = 1f,
        strokeLineWidth = 1f,
        strokeLineCap = StrokeCap.Butt,
        strokeLineJoin = StrokeJoin.Bevel,
        strokeLineMiter = 1f,
    ) {
        moveTo(4f, 8f)
        horizontalLineToRelative(4f)
        lineTo(8f, 4f)
        lineTo(4f, 4f)
        verticalLineToRelative(4f)
        close()
        moveTo(10f, 20f)
        horizontalLineToRelative(4f)
        verticalLineToRelative(-4f)
        horizontalLineToRelative(-4f)
        verticalLineToRelative(4f)
        close()
        moveTo(4f, 20f)
        horizontalLineToRelative(4f)
        verticalLineToRelative(-4f)
        lineTo(4f, 16f)
        verticalLineToRelative(4f)
        close()
        moveTo(4f, 14f)
        horizontalLineToRelative(4f)
        verticalLineToRelative(-4f)
        lineTo(4f, 10f)
        verticalLineToRelative(4f)
        close()
        moveTo(10f, 14f)
        horizontalLineToRelative(4f)
        verticalLineToRelative(-4f)
        horizontalLineToRelative(-4f)
        verticalLineToRelative(4f)
        close()
        moveTo(16f, 4f)
        verticalLineToRelative(4f)
        horizontalLineToRelative(4f)
        lineTo(20f, 4f)
        horizontalLineToRelative(-4f)
        close()
        moveTo(10f, 8f)
        horizontalLineToRelative(4f)
        lineTo(14f, 4f)
        horizontalLineToRelative(-4f)
        verticalLineToRelative(4f)
        close()
        moveTo(16f, 14f)
        horizontalLineToRelative(4f)
        verticalLineToRelative(-4f)
        horizontalLineToRelative(-4f)
        verticalLineToRelative(4f)
        close()
        moveTo(16f, 20f)
        horizontalLineToRelative(4f)
        verticalLineToRelative(-4f)
        horizontalLineToRelative(-4f)
        verticalLineToRelative(4f)
        close()
    }.build()
}
