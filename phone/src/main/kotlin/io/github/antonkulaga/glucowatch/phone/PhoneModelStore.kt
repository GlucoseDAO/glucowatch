package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.net.Uri
import glucowatch.core.HuggingFaceModel
import glucowatch.core.HuggingFaceAuth
import glucowatch.core.GlucosePredictor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Private user imports. Release uses the source interpreter; debug can run GlucoseDao bundles. */
class PhoneModelStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("model", Context.MODE_PRIVATE)
    private val folder get() = app.filesDir.resolve("forecast-model")
    private val file get() = folder.resolve("model.onnx").takeIf(File::isFile) ?: app.filesDir.resolve("glucose-predictor.onnx")
    data class Listing(val model: HuggingFaceModel, val files: List<String>)
    val origin: String? get() = prefs.getString("origin", null)?.takeIf { available }
    val available: Boolean get() = file.isFile

    fun predictor(): GlucosePredictor? = synchronized(PhoneModelStore::class.java) {
        if (!available) return null
        val version = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        cached?.takeIf { it.first == version }?.let { return it.second }
        val loaded = runCatching { PhoneModelRuntime.load(file, folder.resolve("onnx_meta.json"), folder.resolve("scalers.json")) }.getOrNull() ?: return null
        (cached?.second as? AutoCloseable)?.close()
        cached = version to loaded
        loaded
    }

    suspend fun importFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = app.contentResolver.openInputStream(uri) ?: error("Could not open model file")
        stream.use { install(readBounded(it), null, null, uri.lastPathSegment?.substringAfterLast('/') ?: "local .onnx file") }
    }

    suspend fun listHuggingFace(input: String, token: String = ""): Listing = withContext(Dispatchers.IO) {
        val model = HuggingFaceModel.parse(input)
        model.file?.let { return@withContext Listing(model, listOf(it)) }
        val body = read(URL(model.listingUrl), token).toString(Charsets.UTF_8)
        val files = model.filesInDirectory(HuggingFaceModel.parseFileList(body))
        require(files.isNotEmpty()) { "${model.label} has no .onnx file. Check its files at ${model.pageUrl}" }
        Listing(model, files)
    }

    suspend fun searchHuggingFace(query: String, token: String = ""): List<HuggingFaceModel> = withContext(Dispatchers.IO) {
        HuggingFaceModel.parseSearchResults(read(URL(HuggingFaceModel.searchUrl(query)), token).toString(Charsets.UTF_8))
    }

    suspend fun importHuggingFace(model: HuggingFaceModel, file: String, token: String = ""): String = withContext(Dispatchers.IO) {
        // Pin model and preprocessing files to one commit, even if main moves during the download.
        val info = read(URL(model.listingUrl), token).toString(Charsets.UTF_8)
        val revision = JSONObject(info).optString("sha").takeIf { it.isNotEmpty() }
        require(revision != null && revision.matches(Regex("[a-fA-F0-9]{40}"))) { "Hugging Face returned no model revision" }
        val pinned = model.copy(revision = revision)
        val artifacts = HuggingFaceModel.parseFileList(info)
        val directory = file.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        fun sidecar(name: String): ByteArray? = if (PhoneModelRuntime.supportsBundles && directory + name in artifacts)
            read(URL(pinned.artifactUrl(directory + name)), token) else null
        val metadata = sidecar("onnx_meta.json")
        val scalers = sidecar("scalers.json")
        install(read(URL(pinned.downloadUrl(file)), token), metadata, scalers, "${model.repo} · $file · ${revision.take(8)}")
    }

    /** A private bundle supplied by screenshot automation, using the same validation/install path. */
    fun importDebugBundle(): String {
        require(PhoneModelRuntime.supportsBundles) { "Development bundle imports are disabled" }
        val input = app.filesDir.resolve("debug-model")
        return install(input.resolve("model.onnx").inputStream().use(::readBounded),
            input.resolve("onnx_meta.json").takeIf(File::isFile)?.readBytes(),
            input.resolve("scalers.json").takeIf(File::isFile)?.readBytes(), "private emulator bundle")
    }

    private fun read(start: URL, token: String): ByteArray {
        var url = start
        var redirects = 0
        while (true) {
            require(url.protocol.equals("https", true)) { "Model downloads must use HTTPS" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 90_000
                setRequestProperty("Accept", "application/octet-stream, application/json")
                setRequestProperty("User-Agent", "glucowatch")
                HuggingFaceAuth.authorization(url.toString(), token)?.let { setRequestProperty("Authorization", it) }
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    require(++redirects <= 5) { "Too many Hugging Face redirects" }
                    url = URL(url, connection.getHeaderField("Location") ?: error("Missing redirect URL"))
                    continue
                }
                require(status != 401 && status != 403) { "Hugging Face denied access. Check your token and model access." }
                require(status != 404) { "Hugging Face has no such repo, revision or file" }
                require(status == 200) { "Hugging Face returned HTTP $status" }
                require(connection.contentLengthLong <= PhoneModelRuntime.MAX_BYTES) { "Model exceeds the import size limit" }
                return connection.inputStream.use(::readBounded)
            } finally { connection.disconnect() }
        }
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = stream.read(chunk)
            if (count < 0) break
            require(bytes.size() + count <= PhoneModelRuntime.MAX_BYTES) { "Model exceeds the import size limit" }
            bytes.write(chunk, 0, count)
        }
        return bytes.toByteArray()
    }

    private fun install(bytes: ByteArray, metadata: ByteArray?, scalers: ByteArray?, source: String): String = synchronized(PhoneModelStore::class.java) {
        val temp = File.createTempFile("forecast-", "", app.filesDir).apply { delete(); mkdir() }
        val backup = app.filesDir.resolve("forecast-backup")
        try {
            temp.resolve("model.onnx").writeBytes(bytes)
            metadata?.let { require(it.size <= 1024 * 1024); temp.resolve("onnx_meta.json").writeBytes(it) }
            scalers?.let { require(it.size <= 1024 * 1024); temp.resolve("scalers.json").writeBytes(it) }
            val checked = PhoneModelRuntime.load(temp.resolve("model.onnx"), temp.resolve("onnx_meta.json"), temp.resolve("scalers.json"))
            (checked as? AutoCloseable)?.close()
            backup.deleteRecursively()
            if (folder.exists()) require(folder.renameTo(backup)) { "Could not preserve previous model" }
            if (!temp.renameTo(folder)) { backup.renameTo(folder); error("Could not save model") }
            (cached?.second as? AutoCloseable)?.close(); cached = null
            app.filesDir.resolve("glucose-predictor.onnx").delete()
            backup.deleteRecursively()
            prefs.edit().putString("origin", source).apply()
        } finally { temp.deleteRecursively() }
        source
    }

    fun remove() = synchronized(PhoneModelStore::class.java) {
        (cached?.second as? AutoCloseable)?.close(); cached = null
        folder.deleteRecursively(); app.filesDir.resolve("glucose-predictor.onnx").delete()
        prefs.edit().clear().apply()
    }

    private companion object { private var cached: Pair<String, GlucosePredictor>? = null }
}
