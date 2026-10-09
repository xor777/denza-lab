package dev.denza.apps.feature.cluster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

internal class DiagnosticView(context: Context, private val label: String) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(10, 24, 28)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val inset = (width * 0.04f).coerceAtLeast(8f)
        paint.color = Color.rgb(20, 156, 132)
        canvas.drawRect(inset, inset, width - inset, height - inset, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = (height * 0.10f).coerceAtLeast(34f)
        canvas.drawText(label, width / 2f, height * 0.50f, paint)
    }
}
