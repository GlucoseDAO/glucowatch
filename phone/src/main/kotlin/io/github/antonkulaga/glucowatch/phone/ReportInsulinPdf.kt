package io.github.antonkulaga.glucowatch.phone

import android.graphics.*
import glucowatch.core.*
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.*

/** Shared event-level daily/activity view; hourly distributions belong only to summaries. */
internal object ReportInsulinPdf {
    fun draw(c: Canvas, zone: ZoneId, from: Long, until: Long, top: Float, height: Float,
             rows: List<ExportRow>, estimate: ReportBasalEstimate, right: Float = 490f, workouts: List<Workout> = emptyList()) {
        val left = 60f; val bottom = top + height; val width = right - left
        val red = ReportStyle.BOLUS; val blue = ReportStyle.BASAL; val compact = right < 490f
        fun p(color: Int, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color; this.alpha=alpha }
        fun text(x: Float,y: Float,s: String,color: Int=Color.BLACK,size: Float=6.5f) = c.drawText(s,x,y,p(color).apply { textSize=size; typeface=Typeface.create("sans-serif",Typeface.NORMAL) })
        fun line(x1: Float,y1: Float,x2: Float,y2: Float,color: Int,width: Float=.5f) = c.drawLine(x1,y1,x2,y2,p(color).apply { strokeWidth=width })
        fun x(t: Long) = left + width*((t-from).toDouble()/(until-from)).toFloat()
        fun round(box: RectF,alpha: Int=64) { if(box.width()>0&&box.height()>0)c.drawRoundRect(box,min(2f,box.width()/2),2f,p(blue,alpha)) }
        val pulses = rows.filter { it.source==ExportRow.SOURCE_CARELINK&&it.kind==ExportRow.KIND_BASAL_DELIVERY&&it.insulinU!=null&&it.timeMillis in from until until }.distinctBy { it.identity() }
        val measured = pulses.isNotEmpty()
        val blueMax = if(measured)max(.1,ceil(pulses.maxOf { it.insulinU!! }*10)/10) else ceil(max(2.5,estimate.intervals.maxOfOrNull { max(it.scheduledRate,it.programmedRate) }?:0.0)*2)/2
        fun by(v: Double)=bottom-(v/blueMax).toFloat()*height
        val headingY=top-if(compact)17 else 30
        text(22f,headingY,"Insulin",size=10f)
        text(85f,headingY,"Bolus U (red, left) / Basal ${if(measured)"U"else"U/h"} (blue, right)",size=if(compact)6.4f else 7.5f)
        if(!compact)text(left,top-17,"Blue: scheduled/held 25% + temporary 25% = overlap 50%; hatch = suspension",blue)
        text(left,top-7,"Red spike = immediate U; rising ramp = cumulative total (dashed: unconfirmed); grey = elevation",red,if(compact)5.2f else 6.1f)
        ReportElevationPdf.draw(c,workouts,from,until,RectF(left,top,right,bottom))
        (0..4).forEach { i->val yy=by(blueMax*i/4);line(left,yy,right,yy,ReportStyle.GRID);text(right+4,yy+2,String.format(Locale.US,"%.1f",blueMax*i/4),blue) }
        text(right+4,top-7,if(measured)"U"else"U/h",blue)
        val saved=c.save();c.clipRect(left,top,right,bottom)
        rows.filter { it.kind=="pump_disconnected"&&it.durationMin!=null&&it.timeMillis<until&&it.timeMillis+(it.durationMin!!*60_000).toLong()>from }.forEach {
            val a=maxOf(from,it.timeMillis);val b=minOf(until,it.timeMillis+(it.durationMin!!*60_000).toLong())
            c.drawRect(x(a),top,x(b),bottom,p(Color.GRAY,35))
            text(x(a).coerceAtMost(right-80)+2,top+9,"Pump disconnected",ReportStyle.GREY,5.8f)
        }
        if(measured)pulses.forEach { r->line(x(r.timeMillis),bottom,x(r.timeMillis),by(r.insulinU!!),blue,1.3f) }
        else {
            fun snap(t: Long)=((t+150_000)/300_000)*300_000
            val display=estimate.intervals.map { it.copy(start=max(from,snap(it.start)),end=min(until,snap(it.end))) }.filter { it.end>it.start }
            var hour=Instant.ofEpochMilli(from).atZone(zone).withMinute(0).withSecond(0).withNano(0)
            while(hour.toInstant().toEpochMilli()<until){
                val a=max(from,hour.toInstant().toEpochMilli());val b=min(until,hour.plusHours(1).toInstant().toEpochMilli())
                val runs=mutableListOf<ReportBasalInterval>()
                display.filter { it.start<b&&it.end>a }.forEach { i->val run=i.copy(start=max(a,i.start),end=min(b,i.end));val last=runs.lastOrNull()
                    if(last!=null&&last.end==run.start&&last.scheduledRate==run.scheduledRate)runs[runs.lastIndex]=last.copy(end=run.end)else runs+=run }
                runs.forEach { run->
                    val box=RectF(max(x(a)+1.1f,x(run.start)),by(run.scheduledRate),min(x(b)-1.1f,x(run.end)),bottom);round(box)
                    if(box.width()>0)display.filter { it.start<run.end&&it.end>run.start&&it.change!=ReportBasalChange.SCHEDULED }.forEach { i->
                        val l=max(box.left,x(max(run.start,i.start)));val r=min(box.right,x(min(run.end,i.end)))
                        if(r>l&&i.change==ReportBasalChange.TEMPORARY){
                            val changed=RectF(l,by(i.programmedRate),r,bottom);round(changed)
                            if(box.height()>0&&changed.height()>0){val clip=c.save();c.clipPath(Path().apply { addRoundRect(box,2f,2f,Path.Direction.CW) });c.clipPath(Path().apply { addRoundRect(changed,2f,2f,Path.Direction.CW) });c.drawColor(Color.argb(28,22,139,211));c.restoreToCount(clip)}
                        }else if(r>l&&box.height()>0){val clip=c.save();c.clipRect(l,box.top,r,bottom);var xx=l-box.height();while(xx<r){line(xx,bottom,xx+box.height(),box.top,blue,.5f);xx+=4};c.restoreToCount(clip)}
                    }
                };hour=hour.plusHours(1)
            }
        }
        val boluses=ReportBolus.read(rows).filter { it.start<until&&(it.end?:it.start)>=from }
        val redMax=max(3.0,ceil(boluses.maxOfOrNull { it.total }?:0.0))
        fun ry(v: Double)=bottom-(v/redMax).toFloat()*height
        boluses.forEach { b->
            if(b.start in from until until)b.immediate?.takeIf { it>0 }?.let { line(x(b.start),bottom,x(b.start),ry(it),red,1.7f) }
            val end=b.end;val amount=b.extended
            if(end!=null&&amount!=null&&amount>0&&end>b.start){
                val initial=b.immediate?:0.0
                val ramp=Path().apply { moveTo(x(b.start),bottom);lineTo(x(b.start),ry(initial));lineTo(x(end),ry(b.total));lineTo(x(end),bottom);close() }
                c.drawPath(ramp,p(red,60))
                c.drawLine(x(b.start),ry(initial),x(end),ry(b.total),p(red,190).apply { strokeWidth=.9f;if(!b.extensionDelivered)pathEffect=DashPathEffect(floatArrayOf(2f,2f),0f) })
                if(x(end)-x(b.start)>15)text(x(end)-8,ry(b.total)-3,String.format(Locale.US,"%.1f U",b.total),red,5.7f)
            }
        }
        c.restoreToCount(saved)
        (0..3).forEach { i->val label=String.format(Locale.US,"%.1f",redMax*i/3);text(left-7-label.length*3.5f,bottom-height*i/3+2,label,red) }
        text(43f,top-7,"U",red)
        if(estimate.intervals.isEmpty()&&!measured)text(left+8,top+12,"Basal unavailable",ReportStyle.GREY)
        val clock=java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        (0..8).forEach { i->val t=from+(until-from)*i/8;text(x(t)-8,bottom+13,clock.format(Instant.ofEpochMilli(t)),ReportStyle.GREY) }
    }
}

internal object InsulinReferencePdf {
    fun draw(c: Canvas, rows: List<ExportRow>, zone: ZoneId, from: Long, until: Long, top: Float, height: Float,
             response: InsulinResponse.Result = InsulinResponse.Result(emptyList(),emptyList(),0)) {
        fun paint(color: Int=Color.BLACK,size: Float=7f)=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color;textSize=size}
        fun text(x: Float,y: Float,s: String,size: Float=7f,color: Int=Color.BLACK)=c.drawText(s,x,y,paint(color,size))
        text(22f,top-24,"ISF / ICR: observed response points",10f)
        text(22f,top-11,"Blue diamonds: response estimates (hollow = provisional) / black circles: recorded pump settings / no interpolation",6.2f)
        val day=response.estimates.filter {it.time in from until until}
        val native=rows.filter {it.timeMillis in from until until&&(it.pumpIsfMgdlPerU!=null||it.pumpIcrGPerU!=null)}
        repeat(2) { panel ->
            val observations=native.mapNotNull {r->(if(panel==0)r.pumpIsfMgdlPerU else r.pumpIcrGPerU)?.let {r.timeMillis to it} }
            val estimates=day.filter {(if(panel==0)it.isf else it.icr)!=null}
            val all=observations.map {it.second}+estimates.mapNotNull {if(panel==0)it.isf else it.icr}
            val l=60f+panel*280;val r=l+210;val bottom=top+height;val maximum=max(1.0,ceil((all.maxOrNull()?:1.0)*1.15))
            fun x(t: Long)=l+(r-l)*((t-from).toDouble()/(until-from)).toFloat()
            fun y(v: Double)=bottom-(v/maximum).toFloat()*(height-12)
            text(l,top-2,if(panel==0)"ISF (mg/dL/U)" else "ICR (g/U)",7f)
            if(all.isNotEmpty())(0..2).forEach {i->val v=maximum*i/2;c.drawLine(l,y(v),r,y(v),paint(ReportStyle.GRID));text(r+3,y(v)+2,String.format(Locale.US,"%.0f",v),5.7f,ReportStyle.GREY)}
            observations.forEach {(t,v)->c.drawCircle(x(t),y(v),1.9f,paint().apply {style=Paint.Style.STROKE;strokeWidth=.6f})}
            // Thin only the drawing, retaining actual observation times and every CSV point.
            val visible=mutableListOf<InsulinResponse.Estimate>()
            estimates.forEach {e->if(visible.isEmpty()||x(e.time)-x(visible.last().time)>=7f)visible+=e}
            estimates.lastOrNull()?.let {last->if(visible.lastOrNull()!=last) {
                if(visible.isNotEmpty()&&x(last.time)-x(visible.last().time)<7f)visible.removeAt(visible.lastIndex)
                visible+=last
            }}
            visible.forEach {e->val xx=x(e.time);val yy=y((if(panel==0)e.isf else e.icr)!!)
                c.drawPath(Path().apply {moveTo(xx,yy-2.5f);lineTo(xx+2.5f,yy);lineTo(xx,yy+2.5f);lineTo(xx-2.5f,yy);close()},paint(ReportStyle.BASAL).apply {if(e.provisional) {style=Paint.Style.STROKE;strokeWidth=.7f}})}
            val last=estimates.lastOrNull()
            if(last!=null)text(l+105,top-2,"Latest %.1f".format(Locale.US,if(panel==0)last.isf else last.icr),6.7f)
            else text(l+105,top-2,"No response estimate",6.2f,ReportStyle.GREY)
            if(all.isEmpty())text(l,top+height/2,"No eligible observations",6.2f,ReportStyle.GREY)
            listOf(0,6,12,18,24).forEach {i->val t=from+(until-from)*i/24;text(x(t)-5,bottom+11,java.time.format.DateTimeFormatter.ofPattern("HH").withZone(zone).format(Instant.ofEpochMilli(t)),6f,ReportStyle.GREY)}
        }
        text(22f,top+height+24,"${day.size} response points; drawing thinned, full series in CSV. Early points are provisional. Model assumptions apply; no setting is changed.",6.1f,ReportStyle.GREY)
    }
}
