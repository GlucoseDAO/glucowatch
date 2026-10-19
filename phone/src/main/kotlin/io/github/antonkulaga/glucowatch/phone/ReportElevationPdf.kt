package io.github.antonkulaga.glucowatch.phone

import android.graphics.*
import glucowatch.core.Workout

/** Recorded altitude, normalized within each activity; never invents elevation for a swim. */
internal object ReportElevationPdf {
    fun draw(c: Canvas, workouts: List<Workout>, from: Long, until: Long, bounds: RectF) {
        fun x(t: Long)=bounds.left+bounds.width()*((t-from).toDouble()/(until-from)).toFloat()
        val saved=c.save();c.clipRect(bounds)
        workouts.filter { it.overlaps(from,until) }.forEach { w->
            val points=w.samples.filter { it.elevationM!=null&&it.timeMillis in from..until }
                .groupBy { it.timeMillis/60_000 }.values.map { list->list[list.size/2] }.sortedBy { it.timeMillis }
            val lo=points.minOfOrNull { it.elevationM!! };val hi=points.maxOfOrNull { it.elevationM!! }
            if(lo!=null&&hi!=null&&hi>lo) {
                val groups=mutableListOf<MutableList<glucowatch.core.WorkoutSample>>()
                points.forEach { p->if(groups.isEmpty()||p.timeMillis-groups.last().last().timeMillis>120_000)groups+=mutableListOf(p)else groups.last()+=p }
                groups.forEach { g->val path=Path();path.moveTo(x(g.first().timeMillis),bounds.bottom)
                    g.forEach { p->path.lineTo(x(p.timeMillis),bounds.bottom-bounds.height()*.85f*((p.elevationM!!-lo)/(hi-lo)).toFloat()) }
                    path.lineTo(x(g.last().timeMillis),bounds.bottom);path.close();c.drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(224,224,224);alpha=170 }) }
            }else c.drawRect(x(maxOf(from,w.startMillis)),bounds.top,x(minOf(until,w.endMillis)),bounds.bottom,Paint().apply { color=Color.rgb(236,236,236) })
        }
        c.restoreToCount(saved)
    }
}
