package io.github.antonkulaga.glucowatch.phone

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import glucowatch.core.ExportPortal
import glucowatch.core.ExportNames
import glucowatch.core.HistoricalCsv
import java.security.MessageDigest

/** One visible layout for source originals and generated output. Never moves browser originals. */
object ReportFiles {
    const val ROOT = "GlucoPhone"
    const val RAW = "$ROOT/Raw"
    fun raw(portal: ExportPortal) = "$RAW/${if (portal == ExportPortal.CLARITY) "Dexcom" else "Medtronic"}"
    const val STRAVA = "$RAW/Strava"
    const val STRAVA_UNEDITED = "$STRAVA/Unedited"
    fun exports(mime: String) = "$ROOT/Exports/${if (mime == "application/pdf") "PDF" else "CSV"}"

    /** MediaStore creates the public directories without broad storage permission. Keep a
     * small useful file in every leaf so even an otherwise empty layout remains visible. */
    @Synchronized
    fun ensureLayout(context: Context) {
        // Portal capture runs in another app process; a JVM monitor alone cannot prevent
        // both processes from inserting the same folder note on first launch.
        java.io.RandomAccessFile(java.io.File(context.filesDir, "report-layout.lock"), "rw").use { file ->
        file.channel.lock().use {
        val folders = listOf(raw(ExportPortal.CLARITY), raw(ExportPortal.CARELINK), STRAVA,
            STRAVA_UNEDITED, exports("application/pdf"), exports("text/csv"))
        val resolver = context.contentResolver
        folders.forEach { folder ->
            val path = "${Environment.DIRECTORY_DOWNLOADS}/$folder/"
            val exists = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?",
                arrayOf(path, "README.txt", context.packageName), null)?.use { cursor ->
                var readable = false
                while (cursor.moveToNext() && !readable) {
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    readable = runCatching { resolver.openFileDescriptor(uri, "r")?.use { true } == true }.getOrDefault(false)
                }
                readable
            } == true
            if (!exists) publish(context, "README.txt", "text/plain",
                ("GlucoPhone: $folder\n" + when (folder) {
                    STRAVA_UNEDITED -> "Original Strava files, with their incoming names and bytes.\n"
                    STRAVA -> "Arranged workout files. Original downloads are in Unedited.\n"
                    else -> if (folder.contains("/Raw/")) "Original provider data for reports.\n" else "Generated reports.\n"
                } + "Managed sensor/pump downloads are saved here automatically. External browsers control their own download location.\n").toByteArray(), folder)
        }
        }
        }
    }

    /** Reorganize only files this app published in the old flat layout, keeping their URIs. */
    fun migrateLegacy(context: Context) {
        val resolver = context.contentResolver
        val root = "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/"
        runCatching {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.RELATIVE_PATH),
                "${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ? AND (${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?)",
                arrayOf(context.packageName, root, "${root}Strava/"), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1).orEmpty()
                    val mime = cursor.getString(2).orEmpty()
                    val path = cursor.getString(3).orEmpty()
                    val target = when {
                        name.startsWith("carelink-history-") && name.endsWith(".csv") -> raw(ExportPortal.CARELINK)
                        name.startsWith("clarity-history-") && name.endsWith(".csv") -> raw(ExportPortal.CLARITY)
                        name.startsWith("glucowatch-") && mime in setOf("application/pdf", "text/csv") -> exports(mime)
                        path == "${root}Strava/" && name.endsWith(".fit") -> STRAVA
                        else -> continue
                    }
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    runCatching { resolver.update(uri, ContentValues().apply {
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$target/")
                    }, null, null) }
                }
            }
        }
    }

    fun publish(context: Context, name: String, mime: String, bytes: ByteArray, folder: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads unavailable")
        try {
            val actual = resolver.query(uri, arrayOf(MediaStore.Downloads.RELATIVE_PATH), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0).trimEnd('/') else null
            }
            require(actual == "${Environment.DIRECTORY_DOWNLOADS}/$folder".trimEnd('/')) {
                "Android did not create the requested download folder: $folder"
            }
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not write download")
            require(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) {
                "Could not publish download"
            }
            return uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    /** Identical imports reuse their public copy; bytes are saved before any CSV filtering. */
    @Synchronized
    fun archiveCsv(context: Context, portal: ExportPortal, bytes: ByteArray, synthetic: Boolean = false,
                   order: HistoricalCsv.DateOrder? = null): Uri =
        java.io.RandomAccessFile(java.io.File(context.filesDir, "report-archive.lock"), "rw").use { file ->
            file.channel.lock().use { archiveCsvLocked(context, portal, bytes, synthetic, order) }
        }

    private fun archiveCsvLocked(context: Context, portal: ExportPortal, bytes: ByteArray, synthetic: Boolean,
                                 order: HistoricalCsv.DateOrder?): Uri {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val provider = if (portal == ExportPortal.CLARITY) "dexcom-cgm" else "medtronic-pump"
        val interval = runCatching {
            val text = bytes.toString(Charsets.UTF_8)
            val dateOrder = order ?: HistoricalCsv.inferDateOrder(text) ?: return@runCatching null
            ExportNames.interval(HistoricalCsv.parse(text, portal.source, java.time.ZoneId.systemDefault(), dateOrder), java.time.ZoneId.systemDefault())
        }.getOrNull() ?: "dates-unresolved"
        val name = "${if (synthetic) "synthetic-" else ""}${interval}-$provider-${digest.take(12)}.csv"
        // SharedPreferences caches are process-local. Look up the actual owned archive
        // while holding a cross-process file lock, then verify its full content digest.
        val resolver = context.contentResolver
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/${raw(portal)}/", "%${digest.take(12)}%.csv", context.packageName), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1).startsWith("synthetic-") != synthetic) continue
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                val same = runCatching { resolver.openInputStream(uri)?.use read@ { input ->
                    val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);var total=0
                    while(true) {val count=input.read(buffer);if(count<0)break;total+=count
                        if(total>HistoricalCsv.MAX_BYTES)return@read false
                        hash.update(buffer,0,count)
                    }
                    hash.digest().joinToString("") {"%02x".format(it)}==digest
                }==true }.getOrDefault(false)
                if(same) {
                    if(interval!="dates-unresolved")resolver.update(uri,ContentValues().apply {put(MediaStore.Downloads.DISPLAY_NAME,name)},null,null)
                    return uri
                }
            }
        }
        return publish(context,name,"text/csv",bytes,raw(portal))
    }

    @Synchronized
    fun archiveStravaOriginal(context: Context, originalName: String, bytes: ByteArray): Uri {
        val name = originalName.substringAfterLast('/').substringAfterLast('\\').replace('\u0000', '_')
            .take(180).ifBlank { "Workout.fit" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // Preserve aliases too: same bytes downloaded under another name still have an original copy.
        val key = "strava-original:$name:$digest"
        val prefs = context.getSharedPreferences("raw-downloads", Context.MODE_PRIVATE)
        prefs.getString(key, null)?.let { saved ->
            val uri = Uri.parse(saved)
            if (runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true }.getOrDefault(false)) return uri
        }
        return publish(context, name, "application/octet-stream", bytes, STRAVA_UNEDITED).also {
            prefs.edit().putString(key, it.toString()).apply()
        }
    }
}
