package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.net.Uri
import glucowatch.core.HuggingFaceModel
import glucowatch.core.OnnxPredictor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A user-supplied ONNX model lives only in this phone app's private storage. It arrives either
 * from a file the user picked, or from a public Hugging Face repo the user named: the repo id is
 * enough, and its .onnx files are listed for the user to choose from. Nothing is downloaded
 * without that choice, no model is bundled, and the model is never uploaded or sent to the watch.
 */
class PhoneModelStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("model", Context.MODE_PRIVATE)
    private val file get() = app.filesDir.resolve("glucose-predictor.onnx")

    /** The .onnx files a repo offers, best first. A direct file link lists just that file. */
    data class Listing(val model: HuggingFaceModel, val files: List<String>)

    val origin: String? get() = prefs.getString("origin", null)?.takeIf { file.isFile }
    val available: Boolean get() = file.isFile

    fun predictor(): OnnxPredictor? {
        if (!file.isFile) return null
        synchronized(PhoneModelStore::class.java) {
            val version = file.lastModified() to file.length()
            cached?.takeIf { it.first == version }?.let { return it.second }
            val loaded = runCatching { OnnxPredictor.load(file.readBytes()) }.getOrNull() ?: return null
            cached = version to loaded
            return loaded
        }
    }

    suspend fun importFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = app.contentResolver.openInputStream(uri) ?: error("Could not open model file")
        stream.use { import(it, uri.lastPathSegment?.substringAfterLast('/') ?: "local .onnx file") }
    }

    /**
     * Asks Hugging Face which files [input]'s repo holds. A `resolve`/`blob` link to one .onnx
     * needs no request, so a repo whose listing is unavailable still works from a direct link.
     */
    suspend fun listHuggingFace(input: String): Listing = withContext(Dispatchers.IO) {
        val model = HuggingFaceModel.parse(input)
        model.file?.let { return@withContext Listing(model, listOf(it)) }
        val body = read(URL(model.listingUrl)) { it.reader().readText() }
        val files = HuggingFaceModel.onnxFiles(HuggingFaceModel.parseFileList(body))
        require(files.isNotEmpty()) { "${model.label} has no .onnx file. Check its files at ${model.pageUrl}" }
        Listing(model, files)
    }

    suspend fun importHuggingFace(model: HuggingFaceModel, file: String): String = withContext(Dispatchers.IO) {
        read(URL(model.downloadUrl(file))) { import(it, "${model.label} · $file") }
    }

    /** Follows Hugging Face's redirect to its file storage, over HTTPS only, and reads the body. */
    private fun <T> read(start: URL, body: (InputStream) -> T): T {
        var url = start
        var redirects = 0
        while (true) {
            require(url.protocol.equals("https", ignoreCase = true)) { "Model downloads must use HTTPS" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/octet-stream, application/json")
                setRequestProperty("User-Agent", "glucowatch")
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    require(++redirects <= 5) { "Too many Hugging Face redirects" }
                    url = URL(url, connection.getHeaderField("Location") ?: error("Missing redirect URL"))
                    continue
                }
                require(status != 401 && status != 403) { "That repo is private or gated; only public models can be used" }
                require(status != 404) { "Hugging Face has no such repo, revision or file" }
                require(status == 200) { "Hugging Face returned HTTP $status" }
                require(connection.contentLengthLong <= MAX_BYTES) { "Model is larger than 10 MB" }
                return connection.inputStream.use(body)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun import(stream: InputStream, source: String): String {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = stream.read(chunk)
            if (count < 0) break
            require(bytes.size() + count <= MAX_BYTES) { "Model is larger than 10 MB" }
            bytes.write(chunk, 0, count)
        }
        // Loading first means an unusable model never replaces one that works.
        OnnxPredictor.load(bytes.toByteArray())
        val temp = app.filesDir.resolve("glucose-predictor.tmp")
        try {
            temp.writeBytes(bytes.toByteArray())
            require(temp.renameTo(file)) { "Could not save model" }
            synchronized(PhoneModelStore::class.java) { cached = null }
            prefs.edit().putString("origin", source).apply()
        } finally {
            temp.delete()
        }
        return source
    }

    fun remove() {
        file.delete()
        synchronized(PhoneModelStore::class.java) { cached = null }
        prefs.edit().clear().apply()
    }

    private companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
        private var cached: Pair<Pair<Long, Long>, OnnxPredictor>? = null
    }
}
