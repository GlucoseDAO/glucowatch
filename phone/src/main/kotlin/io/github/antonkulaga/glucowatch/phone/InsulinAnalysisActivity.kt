package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.view.View
import android.view.WindowInsets
import android.widget.*
import glucowatch.core.*
import kotlinx.coroutines.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Recomputes from this account's live caches, not the last imported report or pump settings. */
open class InsulinAnalysisActivity : Activity() {
    protected data class Sample(val response: InsulinResponse.Result, val sensorTime: Long?, val source: String)
    private val scope=MainScope()
    private var updates: Job?=null
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Brand.BACKGROUND) }
        root.addView(Button(this).apply { text="Back"; Brand.style(this,false); setOnClickListener { finish() } })
        status=TextView(this).apply { setTextColor(Brand.MUTED); setPadding(24,12,24,12); text="Reading live sensor and pump history..." }
        root.addView(status)
        root.addView(Button(this).apply { text="Refresh response points"; Brand.style(this,true); setOnClickListener { startUpdates() } })
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,8,24,24) }
        root.addView(ScrollView(this).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f))
        root.setOnApplyWindowInsetsListener { view,insets ->
            if(android.os.Build.VERSION.SDK_INT>=30) { val b=insets.getInsets(WindowInsets.Type.systemBars());view.setPadding(b.left,b.top,b.right,b.bottom) }
            else { @Suppress("DEPRECATION") view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom) };insets
        }
        setContentView(root)
    }
    override fun onStart() { super.onStart();startUpdates() }
    override fun onStop() { updates?.cancel();super.onStop() }
    override fun onDestroy() { scope.cancel();super.onDestroy() }
    private fun startUpdates() {
        updates?.cancel()
        updates=scope.launch {
            while(isActive) {
                try { val now=System.currentTimeMillis(); val data=sample(now);show(data,now) }
                catch(e: CancellationException) {throw e}
                catch(e: Exception) { status.text="Could not refresh response points: ${e.message}" }
                delay(60_000)
            }
        }
    }
    protected open suspend fun sample(now: Long): Sample {
        val settings=PhoneSettingsStore(this).load()
        val repository=PhoneRepository(this)
        repository.refresh(maxAgeMs=60_000)
        val today=LocalDate.now();val zone=ZoneId.systemDefault()
        val window=ExportWindow(LatestDevice.MINIMED_PUMP,today.minusDays(14),today.minusDays(14),today,today.minusDays(14),allowIncomplete=true)
        val tables=repository.exportTables(window)
        val response=withContext(Dispatchers.Default) {
            val workouts=WorkoutStore(this@InsulinAnalysisActivity).inWindow(window,zone)
            InsulinResponse.analyse(WorkoutAnnotations.add(tables.rows,workouts),tables.glucose,now)
        }
        check(PhoneSettingsStore(this).load().configurationKey==settings.configurationKey) { "Source changed; refreshing the selected account." }
        return Sample(response,tables.glucose.maxOfOrNull {it.timeMillis},tables.glucoseSource)
    }
    private fun show(data: Sample,now: Long) {
        val zone=ZoneId.systemDefault()
        fun stamp(t: Long)=DateTimeFormatter.ofPattern("dd MMM HH:mm",Locale.ENGLISH).format(Instant.ofEpochMilli(t).atZone(zone))
        val age=data.sensorTime?.let {(now-it)/60_000}
        status.text="Updated ${stamp(now)}. ${data.source}. "+(data.sensorTime?.let {"Latest CGM ${stamp(it)} (${age} min ago)."}?:"No cached CGM.")+" Refreshes every minute while open."
        body.removeAllViews()
        fun text(s: String,size: Float=15f,muted: Boolean=false) {body.addView(TextView(this).apply {text=s;textSize=size;setTextColor(if(muted)Brand.MUTED else Brand.TEXT);setPadding(0,10,0,10)})}
        text("Observed insulin response",24f)
        text("Calculated from CGM change, confirmed pump insulin and logged food. Points are dated observations; they are not an hourly schedule.",muted=true)
        val points=data.response.estimates
        for(isf in listOf(true,false)) {
            val point=points.lastOrNull {if(isf)it.isf!=null else it.icr!=null}
            val name=if(isf)"ISF" else "ICR";val unit=if(isf)"mg/dL/U" else "g/U"
            if(point==null)text("$name: waiting for an eligible response window",19f)
            else {
                text("$name: %.1f %s".format(Locale.US,if(isf)point.isf else point.icr,unit),22f)
                text("Observed ${stamp(point.time)}; window ${stamp(point.windowStart)} to ${stamp(point.time)} (${point.minutes} min). "+
                    (if(point.provisional)"Provisional: response is still evolving." else "Model window complete; remains a conditional estimate.")+" Method: ${point.method.replace('_',' ')}.",muted=true)
            }
        }
        if(points.isEmpty())text(data.response.noPointsReason?:"Waiting for observations.",muted=true)
        else {
            val end=points.maxOf {it.time};val from=end-InsulinAnalytics.DAY
            body.addView(InsulinResponseChart(this,points.filter {it.time>=from},from,end),LinearLayout.LayoutParams(-1,(320*resources.displayMetrics.density).toInt()))
            text("Hollow points are provisional. Drawing thins close points; values retain their actual observation times.",13f,true)
            text("Recent observation points",18f)
            points.takeLast(12).reversed().forEach { p->text("${stamp(p.time)}  "+if(p.isf!=null)"ISF %.1f mg/dL/U".format(Locale.US,p.isf) else "ICR %.1f g/U".format(Locale.US,p.icr),14f) }
        }
        text("ISF uses isolated correction windows from 90 minutes. ICR uses meal windows from 3 hours, with an independently observed or recorded ISF; near starting glucose it can use carbs / effective insulin. Overlapping food, exercise, basal changes, unconfirmed doses and CGM gaps exclude points.",muted=true)
        text("Assumptions: 6-hour triangular insulin action, neutral basal and complete food logs. Meal estimates also assume the logged food has appeared. This is retrospective analysis updated with incoming readings, not measured absorption or a dose recommendation. Recorded pump settings stay separate in exports.",muted=true)
    }
}

/** Scatter only: never joins sparse estimates into a continuous ISF/ICR curve. */
internal class InsulinResponseChart(context: android.content.Context,private val points: List<InsulinResponse.Estimate>,private val from: Long,private val until: Long): View(context) {
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val density=resources.displayMetrics.density
        fun paint(color: Int,size: Float=11f)=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color;textSize=size*density}
        repeat(2) { panel->
            val selected=points.filter {if(panel==0)it.isf!=null else it.icr!=null}
            val top=height*panel/2f+36*density;val bottom=height*(panel+1)/2f-26*density;val left=42*density;val right=width-14*density
            val max=selected.maxOfOrNull {if(panel==0)it.isf!! else it.icr!!}?.times(1.15)?:1.0
            c.drawText(if(panel==0)"ISF (mg/dL/U)" else "ICR (g/U)",left,top-6*density,paint(Brand.TEXT))
            for(i in 0..2) {val y=bottom-(bottom-top)*i/2;c.drawLine(left,y,right,y,paint(Brand.SURFACE));c.drawText("%.0f".format(Locale.US,max*i/2),0f,y,paint(Brand.MUTED,9f))}
            fun x(t: Long)=left+(right-left)*((t-from).toDouble()/(until-from)).toFloat()
            val visible=mutableListOf<InsulinResponse.Estimate>()
            selected.forEach {p->if(visible.isEmpty()||x(p.time)-x(visible.last().time)>=10*density)visible+=p}
            selected.lastOrNull()?.let {last->if(visible.lastOrNull()!=last) {
                if(visible.isNotEmpty()&&x(last.time)-x(visible.last().time)<10*density)visible.removeAt(visible.lastIndex)
                visible+=last
            }}
            visible.forEach { p->val v=if(panel==0)p.isf!! else p.icr!!
                c.drawCircle(x(p.time),bottom-(bottom-top)*(v/max).toFloat(),2f*density,paint(Brand.TEXT).apply {if(p.provisional) {style=Paint.Style.STROKE;strokeWidth=density}}) }
            for(i in 0..4) {val t=from+(until-from)*i/4;val label=DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))
                val p=paint(Brand.MUTED,9f);c.drawText(label,(x(t)-p.measureText(label)/2).coerceIn(0f,width-p.measureText(label)),bottom+16*density,p)}
        }
    }
}
