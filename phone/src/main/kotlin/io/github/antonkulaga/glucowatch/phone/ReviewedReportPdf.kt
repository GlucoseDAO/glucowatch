package io.github.antonkulaga.glucowatch.phone

import android.graphics.*
import android.graphics.pdf.PdfDocument
import glucowatch.core.*
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

/** Shared whole-day and activity layout reviewed against the real-data PDF sample. */
internal object ReviewedReportPdf {
    fun render(report: GlucoseReport, workouts: List<Workout>, boundaries: List<Int>, generated: Long,
               paceBoundaries: List<Int> = WorkoutAnalysis.defaultPaceBoundaries): ByteArray {
        val zone=ZoneId.of(report.zoneId)
        val first=report.days.minOf { it.date }.atStartOfDay(zone).toInstant().toEpochMilli()
        val until=report.days.maxOf { it.date }.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val selected=workouts.filter { it.overlaps(first,until) }.distinctBy { it.id }.sortedByDescending { it.startMillis }
        val pages=mutableListOf<(Canvas)->Unit>()
        val response=InsulinResponse.analyse(report.events,report.readings)
        report.days.sortedByDescending { it.date }.forEach { day ->
            val a=day.date.atStartOfDay(zone).toInstant().toEpochMilli()
            val b=minOf(day.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),generated)
            val activities=selected.filter { it.overlaps(a,b) }
            val detail=if(activities.isEmpty())0 else pages.size+2
            pages += { c -> Painter(c,zone).day(day.date,a,b,report.readings,report.events,activities,detail,response) }
            activities.forEach { w -> pages += { c -> Painter(c,zone).activity(w,report.readings,report.events,boundaries,
                maxOf(a,w.startMillis),minOf(b-1,w.endMillis),paceBoundaries) } }
        }
        val pdf=PdfDocument()
        return try {
            pages.forEachIndexed { i,draw ->
                val page=pdf.startPage(PdfDocument.PageInfo.Builder(612,792,i+1).create())
                try {
                    page.canvas.drawColor(Color.WHITE);draw(page.canvas)
                    val p=Painter(page.canvas,zone)
                    p.line(22f,751f,590f,751f,ReportStyle.GREY)
                    p.text(22f,764f,"GlucoPhone / ${zone.id} / ${DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(zone).format(Instant.ofEpochMilli(generated))}",7f,color=ReportStyle.GREY)
                    p.text(590f,764f,"${i+1} of ${pages.size}",7f,color=ReportStyle.GREY,right=true)
                    p.text(22f,778f,"Calculated insulin = rate-time basal estimate + recorded bolus; not independently measured delivery.",7f,color=ReportStyle.GREY)
                } finally { pdf.finishPage(page) }
            }
            ByteArrayOutputStream().also(pdf::writeTo).toByteArray()
        } finally { pdf.close() }
    }

    fun activityPages(w: Workout, glucose: List<GlucoseReading>, rows: List<ExportRow>, zone: ZoneId,
                      boundaries: List<Int>, from: Long, until: Long, paceBoundaries: List<Int> = WorkoutAnalysis.defaultPaceBoundaries): List<(Canvas)->Unit> {
        val a=maxOf(from,w.startMillis);val b=minOf(until,w.endMillis)
        return if(b<=a)emptyList()else listOf { c -> Painter(c,zone).activity(w,glucose,rows,boundaries,a,b,paceBoundaries) }
    }

    private class Painter(val c: Canvas,val zone: ZoneId) {
        val grey=ReportStyle.GREY;val blue=ReportStyle.BASAL;val red=ReportStyle.BOLUS
        val elevation=Color.rgb(229,229,229);val heart=Color.rgb(201,103,103);val pace=Color.rgb(114,152,198)
        fun paint(size: Float=8f,bold: Boolean=false,color: Int=Color.BLACK,alpha: Int=255)=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize=size;this.color=color;this.alpha=alpha;typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
        }
        fun text(x: Float,y: Float,value: String,size: Float=9f,bold: Boolean=false,color: Int=Color.BLACK,right: Boolean=false) {
            val p=paint(size,bold,color);c.drawText(value,if(right)x-p.measureText(value)else x,y,p)
        }
        fun line(x1: Float,y1: Float,x2: Float,y2: Float,color: Int=ReportStyle.GRID,width: Float=.5f,dash: Boolean=false) {
            c.drawLine(x1,y1,x2,y2,paint(color=color).apply { strokeWidth=width;if(dash)pathEffect=DashPathEffect(floatArrayOf(3f,2f),0f) })
        }
        fun rounded(box: RectF,color: Int,alpha: Int=255,radius: Float=2f) { if(box.width()>0&&box.height()>0)c.drawRoundRect(box,radius,radius,paint(color=color,alpha=alpha)) }
        fun paragraph(x: Float,y: Float,value: String,width: Float=568f,size: Float=7.2f,color: Int=ReportStyle.GREY) {
            val p=paint(size,color=color);var at=y+size
            value.split('\n').forEach { part ->
                var row=""
                part.split(' ').forEach { word ->
                    val next=if(row.isEmpty())word else "$row $word"
                    if(p.measureText(next)>width&&row.isNotEmpty()){c.drawText(row,x,at,p);at+=size*1.35f;row=word}else row=next
                }
                c.drawText(row,x,at,p);at+=size*1.35f
            }
        }
        fun fmt(v: Double?,n: Int=1)=v?.takeIf { it.isFinite() }?.let { String.format(Locale.US,"%.${n}f",it) }?:"--"
        fun units(v: Double?)=v?.let { BigDecimal.valueOf(it).setScale(9,RoundingMode.HALF_UP).setScale(2,RoundingMode.HALF_UP).toPlainString() }?:"--"
        fun clock(t: Long)=DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(t))
        fun duration(ms: Long)="${ms/3_600_000}h ${(ms%3_600_000)/60_000}m"
        fun title(value: String,subtitle: String,from: Long) {
            text(22f,43f,fit(value,410f,18f),18f,true)
            text(22f,59f,DateTimeFormatter.ofPattern("EEEE d MMMM yyyy").withZone(zone).format(Instant.ofEpochMilli(from)),8f,true)
            text(590f,35f,"GlucoPhone report",8f,right=true);text(590f,48f,"Private health data",7f,color=grey,right=true)
            text(22f,78f,subtitle,9f,true)
        }
        fun fit(value: String,width: Float,size: Float): String {
            val p=paint(size);if(p.measureText(value)<=width)return value
            var end=value.length;while(end>0&&p.measureText(value.take(end)+"...")>width)end--
            return value.take(end)+"..."
        }
        fun section(y: Float,label: String){text(22f,y,label,12f,true);line(22f,y+7,590f,y+7,grey,.7f)}

        fun summary(readings: List<GlucoseReading>,rows: List<ExportRow>,from: Long,until: Long,activities: List<Workout>,active: Boolean): ReportBasalEstimate {
            val values=readings.filter { it.timeMillis in from until until }.map { it.mgdl.toDouble() }
            val selected=rows.filter { it.timeMillis in from until until }.distinctBy { it.identity() }
            section(98f,"Glucose");text(22f,119f,"Average glucose",10f,true)
            text(22f,163f,fmt(values.takeIf { it.isNotEmpty() }?.average(),0),48f,true);text(117f,161f,"mg/dL",9f)
            text(22f,194f,"Standard deviation",9f,true)
            val mean=values.takeIf { it.isNotEmpty() }?.average()
            val sd=if(values.size>1)sqrt(values.sumOf { (it-mean!!).pow(2) }/(values.size-1))else null
            text(22f,215f,fmt(sd),18f,true);text(78f,214f,"mg/dL",8f)
            text(235f,119f,"Time in Range",10f,true)
            val counts=values.fold(RangeCounts()){a,v->a+RangeCounts.of(v.toInt())}
            val bins=listOf(counts.veryHigh,counts.high,counts.inRange,counts.low,counts.veryLow)
            val colors=listOf(ReportStyle.ORANGE,ReportStyle.YELLOW,ReportStyle.GREEN,ReportStyle.LOW,ReportStyle.VERY_LOW)
            val labels=listOf("Very High","High","In Range","Low","Very Low");var y=131f
            bins.forEachIndexed { i,n ->val h=if(values.isEmpty())0f else 80f*n/values.size;c.drawRect(235f,y,258f,y+h,paint(color=colors[i]));y+=h
                text(267f,141f+i*16,"${if(values.isEmpty())"--"else fmt(100.0*n/values.size)}% ${labels[i]}",8.6f,i==2) }
            text(235f,226f,"Reference: 70-180 mg/dL",8f)
            val date=Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
            val dayLength=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()-date.atStartOfDay(zone).toInstant().toEpochMilli()
            val clipped=activities.map { maxOf(from,it.startMillis) to minOf(until,it.endMillis) }.filter { it.second>it.first }.sortedBy { it.first }
            var covered=0L;var end=Long.MIN_VALUE
            clipped.forEach { (a,b)->covered+=(b-maxOf(a,end)).coerceAtLeast(0);end=maxOf(end,b) }
            text(449f,119f,if(active)"Activity duration"else"Activity",10f,true)
            text(449f,153f,if(active)duration(until-from)else"${fmt(100.0*covered/dayLength)}%",if(active)24f else 29f,true)
            text(449f,177f,if(active)"${fmt(100.0*(until-from)/dayLength)}% of the day"else"of the day",9f)
            text(449f,199f,if(active)"${values.size} glucose readings"else if(activities.isEmpty())"No imported activity"else"${activities.first().sport} / ${duration(covered)}",8.5f)
            text(449f,215f,if(active)"During the activity"else activities.firstOrNull()?.let { "${clock(it.startMillis)} - ${clock(it.endMillis)}" }.orEmpty(),8.5f,color=grey)
            section(246f,"Insulin")
            val estimate=ReportBasalEstimate.build(rows,zone,from,until)
            val doses=selected.filter { it.kind==ExportRow.KIND_BASAL_DELIVERY&&it.insulinU!=null }
            val basal=if(doses.isNotEmpty())doses.sumOf { it.insulinU!! }else estimate.units
            val bolus=selected.filter { it.kind==ExportRow.KIND_BOLUS }.sumOf { it.insulinU?:0.0 }
            rounded(RectF(22f,264f,43f,287f),red);text(54f,273f,"Bolus - delivered",9f,true);text(54f,289f,"${units(bolus)} U recorded",10f)
            rounded(RectF(22f,300f,43f,323f),blue,128)
            text(54f,307f,if(doses.isNotEmpty())"Basal - recorded"else"Basal - calculated",9f,true)
            text(54f,322f,"${units(basal)} U${if(doses.isEmpty())" from rates x time"else" recorded"}",9f)
            text(278f,273f,if(doses.isNotEmpty())"Recorded total"else"Calculated total",9f,true)
            text(278f,297f,"${units(basal?.plus(bolus))} U",24f,true)
            text(278f,316f,if(basal==null)"No basal rates or doses supplied"else if(!estimate.complete&&doses.isEmpty())"Partial rate-time coverage"else"Basal + recorded bolus",8f)
            text(453f,273f,"Logged carbs",9f,true);text(453f,296f,"${fmt(selected.sumOf { it.carbsG?:0.0 },0)} g",20f,true)
            return estimate
        }

        inner class Plot(val from: Long,val until: Long,val top: Float,val height: Float,val low: Double,val high: Double,val right: Float=490f) {
            val left=60f;val bottom=top+height
            fun x(t: Long)=left+(right-left)*((t-from).toDouble()/(until-from).coerceAtLeast(1)).toFloat()
            fun y(v: Double)=bottom-((v-low)/(high-low)).toFloat()*height
            fun grid(ticks: List<Double>,color: Int=grey,format: (Double)->String={fmt(it,0)}) {
                ticks.forEach { v->line(left,y(v),right,y(v));text(right+4,y(v)+2,format(v),7f,color=color) }
                line(left,top,right,top,grey);line(left,bottom,right,bottom,grey)
            }
            fun labels(unit: String="") {
                val step=if(until-from>12*3_600_000L)3 else 1
                var t=Instant.ofEpochMilli(from).atZone(zone).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
                while(t<=until){if(t>=from)text(x(t)-10,bottom+14,clock(t),7f,color=grey);t+=step*3_600_000}
                text(right+32,bottom+14,unit,7f,color=grey)
            }
            fun trace(groups: List<List<WorkoutPoint>>,color: Int=Color.BLACK,alpha: Int=255,fill: Boolean=false) {
                val saved=c.save();c.clipRect(left,top,right,bottom)
                groups.forEach { group ->
                    val points=group.filter { it.time in from..until };if(points.isEmpty())return@forEach
                    val path=Path();path.moveTo(x(points.first().time),if(fill)bottom else y(points.first().value))
                    points.forEach { path.lineTo(x(it.time),y(it.value)) }
                    if(fill){path.lineTo(x(points.last().time),bottom);path.close()}
                    c.drawPath(path,paint(color=color,alpha=alpha).apply { style=if(fill)Paint.Style.FILL else Paint.Style.STROKE;strokeWidth=.8f })
                };c.restoreToCount(saved)
            }
            fun elevation(groups: List<List<WorkoutPoint>>) {
                val points=groups.flatten();val min=points.minOfOrNull { it.value }?:return;val max=points.maxOf { it.value };if(max<=min)return
                trace(groups.map { g->g.map { it.copy(value=low+(it.value-min)/(max-min)*(high-low)) } },elevation,fill=true)
            }
        }

        fun glucose(from: Long,until: Long,top: Float,height: Float,readings: List<GlucoseReading>,activities: List<Workout>,right: Float=490f) {
            text(22f,top-13,"Glucose",10f,true);val p=Plot(from,until,top,height,40.0,400.0,right)
            activities.forEach { w->
                val background=WorkoutAnalysis.analyse(w).elevation
                if(background.isNotEmpty())p.elevation(background)
                else if(w.overlaps(from,until))c.drawRect(p.x(maxOf(from,w.startMillis)),top,p.x(minOf(until,w.endMillis)),p.bottom,paint(color=elevation,alpha=110))
            }
            p.grid(if(height>=80)(50..400 step 50).map(Int::toDouble)else listOf(70.0,180.0,300.0,400.0))
            if(right==490f)activities.forEach { w->
                val a=p.x(maxOf(from,w.startMillis));val b=p.x(minOf(until,w.endMillis));c.drawRect(a,top,b,p.bottom,paint(color=grey,alpha=22));line(a,top,a,p.bottom,grey,dash=true);line(b,top,b,p.bottom,grey,dash=true)
            }
            listOf(70.0 to ReportStyle.LOW_RULE,180.0 to ReportStyle.HIGH_RULE).forEach { (v,color)->
                line(60f,p.y(v),right,p.y(v),color,1f);rounded(RectF(30f,p.y(v)-5,59f,p.y(v)+5),color,radius=5f);text(37f,p.y(v)+2.5f,fmt(v,0),7f,true,Color.WHITE)
            }
            val groups=mutableListOf<MutableList<WorkoutPoint>>()
            readings.filter { it.timeMillis in from..until }.sortedBy { it.timeMillis }.forEach { r->
                if(groups.isEmpty()||r.timeMillis-groups.last().last().time>900_000)groups.add(mutableListOf())
                groups.last()+=WorkoutPoint(r.timeMillis,r.mgdl.toDouble())
            };p.trace(groups);p.labels("mg/dL")
        }

        fun insulin(from: Long,until: Long,top: Float,height: Float,rows: List<ExportRow>,estimate: ReportBasalEstimate,right: Float=490f,activities: List<Workout> = emptyList()) {
            ReportInsulinPdf.draw(c, zone, from, until, top, height, rows, estimate, right,activities)
        }

        fun day(date: LocalDate,from: Long,until: Long,glucose: List<GlucoseReading>,rows: List<ExportRow>,activities: List<Workout>,detailPage: Int,response: InsulinResponse.Result) {
            title("Whole day","Sensor, pump and activity / $date",from)
            val estimate=summary(glucose,rows,from,until,activities,false)
            if(activities.isNotEmpty()){
                rounded(RectF(22f,342f,590f,367f),Color.rgb(237,240,238),radius=4f)
                text(34f,358f,"${if (activities.size == 1) activities.first().sport else "${activities.size} activities"} / ${clock(activities.minOf { it.startMillis })} - ${clock(activities.maxOf { it.endMillis })} / shaded below",9f,true)
                text(578f,358f,"Activity detail on page $detailPage",9f,true,right=true)
            }
            glucose(from,until,389f,95f,glucose,activities)
            insulin(from,until,540f,80f,rows,estimate,activities=activities)
            InsulinReferencePdf.draw(c,rows,zone,from,until,665f,33f,response)
            paragraph(22f,729f,if(activities.any {it.pumpDisconnected})"Pump disconnected during swimming (user-reported). FIT session bounds approximate timing; calculated basal reaching the user is zero during that interval. Original pump records are retained." else fit(estimate.assumptions.joinToString(" ").ifBlank { "No basal rates supplied; recorded doses remain separate." },1050f,7f),size=7f)
        }

        fun profile(from: Long,until: Long,top: Float,title: String,groups: List<List<WorkoutPoint>>,background: List<List<WorkoutPoint>>,low: Double,high: Double,ticks: List<Double>,color: Int,unit: String): Plot {
            text(22f,top-13,title,10f,true);val p=Plot(from,until,top,63f,low,high,389f)
            p.elevation(background);p.trace(groups,color,96,true);p.trace(groups,color,210)
            p.grid(ticks,color){if(unit.startsWith("min/"))if(it==30.0)"30+"else paceText(it)else fmt(it,0)}
            text(393f,top-5,unit,7f,color=color);p.labels();return p
        }
        fun zones(top: Float,values: List<Double>,labels: List<String>,colors: List<Int>,title: String) {
            text(443f,top-13,title,9f,true);val total=values.sum();val max=values.maxOrNull()?:0.0
            if(total==0.0){text(443f,top+8,"No observed zone time",7f,color=grey);return}
            values.indices.reversed().forEachIndexed { row,i->
                val y=top+4+row*if(values.size==5)12f else 10.5f
                text(443f,y,"Z${i+1}",6.8f,true)
                val width=(41*values[i]/max).toFloat();rounded(RectF(460f,y-5,460f+width,y),colors[i.coerceAtMost(colors.lastIndex)],radius=min(1.7f,width/2))
                val pct=100*values[i]/total;text(527f,y,if(pct>0&&pct<.05)"<0.1%"else"${fmt(pct)}%",6.6f,right=true)
                text(590f,y,labels[i],6.6f,color=grey,right=true)
            }
        }
        fun activity(workout: Workout,glucose: List<GlucoseReading>,rows: List<ExportRow>,boundaries: List<Int>,from: Long,until: Long,paceBoundaries: List<Int>) {
            val a=WorkoutAnalysis.analyse(workout,from,until,boundaries,paceBoundaries)
            title("${workout.name} / ${workout.sport}","Activity detail / ${clock(from)} - ${clock(until)} local time${if(workout.pumpDisconnected)" / Pump disconnected (user-reported)"else""}",from)
            val estimate=summary(glucose,rows,from,until+1,listOf(workout),true)
            val full=from==workout.startMillis&&until==workout.endMillis
            val samples=workout.samples.filter { it.timeMillis in from..until }
            val distance=if(full)workout.distanceM else samples.mapNotNull { it.distanceM }.let { if(it.size<2)null else it.last()-it.first() }
            val seconds=if(full)workout.timerSeconds?:((until-from)/1000.0)else(until-from)/1000.0
            val ascent=if(full)workout.ascentM else null;val descent=if(full)workout.descentM else null
            val elapsed=(until-from)/1000
            val metrics=listOf("Distance" to "${fmt(distance?.div(1000),2)} km","Elapsed time" to "${elapsed/3600}:${(elapsed%3600/60).toString().padStart(2,'0')}:${(elapsed%60).toString().padStart(2,'0')}",if(workout.isSwim)"Pool / lengths" to "${fmt(workout.poolLengthM,0)} m / ${workout.lengths.count { it.active }}" else "Ascent / descent" to "+${fmt(ascent,0)} / -${fmt(descent,0)} m","Mean speed" to "${fmt(distance?.times(3.6)?.div(seconds),2)} km/h","Mean pace" to "${paceText(distance?.takeIf { it>0 }?.let { seconds/it*(if(workout.isSwim)100 else 1000)/60 })} ${if(workout.isSwim)"/100m"else"/km"}")
            metrics.forEachIndexed { i,(name,value)->
                val x=22f+i*568f/5
                text(x,331f,fit(name,106f,8f),8f,true)
                val requested=if(i==2)10f else 13f
                val size=min(requested,requested*106f/paint(requested,true).measureText(value).coerceAtLeast(1f))
                text(x,349f,value,size,true)
            }
            val hp=profile(from,until,378f,"Heart rate",a.heart,a.elevation,70.0,190.0,listOf(80.0,130.0,180.0),heart,"bpm")
            if(a.heart.isEmpty())text(74f,407f,"Heart rate unavailable",8f,color=grey)
            a.heartGaps.forEach { (lo,hi)->line(hp.x(lo),378f,hp.x(lo),441f,grey,dash=true);line(hp.x(hi),378f,hp.x(hi),441f,grey,dash=true);if(hp.x(hi)-hp.x(lo)>20)text((hp.x(lo)+hp.x(hi))/2-10,388f,"HR gap",6f,color=grey)}
            if(boundaries.size==4)zones(378f,a.heartZones,listOf("<${boundaries[0]}","${boundaries[0]}-${boundaries[1]-1}","${boundaries[1]}-${boundaries[2]-1}","${boundaries[2]}-${boundaries[3]-1}","${boundaries[3]}+"),listOf(0xFFE8AAAA.toInt(),0xFFD67B7B.toInt(),0xFFD35450.toInt(),0xFFC63C34.toInt(),0xFFA82626.toInt()),"Heart-rate zones / bpm")
            else text(443f,385f,"Heart-rate zones disabled",8f)
            text(443f,447f,"Avg ${fmt(a.heartMean,0)} / Max ${fmt(a.heartMax,0)} bpm",7f,true,heart)
            if (a.gap.isNotEmpty()) {
                profile(from,until,480f,"Estimated GAP",a.gap,a.elevation,33.0,3.0,listOf(5.0,10.0,20.0,30.0),pace,"min/km")
                fun boundary(i: Int)="${paceBoundaries[i]/60}:${(paceBoundaries[i]%60).toString().padStart(2,'0')}"
                val paceLabels=listOf("${boundary(4)}+")+(3 downTo 0).map { "${boundary(it)}-${boundary(it+1)}" }+"<${boundary(0)}"
                zones(480f,a.gapZones,paceLabels,listOf(0xFFC6D8EF.toInt(),0xFFA5C1E4.toInt(),0xFF7FA5D5.toInt(),0xFF537FC0.toInt(),0xFF3561A7.toInt(),0xFF234C91.toInt()),"GAP zones / min/km")
                text(443f,549f,"Mean GAP ${paceText(a.gapMean)} / Minetti",6.8f,true,pace)
            } else {
                val observed = a.motion.flatten().map { it.value }
                val lo = maxOf(0.0, floor(observed.minOrNull() ?: 0.0))
                val hi = maxOf(lo + 1, ceil(observed.maxOrNull() ?: if(workout.isSwim)5.0 else 10.0))
                val ticks = (0..3).map { lo + (hi-lo)*it/3 }
                val paceAxis = a.motionUnit.startsWith("min/")
                profile(from,until,480f,a.motionTitle,a.motion,a.elevation,if(paceAxis)hi else lo,if(paceAxis)lo else hi,ticks,pace,a.motionUnit)
                if(observed.isEmpty())text(74f,510f,"No motion observations supplied",8f,color=grey)
                paragraph(443f,484f,if(workout.isSwim)"Native pool lengths or recorded speed. Swim pace is per 100 m. Running pace zones do not apply." else "Recorded motion only. Running GAP zones are not applied without an estimated GAP series.",147f)
                text(443f,549f,"Mean ${if(paceAxis)paceText(a.motionMean)else fmt(a.motionMean)} ${a.motionUnit}",6.8f,true,pace)
            }
            glucose(from,until,575f,60f,glucose,listOf(workout),389f)
            text(443f,562f,"During the activity",9f,true)
            paragraph(443f,576f,if(a.gap.isNotEmpty())"Grey = recorded elevation. GAP: 100 m grade and 30 s distance changes." else "Missing observations remain blank. Grey elevation appears only when recorded.",147f)
            paragraph(443f,611f,"Observed HR: ${duration((a.heartZones.sum()*1000).toLong())}\n${if(workout.excludedSampleCount>0)"${workout.excludedSampleCount} observations outside the session omitted." else "Real HR gaps remain blank."}",147f)
            insulin(from,until,678f,45f,rows,estimate,389f,listOf(workout))
            text(443f,661f,"Calculated insulin",9f,true)
            paragraph(443f,675f,if(workout.pumpDisconnected)"Pump disconnected (user-reported). FIT bounds approximate timing. Estimated basal reaching user: ${units(estimate.units)} U."else"${units(estimate.units)} U rate-time basal. Blue = held setting; temporary changes and suspensions modify the estimate.",147f)
            paragraph(22f,741f,if(a.gap.isNotEmpty())"GAP: Minetti et al. (2002), doi:10.1152/japplphysiol.01177.2001. Grade +/-45%; 1-min chart medians; display cap 30+. Different from Strava." else "Activity interval and observations come from the original FIT. No running GAP model is applied to swims, cycling or other sports.",size=6.3f)
        }
        fun paceText(value: Double?): String=value?.takeIf { it.isFinite() }?.let { val seconds=(it*60).roundToInt();"${seconds/60}:${(seconds%60).toString().padStart(2,'0')}" }?:"--"
    }
}
