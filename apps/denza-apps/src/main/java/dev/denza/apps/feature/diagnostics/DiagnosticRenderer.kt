package dev.denza.apps.feature.diagnostics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import org.json.JSONObject
import kotlin.math.min

internal data class DiagnosticHit(val id: String, val label: String, val x: Float, val y: Float, val w: Float, val h: Float)

/** Native canvas. Geometry comes from the same asset as the normative Luminofor board. */
internal class DiagnosticRenderer(context: Context) {
    val layout = JSONObject(context.assets.open("diagnostics/layout.json").bufferedReader().use { it.readText() })
    private val colors = layout.getJSONObject("colors")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
    private val face = Typeface.create("sans-serif", Typeface.NORMAL)
    private val strong = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val paths = mutableMapOf<String, android.graphics.Path>()
    fun tab(id: String): JSONObject = (0 until layout.getJSONArray("tabs").length()).map { layout.getJSONArray("tabs").getJSONObject(it) }.first { it.getString("id") == id }
    fun defaultNode(id: String): String = tab(id).getString("default")
    fun hits(tabId: String, selected: String, page: Int, f: DiagnosticFrame, detail: Boolean = false, statusDetail: Boolean = false): List<DiagnosticHit> = buildList {
        add(DiagnosticHit("close", "Назад в сервис", 956f, 12f, 48f, 48f))
        if (!f.running) {
            add(if (DtcEcu.forNode(selected) != null) DiagnosticHit("read", "Прочитать DTC ECU ${f.ecu.address}", 770f, 12f, 174f, 48f)
                else DiagnosticHit("coverage", "Показать охват чтения DTC", 770f, 12f, 174f, 48f))
            add(DiagnosticHit("lamps", "Обновить показания", 20f, 532f, 204f, 44f))
        }
        val tabs = layout.getJSONArray("tabs")
        for(i in 0 until tabs.length()) add(DiagnosticHit("tab:" + tabs.getJSONObject(i).getString("id"), tabs.getJSONObject(i).getString("label"), 20f+i*123, 78f, 123f, 46f))
        val nodes = tab(tabId).getJSONArray("nodes")
        for(i in 0 until nodes.length()) {
            val n = nodes.getJSONObject(i); val id = n.getString("id")
            add(DiagnosticHit("node:$id", layout.getJSONObject("nodes").getJSONObject(id).getString("title") + ", " + (f.nodes[id]?.value ?: "Нет данных"), 20+n.getDouble("x").toFloat()*1.06f, 140+n.getDouble("y").toFloat(), 159f, 68f))
        }
        if ((selected == "body" || selected == "lbms") && detail) {
            add(DiagnosticHit("list", "Вернуться к списку DTC", 754f, 174f, 238f, 36f))
            add(DiagnosticHit("status", if (statusDetail) "Значение кода DTC" else "Все восемь битов статуса DTC", 754f, 478f, 238f, 32f))
        } else if ((selected == "body" || selected == "lbms")) {
            f.records.drop(page*5).take(5).forEachIndexed { i, r ->
                val d = DtcDescriptions.describe(r, f.format)
                add(DiagnosticHit("code:${page*5+i}", "${d.display}, ${d.summary}, ${r.label}. Открыть значение и статус", 754f, 241f+i*43, 238f, 43f))
            }
            if(page > 0) add(DiagnosticHit("prev", "Предыдущие коды", 754f, 478f, 50f, 42f))
            if((page+1)*5 < f.records.size) add(DiagnosticHit("next", "Следующие коды", 938f, 478f, 54f, 42f))
        } else if ((f.nodes[selected]?.rows?.size ?: 0) > 5) {
            if(page > 0) add(DiagnosticHit("prev", "Предыдущие записи", 754f, 478f, 50f, 42f))
            if((page+1)*5 < f.nodes.getValue(selected).rows.size) add(DiagnosticHit("next", "Следующие записи", 938f, 478f, 54f, 42f))
        }
    }
    fun draw(c: Canvas, width: Int, height: Int, f: DiagnosticFrame, tabId: String, selected: String, page: Int, code: Int, detail: Boolean = false, statusDetail: Boolean = false) {
        c.drawColor(color("bg"))
        val scale = min(width/1024f, height/608f)
        c.save(); c.translate((width-1024*scale)/2, (height-608*scale)/2); c.scale(scale, scale)
        fun rect(x: Number,y: Number,w: Number,h: Number,fill: String,stroke: String?=null,r: Float=0f) {
            paint.color=color(fill);paint.style=Paint.Style.FILL
            val box=RectF(x.toFloat(),y.toFloat(),x.toFloat()+w.toFloat(),y.toFloat()+h.toFloat())
            c.drawRoundRect(box,r,r,paint)
            if(stroke!=null){paint.color=color(stroke);paint.style=Paint.Style.STROKE;paint.strokeWidth=1f;c.drawRoundRect(box,r,r,paint)}
        }
        fun text(value: String,x: Number,y: Number,size: Number=12,tone: String="ink",max: Float=1000f,bold: Boolean=false) {
            paint.style=Paint.Style.FILL;paint.color=color(tone);paint.textSize=size.toFloat();paint.typeface=if(bold)strong else face
            var v=value
            if(paint.measureText(v)>max){while(v.isNotEmpty() && paint.measureText(v+"…")>max) v=v.dropLast(1);v+="…"}
            c.drawText(v,x.toFloat(),y.toFloat(),paint)
        }
        text("Диагностика автомобиля",20,34,23,bold=true)
        text("${f.link}   ·   ${f.profile}   ·   ${f.park}",20,59,12,"muted",720f)
        rect(770,12,174,48,if(f.running)"panel" else "select",if(f.running)"line" else "blue",4f)
        text(if(f.running)"Чтение…" else if (DtcEcu.forNode(selected) != null) "DTC · ${f.ecu.address}" else "Охват DTC",791,42,16,if(f.running)"muted" else "ink")
        rect(956,12,48,48,"panel","line",4f);text("×",969,45,29)
        val tabs=layout.getJSONArray("tabs")
        for(i in 0 until tabs.length()) {
            val t=tabs.getJSONObject(i); val active=t.getString("id")==tabId
            rect(20+i*123,78,123,46,if(active)"select" else "bg")
            text(t.getString("label"),32+i*123,107,14,if(active)"ink" else "muted")
            if(active) rect(20+i*123,122,123,2,"blue")
        }
        rect(20,130,700,1,"line");text(tab(tabId).getString("title"),20,155,13,"muted",700f)
        c.save();c.translate(20f,140f);c.scale(1.06f,1f)
        val shapes=tab(tabId).getJSONArray("shapes")
        for(i in 0 until shapes.length()) {
            val shape=shapes.getJSONObject(i);val picked=shape.optString("part")==selected
            if(shape.getString("kind")=="rect") rect(shape.getDouble("x"),shape.getDouble("y"),shape.getDouble("w"),shape.getDouble("h"),if(picked)"select" else "part",if(picked)"blue" else "line",shape.optDouble("r",0.0).toFloat())
            else {
                val d=shape.getString("d");val p=paths.getOrPut(d){PathParser.createPathFromPathData(d)}
                paint.style=Paint.Style.STROKE;paint.strokeWidth=if(shape.optString("style")=="wire")2f else 1.2f
                paint.color=color(if(shape.optString("style")=="wire")"blue" else "line");c.drawPath(p,paint)
            }
        }
        c.restore()
        val nodes=tab(tabId).getJSONArray("nodes")
        for(i in 0 until nodes.length()) {
            val n=nodes.getJSONObject(i);val id=n.getString("id");val v=f.nodes[id] ?: DiagnosticNode()
            val x=20+n.getDouble("x").toFloat()*1.06f;val y=140+n.getDouble("y").toFloat()
            rect(x,y,159,68,if(id==selected)"select" else "panel",if(id==selected)"blue" else "line",3f)
            text(layout.getJSONObject("nodes").getJSONObject(id).getString("label"),x+10,y+17,9,"muted",139f)
            text(v.value,x+10,y+40,18,v.tone,139f)
            text(v.caption,x+10,y+57,10,"muted",139f)
        }
        rect(742,140,262,380,"panel","line",4f)
        text(layout.getJSONObject("nodes").getJSONObject(selected).getString("title"),754,164,16,max=238f,bold=true)
        val node=f.nodes[selected] ?: DiagnosticNode()
        if((selected=="body" || selected=="lbms") && detail && code in f.records.indices) {
            val r = f.records[code]; val d = DtcDescriptions.describe(r, f.format)
            rect(754,174,238,36,"select","blue",3f); text("‹  К списку DTC",766,198,13)
            text(d.display,754,241,26,max=238f,bold=true)
            if (statusDetail) {
                text("Статус %02X · поддержка %02X".format(r.status, f.availability),754,264,11,"muted",238f)
                DtcStatusLabel.entries.forEachIndexed { i, bit ->
                    text("${i} · ${bit.title}",754,290+i*21,10,"muted",164f)
                    text(bit.value(r, f.availability),919,290+i*21,10,max=73f)
                }
                text(f.dtcTime,754,454,10,"muted",238f)
                text("Биты независимы · лампа ≠ причина",754,468,10,"muted",238f)
            } else {
                text("Исходный код ${r.code} · формат %02X".format(f.format),754,266,11,"muted",238f)
                d.meaning.forEachIndexed { i, line -> text(line,754,300+i*20,14,max=238f) }
                text("Тип отказа ${d.failureType ?: "—"} · ${d.subtype}",754,350,12,max=238f)
                d.source.forEachIndexed { i, line -> text(line,754,385+i*20,11,"muted",238f) }
                text(if (f.ecu == DtcEcu.BODY) "ECU ${f.ecu.address} · имя не доказано" else "LBMS · низковольтная батарея",754,438,11,max=238f)
                text(f.dtcTime,754,458,11,"muted",238f)
            }
            rect(754,478,238,32,"select","blue",3f)
            text(if (statusDetail) "‹  Значение кода" else "Все биты статуса  ›",766,499,12)
        } else if((selected=="body" || selected=="lbms")) {
            text(f.dtcTime,754,187,11,"muted",238f)
            text(if(f.dtcRead)"Текущих ${if(f.availability and 1 != 0) f.records.count{it.active} else "—"} · сохранённых ${if(f.availability and 8 != 0) f.records.count{it.confirmed} else "—"}" else "ECU ещё не опрошен",754,211,12,max=238f)
            text("Код и значение · нажмите для деталей",754,233,11,"muted",238f)
            f.records.drop(page*5).take(5).forEachIndexed { i,r ->
                val y=241+i*43; val d = DtcDescriptions.describe(r, f.format)
                if(page*5+i==code) rect(750,y,246,43,"select")
                text(d.display,758,y+16,13,if(r.active||r.warning)"amber" else "ink")
                text(r.label,856,y+16,10,if(r.active||r.warning)"amber" else "muted",136f)
                text(d.summary,758,y+34,11,"muted",234f)
            }
            if(!f.dtcRead) {text("Запрос 0x%03X → ${f.ecu.address}".format(f.ecu.request),754,280,12,"muted");text("Остальные ECU не опрошены",754,305,12,"muted")}
            if(f.dtcRead && f.records.isEmpty()) {text("0 записей по маске 0xFF",754,280,13);text("Только этот ECU, этот снимок",754,305,11,"muted")}
            if (f.dtcRead && f.records.isEmpty()) {
                text("Ожидают: ${if(f.availability and 4 != 0) "0" else "бит не поддержан"}",754,343,11,"muted")
                text("Запрос лампы: ${if(f.availability and 128 != 0) "0" else "бит не поддержан"}",754,366,11,"muted")
            }
            if(f.records.size>5){if(page>0)text("‹",770,507,23);text("${page+1} / ${(f.records.size+4)/5}",858,504,12,"muted");if((page+1)*5<f.records.size)text("›",962,507,23)}
            else {
                text(if(f.ecu == DtcEcu.BODY) "Общий справочник; OEM не получен" else "LBMS · низковольтная батарея",754,481,11,"muted",238f)
                if(f.dtcRead) text("Поддержано %02X · формат %02X".format(f.availability, f.format),754,503,10,"muted",238f)
            }
        } else {
            text(node.value,754,200,24,node.tone,238f)
            node.rows.drop(page*5).take(5).forEachIndexed { i,row -> text(row.first,754,236+i*33,11,"muted",125f);text(row.second,879,236+i*33,12,max=113f) }
            node.notes.take(3).forEachIndexed {i,line->text(line,754,(if(node.rows.size>5) 412 else 442)+i*20,11,"muted",238f)}
            if(node.rows.size>5){if(page>0)text("‹",770,507,23);text("${page+1} / ${(node.rows.size+4)/5}",858,504,12,"muted");if((page+1)*5<node.rows.size)text("›",962,507,23)}
            if(node.rows.isEmpty() && node.notes.isEmpty()) {
                text("Источник ещё не подключён",754,244,12,"muted");text("Значение неизвестно",754,271,12,"muted")
            }
        }
        text("Связи на схеме — функциональные",20,513,11,"muted")
        rect(20,532,204,44,"panel","line",3f);text("Обновить показания",33,560,13,if(f.running)"muted" else "ink")
        text(f.lampTime,242,549,11,"muted",470f);text(f.message,242,570,11,"muted",470f)
        if((selected=="body" || selected=="lbms") && code in f.records.indices) {
            val r=f.records[code]
            fun bit(mask: Int) = if(f.availability and mask == 0) "н/д" else if(r.status and mask != 0) "да" else "нет"
            text("${r.code}  ·  status 0x%02X".format(r.status),742,543,12)
            text("Отказ ${bit(1)} · ожидает ${bit(4)}",742,560,10,"muted")
            text("Подтверждён ${bit(8)} · запрос лампы ${bit(128)}",742,577,10,"muted")
            text("Отказ после очистки: ${bit(32)}",742,594,10,"muted")
        } else text("Серый: без оценки исправности",742,551,11,"muted",262f)
        text(if (f.dtcRead) "Полный ответ ECU ${f.ecu.address} · запрос 0x19 · маска 0xFF · охват автомобиля не установлен." else "DTC ещё не прочитаны · доступны два адреса · охват автомобиля не установлен.",20,599,11,"muted",700f)
        c.restore()
    }
    private fun color(key: String): Int = Color.parseColor(colors.getString(key))
}
