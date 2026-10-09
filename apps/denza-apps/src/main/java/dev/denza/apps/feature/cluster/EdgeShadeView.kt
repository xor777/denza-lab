package dev.denza.apps.feature.cluster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

internal class EdgeShadeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var topShader: Shader? = null
    private var bottomShader: Shader? = null
    private var fadeHeight = 1

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        fadeHeight = (height * 0.20f).toInt().coerceAtLeast(1)
        val dark = Color.argb(179, 0, 0, 0)
        val clear = Color.TRANSPARENT
        topShader = LinearGradient(
            0f,
            0f,
            0f,
            fadeHeight.toFloat(),
            dark,
            clear,
            Shader.TileMode.CLAMP,
        )
        bottomShader = LinearGradient(
            0f,
            (height - fadeHeight).toFloat(),
            0f,
            height.toFloat(),
            clear,
            dark,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        paint.shader = topShader
        canvas.drawRect(0f, 0f, width.toFloat(), fadeHeight.toFloat(), paint)
        paint.shader = bottomShader
        canvas.drawRect(0f, (height - fadeHeight).toFloat(), width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }
}
