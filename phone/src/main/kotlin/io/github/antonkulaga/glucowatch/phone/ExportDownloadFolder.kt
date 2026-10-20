package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import glucowatch.core.HistoricalCsv

/** Reads only the folder explicitly granted through Android's document picker. */
class ExportDownloadFolder(private val context: Context) {
    data class Csv(val uri: Uri, val size: Long, val modified: Long) {
        val fingerprint get() = "$uri|$size|$modified"
    }
    private val preferences = context.getSharedPreferences("export-download-folder", Context.MODE_PRIVATE)
    fun tree(): Uri? = preferences.getString("uri", null)?.let(Uri::parse)?.takeIf { uri ->
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }
    fun remember(uri: Uri, flags: Int) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DocumentsContract.isTreeUri(uri) &&
            uri.authority == "com.android.externalstorage.documents") { "Choose a folder in device storage." }
        val previous = tree()
        context.contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        preferences.edit().putString("uri", uri.toString()).apply()
        if (previous != null && previous != uri) runCatching {
            context.contentResolver.releasePersistableUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    fun csvFiles(since: Long): List<Csv> = files(since, ".csv", HistoricalCsv.MAX_BYTES)
    fun fitFiles(since: Long): List<Csv> = files(since, ".fit", glucowatch.core.FitWorkoutReader.MAX_BYTES)
    private fun files(since: Long, extension: String, maxBytes: Int): List<Csv> {
        val tree = tree() ?: return emptyList()
        val files = mutableListOf<Csv>()
        val pending = java.util.ArrayDeque<Pair<String, Int>>()
        pending.add(DocumentsContract.getTreeDocumentId(tree) to 0)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 64) {
            val (id, depth) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1).orEmpty()
                if (cursor.getString(5) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    // Include raw provider categories, never feed our generated CSVs back into import.
                    if (depth < 3 && !name.equals("Exports", true) && pending.size < 64)
                        pending.add(cursor.getString(0) to depth + 1)
                    continue
                }
                val size = cursor.getLong(2)
                val modified = cursor.getLong(3)
                if (!name.endsWith(extension, true) || size !in 1..maxBytes.toLong() ||
                    modified < since / 1000 * 1000 || cursor.getInt(4) and DocumentsContract.Document.FLAG_PARTIAL != 0) continue
                files += Csv(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)), size, modified)
                if (files.size >= 5000) break
            }
            }
        }
        return files.sortedBy { it.modified }
    }
    /** ExternalStorageProvider indexes writes when their descriptor closes. Never trigger a scan
     * here: opening the document to read can itself scan a file that is still being written. */
    fun indexed(file: Csv): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            file.uri.authority != "com.android.externalstorage.documents") return false
        return runCatching {
            val media = MediaStore.getMediaUri(context, file.uri) ?: return false
            context.contentResolver.query(media, arrayOf(MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)?.use {
                it.moveToFirst() && !it.isNull(0) && it.getLong(0) == file.size &&
                    it.getLong(1) == file.modified / 1000 && it.getInt(2) == 0 && it.getString(3) != context.packageName
            } == true
        }.getOrDefault(false)
    }
    fun read(file: Csv): String = context.contentResolver.openInputStream(file.uri)?.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= HistoricalCsv.MAX_BYTES) { "CSV exceeds 20 MB." }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= HistoricalCsv.MAX_BYTES && bytes.size.toLong() == file.size) { "CSV is still downloading." }
        bytes.toString(Charsets.UTF_8)
    } ?: error("Could not read the shared export folder.")
}
