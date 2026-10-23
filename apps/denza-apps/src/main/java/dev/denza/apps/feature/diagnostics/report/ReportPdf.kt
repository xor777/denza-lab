package dev.denza.apps.feature.diagnostics.report

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.res.ResourcesCompat
import dev.denza.apps.R
import java.io.File
import java.io.OutputStream

/**
 * The sheet's inks: Roboto for words, Roboto Mono - the scanner's own - for codes and addresses, dark
 * ink and grey on white. One [Paint] per [ReportStyle], measured and drawn alike, so the layout's widths
 * are the painter's. Also the [TextMeasure] the layout runs on, and what a screen's preview of a page
 * draws with.
 */
internal class ReportPaints(context: Context) : TextMeasure {
    private val sans = Typeface.create(Typeface.SANS_SERIF, 400, false)
    private val sansMedium = Typeface.create(Typeface.SANS_SERIF, 500, false)
    private val mono = ResourcesCompat.getFont(context, R.font.roboto_mono_regular) ?: Typeface.MONOSPACE
    private val monoMedium = ResourcesCompat.getFont(context, R.font.roboto_mono_medium) ?: Typeface.MONOSPACE

    private val paints: Map<ReportStyle, Paint> = ReportStyle.entries.associateWith { style ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = when {
                style.mono && style.bold -> monoMedium
                style.mono -> mono
                style.bold -> sansMedium
                else -> sans
            }
            textSize = style.size
            letterSpacing = style.tracking
            color = if (style.grey) GREY else INK
        }
    }

    val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RULE
        strokeWidth = 0.6f
        style = Paint.Style.STROKE
    }

    fun paint(style: ReportStyle): Paint = paints.getValue(style)

    override fun width(text: String, style: ReportStyle): Float = paint(style).measureText(text)

    companion object {
        const val INK = 0xFF1B1F24.toInt()
        const val GREY = 0xFF5F6773.toInt()
        const val RULE = 0xFFC9CFD6.toInt()
    }
}

/** Draws a laid-out page: nothing is decided here (architecture, 7.4). */
internal object ReportPainter {
    fun draw(page: ReportPage, canvas: Canvas, paints: ReportPaints) {
        page.inks.forEach { ink ->
            when (ink) {
                is ReportInk.Text -> canvas.drawText(ink.text, ink.x, ink.baseline, paints.paint(ink.style))
                is ReportInk.Rule -> canvas.drawLine(ink.x0, ink.y, ink.x1, ink.y, paints.rule)
            }
        }
    }
}

/** A [ReportDocument] as a PDF: laid out with the device's fonts, one A4 page in points per [ReportPage]. */
internal object ReportPdf {
    /** Writes [document] to [out]; the number of pages. */
    fun write(document: ReportDocument, paints: ReportPaints, out: OutputStream): Int {
        val pages = ReportLayout(paints).lay(document)
        val pdf = PdfDocument()
        try {
            pages.forEach { p ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(ReportSheet.WIDTH.toInt(), ReportSheet.HEIGHT.toInt(), p.number).create())
                ReportPainter.draw(p, page.canvas, paints)
                pdf.finishPage(page)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
        return pages.size
    }
}

/**
 * [ReportWriter] into the shared «Загрузки», `Download/Denza/`, through MediaStore: the app's own files
 * there need no permission (architecture, 7.4). The name is the first base whose files the app has not
 * written yet; a file of another app with the same name is never touched - the provider gives the new
 * one another name, and the outcome says the names as given. Every file is written pending and shown
 * only when all of the bundle is in; a failure removes what this bundle had begun.
 */
internal class DownloadsReportWriter(private val context: Context, private val recordings: File) : ReportWriter {

    override fun lastRecording(): ReportRecording? {
        val file = recordings.listFiles { f -> f.isFile && f.name.startsWith("stream-") && f.name.endsWith(ReportNames.CSV) }
            ?.maxByOrNull { it.name } ?: return null
        val summary = file.bufferedReader().useLines { StreamCsv.summary(it.drop(1)) }
        if (summary.signals == 0) return null
        return ReportRecording(file.absolutePath, summary.durationMs, summary.signals)
    }

    override fun write(bundle: ReportBundle): ReportOutcome.Saved {
        val resolver = context.contentResolver
        val base = ReportNames.free(bundle.base, bundle.extensions, ownNames(resolver))
        val made = mutableListOf<Uri>()
        try {
            var pages = 0
            val pdf = insert(resolver, base + ReportNames.PDF, "application/pdf").also { made += it }
            open(resolver, pdf).use { pages = ReportPdf.write(bundle.document, ReportPaints(context), it) }
            bundle.json?.let { text ->
                val json = insert(resolver, base + ReportNames.JSON, "application/json").also { made += it }
                open(resolver, json).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
            bundle.stream?.let { path ->
                val csv = insert(resolver, base + ReportNames.CSV, "text/csv").also { made += it }
                open(resolver, csv).use { out -> File(path).inputStream().use { it.copyTo(out) } }
            }
            made.forEach { uri ->
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) { "$uri not published" }
            }
            return ReportOutcome.Saved(ReportNames.FOLDER, made.map { name(resolver, it) }, pages)
        } catch (failure: Throwable) {
            made.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
            throw failure
        }
    }

    private fun insert(resolver: ContentResolver, name: String, mime: String): Uri = checkNotNull(
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }),
    ) { "$name not created" }

    private fun open(resolver: ContentResolver, uri: Uri): OutputStream = checkNotNull(resolver.openOutputStream(uri, "w")) { "$uri not opened" }

    /** The names the app has already written into the folder; another app's are not visible and need not be. */
    private fun ownNames(resolver: ContentResolver): Set<String> = runCatching {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(RELATIVE_PATH),
            null,
        )?.use { c -> buildSet { while (c.moveToNext()) c.getString(0)?.let(::add) } }
    }.getOrNull().orEmpty()

    private fun name(resolver: ContentResolver, uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    companion object {
        const val RELATIVE_PATH = ReportNames.FOLDER + "/"
    }
}
