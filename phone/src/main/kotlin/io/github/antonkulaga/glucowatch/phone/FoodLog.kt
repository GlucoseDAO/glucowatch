package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * One entry the user logged on this phone: a meal (carbs, optionally a photo), a dose of insulin,
 * or both. Dexcom Share carries glucose only, so for a Share user this is the only insulin data.
 */
data class FoodEntry(
    val timeMillis: Long,
    val carbs: Double,
    val photo: String?,
    val note: String = "",
    val insulin: Double = 0.0,
) {
    val isMeal get() = carbs > 0 || photo != null
}

/**
 * Meals and insulin logged on the phone. Photos, doses and notes are health data: they stay in this
 * app's private storage, are never relayed to the watch and never leave the phone. Entries older than
 * [KEEP_DAYS] days are dropped, with their photos, so the log does not grow without end.
 */
class FoodLog(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("food", Context.MODE_PRIVATE)
    private val photos get() = app.filesDir.resolve("food").apply { mkdirs() }

    fun all(): List<FoodEntry> = decode(prefs.getString(ENTRIES, "").orEmpty())
        .filter { it.timeMillis >= System.currentTimeMillis() - KEEP_DAYS * 86_400_000L }
        .sortedBy { it.timeMillis }

    fun since(fromMillis: Long): List<FoodEntry> = all().filter { it.timeMillis >= fromMillis }

    /** The file the camera writes into; [add] keeps it only if the user finishes the entry. */
    fun newPhotoFile(): File = photos.resolve("${System.currentTimeMillis()}.jpg")

    fun photoFile(name: String): File? = photos.resolve(name).takeIf { it.isFile && it.parentFile == photos }

    fun add(carbs: Double, note: String, photo: File?, insulin: Double = 0.0): FoodEntry {
        val shrunk = photo?.takeIf { it.isFile }?.let(::shrink)
        val entry = FoodEntry(System.currentTimeMillis(), carbs, shrunk?.name, note.trim(), insulin)
        write(all() + entry)
        return entry
    }

    /** [write] deletes any photo no kept entry names, so the removed meal's photo goes with it. */
    fun remove(timeMillis: Long) = write(all().filterNot { it.timeMillis == timeMillis })

    /** Thumbnail-sized JPEG in place of the camera's full-resolution file, to keep storage small. */
    private fun shrink(source: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        if (largest <= 0) return null.also { source.delete() }
        var sample = 1
        while (largest / sample > 2 * MAX_PIXELS) sample *= 2
        val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null.also { source.delete() }
        val scale = MAX_PIXELS.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale >= 1f) bitmap else
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        return runCatching {
            source.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            source
        }.getOrElse { source.delete(); null }
    }

    private fun write(entries: List<FoodEntry>) {
        val kept = entries.filter { it.timeMillis >= System.currentTimeMillis() - KEEP_DAYS * 86_400_000L }
        val names = kept.mapNotNull(FoodEntry::photo).toSet()
        photos.listFiles()?.forEach { if (it.name !in names) it.delete() }
        prefs.edit().putString(ENTRIES, encode(kept)).apply()
    }

    /**
     * `time,carbs,photo,note,insulin` per row, each field percent-encoded, rows joined with `;`.
     * Rows written before insulin was added have four fields and read back with no insulin.
     */
    private fun encode(entries: List<FoodEntry>) = entries.joinToString(";") {
        listOf(it.timeMillis.toString(), it.carbs.toString(), it.photo.orEmpty(), it.note, it.insulin.toString())
            .joinToString(",") { field -> Uri.encode(field) }
    }

    private fun decode(text: String): List<FoodEntry> = text.split(';').mapNotNull { row ->
        val parts = row.split(',').map(Uri::decode)
        if (parts.size != 4 && parts.size != 5) return@mapNotNull null
        FoodEntry(
            timeMillis = parts[0].toLongOrNull() ?: return@mapNotNull null,
            carbs = parts[1].toDoubleOrNull() ?: return@mapNotNull null,
            photo = parts[2].takeIf(String::isNotEmpty),
            note = parts[3],
            insulin = parts.getOrNull(4)?.toDoubleOrNull() ?: 0.0,
        )
    }

    private companion object {
        const val ENTRIES = "entries"
        const val KEEP_DAYS = 30
        const val MAX_PIXELS = 640
    }
}
