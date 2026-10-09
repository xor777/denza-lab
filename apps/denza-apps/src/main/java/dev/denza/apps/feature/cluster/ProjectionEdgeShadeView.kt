package dev.denza.apps.feature.cluster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/** Navigation contrast shades, strengthened for the center panel. */
// Explicit save/restore calls make the destructive blend scope auditable.
@SuppressLint("UseKtx")
internal class ProjectionEdgeShadeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eraseMode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private var topShader: Shader? = null
    private var bottomShader: Shader? = null
    private var cornerShader: Shader? = null
    private var bottomRevealShader: Shader? = null
    private var topLeftRevealShader: Shader? = null
    private var topRightRevealShader: Shader? = null
    private var fadeHeight = (90f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private var shadeTopAlpha = 204
    private var shadeBottomAlpha = 204
    private var shadeBottomTopAlpha = 0
    private var shadeBottomFadePx = 0
    private var shadeBottomSolidPx = 0
    private var shadeBottomRevealRadiusPx = 0
    private var shadeBottomRevealHeightPercent = 0
    private var shadeBottomRevealCenterOffsetPx = 0
    private var shadeTopLeftRevealRadiusPx = 0
    private var shadeTopRightRevealRadiusPx = 0
    private var shadeTopRevealHeightPx = 0
    private var shadeCenterTopFadePx = 0
    private var shadeTop = true
    private var shadeBottom = true
    private var shadeCorner: ClusterShadeCorner? = null

    fun configure(
        top: Boolean,
        bottom: Boolean,
        heightDp: Int,
        topAlpha: Int,
        bottomAlpha: Int,
        bottomTopAlpha: Int,
        bottomFadePx: Int,
        bottomSolidPx: Int,
        bottomRevealRadiusPx: Int,
        bottomRevealHeightPercent: Int,
        bottomRevealCenterOffsetPx: Int,
        topLeftRevealRadiusPx: Int,
        topRightRevealRadiusPx: Int,
        topRevealHeightPx: Int,
        centerTopFadePx: Int,
        corner: ClusterShadeCorner?,
    ) {
        shadeTop = top
        shadeBottom = bottom
        shadeCorner = corner
        fadeHeight = (heightDp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        shadeTopAlpha = topAlpha.coerceIn(0, 255)
        shadeBottomAlpha = bottomAlpha.coerceIn(0, 255)
        shadeBottomTopAlpha = bottomTopAlpha.coerceIn(0, 255)
        shadeBottomFadePx = bottomFadePx.coerceAtLeast(0)
        shadeBottomSolidPx = bottomSolidPx.coerceAtLeast(0)
        shadeBottomRevealRadiusPx = bottomRevealRadiusPx.coerceAtLeast(0)
        shadeBottomRevealHeightPercent = bottomRevealHeightPercent.coerceIn(0, 100)
        shadeBottomRevealCenterOffsetPx = bottomRevealCenterOffsetPx.coerceAtLeast(0)
        shadeTopLeftRevealRadiusPx = topLeftRevealRadiusPx.coerceAtLeast(0)
        shadeTopRightRevealRadiusPx = topRightRevealRadiusPx.coerceAtLeast(0)
        shadeTopRevealHeightPx = topRevealHeightPx.coerceAtLeast(0)
        shadeCenterTopFadePx = centerTopFadePx.coerceAtLeast(0)
        rebuildShaders()
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        rebuildShaders()
    }

    private fun rebuildShaders() {
        if (width <= 0 || height <= 0) {
            topShader = null
            bottomShader = null
            cornerShader = null
            bottomRevealShader = null
            topLeftRevealShader = null
            topRightRevealShader = null
            return
        }
        val edge = fadeHeight.coerceAtMost(height).toFloat()
        val clear = Color.TRANSPARENT
        val topDark = Color.argb(shadeTopAlpha, 0, 0, 0)
        topShader = LinearGradient(
            0f,
            0f,
            0f,
            edge,
            topDark,
            clear,
            Shader.TileMode.CLAMP,
        )
        val bottomDark = Color.argb(shadeBottomAlpha, 0, 0, 0)
        val topTint = Color.argb(shadeBottomTopAlpha, 0, 0, 0)
        val solidHeight = shadeBottomSolidPx.coerceAtMost(height)
        val bottomFadeHeight = shadeBottomFadePx.coerceAtMost(height - solidHeight)
        val fadeTop = height - solidHeight - bottomFadeHeight
        val solidTop = height - solidHeight
        val topFade = shadeCenterTopFadePx.coerceAtMost(fadeTop)
        bottomShader = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            intArrayOf(topTint, clear, clear, bottomDark, bottomDark),
            floatArrayOf(
                0f,
                topFade.toFloat() / height,
                fadeTop.toFloat() / height,
                solidTop.toFloat() / height,
                1f,
            ),
            Shader.TileMode.CLAMP,
        )
        cornerShader = shadeCorner?.let { corner ->
            RadialGradient(
                if (corner == ClusterShadeCorner.TOP_LEFT) 0f else width.toFloat(),
                0f,
                edge.coerceAtLeast(1f),
                topDark,
                clear,
                Shader.TileMode.CLAMP,
            )
        }
        val bottomRadius = shadeBottomRevealRadiusPx.toFloat()
        val bottomCenterY = (height - shadeBottomRevealCenterOffsetPx)
            .coerceAtLeast(0)
            .toFloat()
        bottomRevealShader = if (bottomRadius > 0f) {
            RadialGradient(
                width / 2f,
                bottomCenterY,
                bottomRadius,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.30f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else {
            null
        }
        topLeftRevealShader = revealShader(0f, shadeTopLeftRevealRadiusPx)
        topRightRevealShader = revealShader(width.toFloat(), shadeTopRightRevealRadiusPx)
    }

    private fun revealShader(centerX: Float, radiusPx: Int): Shader? {
        if (radiusPx <= 0) return null
        return RadialGradient(
            centerX,
            0f,
            radiusPx.toFloat(),
            Color.WHITE,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val revealLayer = if (
            shadeBottomRevealRadiusPx > 0 ||
                shadeTopLeftRevealRadiusPx > 0 ||
                shadeTopRightRevealRadiusPx > 0
        ) {
            canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        } else {
            null
        }
        val edge = fadeHeight.coerceAtMost(height)
        if (shadeTop) {
            paint.shader = topShader
            canvas.drawRect(0f, 0f, width.toFloat(), edge.toFloat(), paint)
        }
        if (shadeBottom) {
            paint.shader = bottomShader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        shadeCorner?.let { corner ->
            paint.shader = cornerShader
            val left = if (corner == ClusterShadeCorner.TOP_LEFT) 0f else width - edge.toFloat()
            val right = if (corner == ClusterShadeCorner.TOP_LEFT) edge.toFloat() else width.toFloat()
            canvas.drawRect(left.coerceAtLeast(0f), 0f, right.coerceAtMost(width.toFloat()), edge.toFloat(), paint)
        }
        if (shadeBottomRevealRadiusPx > 0 && shadeBottomRevealHeightPercent > 0) {
            val centerX = width / 2f
            val centerY = (height - shadeBottomRevealCenterOffsetPx)
                .coerceAtLeast(0)
                .toFloat()
            val radius = shadeBottomRevealRadiusPx.toFloat()
            val revealSave = canvas.save()
            canvas.scale(
                1f,
                shadeBottomRevealHeightPercent / 100f,
                centerX,
                centerY,
            )
            paint.shader = bottomRevealShader
            paint.xfermode = eraseMode
            canvas.drawCircle(centerX, centerY, radius, paint)
            paint.xfermode = null
            canvas.restoreToCount(revealSave)
        }
        if (shadeTopRevealHeightPx > 0) {
            drawTopReveal(
                canvas,
                0f,
                shadeTopLeftRevealRadiusPx,
                topLeftRevealShader,
            )
            drawTopReveal(
                canvas,
                width.toFloat(),
                shadeTopRightRevealRadiusPx,
                topRightRevealShader,
            )
        }
        paint.shader = null
        revealLayer?.let(canvas::restoreToCount)
    }

    private fun drawTopReveal(
        canvas: Canvas,
        centerX: Float,
        radiusPx: Int,
        shader: Shader?,
    ) {
        if (radiusPx <= 0 || shader == null) return
        val radius = radiusPx.toFloat()
        val revealSave = canvas.save()
        canvas.scale(1f, shadeTopRevealHeightPx / radius, centerX, 0f)
        paint.shader = shader
        paint.xfermode = eraseMode
        canvas.drawCircle(centerX, 0f, radius, paint)
        paint.xfermode = null
        canvas.restoreToCount(revealSave)
    }
}
