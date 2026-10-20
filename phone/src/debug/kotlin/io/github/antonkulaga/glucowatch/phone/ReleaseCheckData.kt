package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.content.ContentUris
import android.provider.MediaStore
import glucowatch.core.*
import java.time.LocalDate
import java.time.ZoneOffset

/** Local deterministic QA, never packaged into release APKs. */
internal object ReleaseCheckData {
    fun verifyFolders(context: Context) {
        ReportFiles.ensureLayout(context)
        val folders=listOf(ReportFiles.raw(ExportPortal.CLARITY),ReportFiles.raw(ExportPortal.CARELINK),ReportFiles.STRAVA,ReportFiles.STRAVA_UNEDITED,ReportFiles.exports("application/pdf"),ReportFiles.exports("text/csv"))
        fun markers(folder: String): List<android.net.Uri> = context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),"relative_path = ? AND _display_name = ? AND owner_package_name = ?",
            arrayOf("Download/$folder/","README.txt",context.packageName),null)!!.use { c->buildList {while(c.moveToNext())add(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,c.getLong(0)))} }
        folders.forEach {check(markers(it).size==1)}
        ReportFiles.ensureLayout(context)
        folders.forEach {check(markers(it).size==1)}
        // Only our own folder note is deleted; original files and reports are untouched.
        context.contentResolver.delete(markers(folders.first()).single(),null,null)
        ReportFiles.ensureLayout(context)
        folders.forEach {check(markers(it).size==1)}
        val uri=ReportFiles.publish(context,"probe.txt","text/plain","directory probe".toByteArray(),"${ReportFiles.ROOT}/directory-check-${java.util.UUID.randomUUID()}/nested")
        check(context.contentResolver.openInputStream(uri)!!.use {it.readBytes().toString(Charsets.UTF_8)}=="directory probe")
        context.contentResolver.delete(uri,null,null)
    }
    fun fixture(): GlucoseReport {
        val zone=ZoneOffset.UTC;val day=LocalDate.of(2026,10,19)
        val start=day.atTime(6,0).toInstant(zone).toEpochMilli();val meal=start+8*InsulinResponse.HOUR
        val events=listOf(ExportRow(start,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS,insulinU=2.0),
            ExportRow(meal,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS,insulinU=6.0),
            ExportRow(meal,ExportRow.SOURCE_CARELINK,ExportRow.KIND_MEAL,carbsG=60.0),
            ExportRow(start,ExportRow.SOURCE_CARELINK,"pump_therapy_settings",pumpIsfMgdlPerU=55.0,pumpIcrGPerU=12.0))
        val readings=(-72..204).map { i->val t=start+i*300_000L
            val mgdl=if(t<meal)250-100*InsulinResponse.effect(i*5.0) else 150.0
            GlucoseReading(t,mgdl.toInt(),Trend.None) }
        val window=ExportWindow(LatestDevice.MINIMED_PUMP,day.minusDays(1),day,day,day,allowIncomplete=true)
        return GlucoseReport.build(readings,listOf(Treatment(start,insulin=2.0),Treatment(meal,insulin=6.0),Treatment(meal,carbs=60.0)),"Synthetic response fixture",zone,window=window,events=events)
    }
}

class LiveAnalysisPreviewActivity : InsulinAnalysisActivity() {
    override suspend fun sample(now: Long): Sample {
        val f=ReleaseCheckData.fixture()
        return Sample(InsulinResponse.analyse(f.events,f.readings),f.readings.last().timeMillis,"Synthetic CGM and pump - not personal data")
    }
}
