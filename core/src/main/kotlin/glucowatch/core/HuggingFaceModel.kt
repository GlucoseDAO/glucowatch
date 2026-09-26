package glucowatch.core

import java.net.URI
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Hugging Face model repository, resolved from whatever the user typed: a repo id
 * (`owner/name`, optionally `owner/name@revision`), a repo page, or a `blob`/`resolve` link to
 * one file. A repo id alone is enough: [listingUrl] names the repo's files, [onnxFiles] keeps the
 * ONNX ones, and [downloadUrl] builds the download. Nothing here fetches; the phone app does that
 * only when the user asks, and only over HTTPS to huggingface.co.
 */
data class HuggingFaceModel(val repo: String, val revision: String = "main", val file: String? = null) {
    /** The repo's file list, pinned to [revision]. */
    val listingUrl: String get() = "$HOST/api/models/$repo/revision/${encodePath(revision)}"

    /** The repo's page, for the "not an ONNX repo" message and the status line. */
    val pageUrl: String get() = "$HOST/$repo/tree/${encodePath(revision)}"

    fun downloadUrl(name: String = requireNotNull(file) { "No ONNX file chosen" }): String {
        require(isOnnx(name)) { "Only .onnx files can be imported" }
        return "$HOST/$repo/resolve/${encodePath(revision)}/${encodePath(name)}"
    }

    /** `owner/name` or `owner/name@revision`, for the status line under the field. */
    val label: String get() = if (revision == DEFAULT_REVISION) repo else "$repo@$revision"

    companion object {
        const val HOST = "https://huggingface.co"
        const val DEFAULT_REVISION = "main"

        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
        private val REVISION = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")

        fun isOnnx(name: String) = name.endsWith(".onnx", ignoreCase = true)

        /**
         * Reads `owner/name`, `owner/name@revision`, or any huggingface.co link to a repo, a tree,
         * or a single file. Anything else is rejected with a message the user can act on.
         */
        fun parse(input: String): HuggingFaceModel {
            val text = input.trim()
            require(text.isNotEmpty()) { "Enter a Hugging Face model repo, such as owner/model" }
            val looksLikeUrl = "://" in text || text.startsWith("huggingface.co/", ignoreCase = true)
            return if (looksLikeUrl) fromUrl(text) else fromRepoId(text)
        }

        private fun fromUrl(text: String): HuggingFaceModel {
            val uri = runCatching { URI(if ("://" in text) text else "https://$text") }
                .getOrElse { throw IllegalArgumentException("That is not a valid link") }
            require(uri.scheme.equals("https", ignoreCase = true)) { "Model downloads must use https://" }
            require(uri.host != null && uri.host.equals("huggingface.co", ignoreCase = true)) {
                "Only huggingface.co links are supported"
            }
            val segments = (uri.path ?: "").trim('/').split('/').filter(String::isNotEmpty)
            require(segments.size >= 2) { "Use a link like https://huggingface.co/owner/model" }
            val repo = repoId(segments[0], segments[1])
            if (segments.size == 2) return HuggingFaceModel(repo)
            val kind = segments[2]
            require(kind in setOf("resolve", "blob", "tree")) {
                "Use the repo page, or a link to one .onnx file (…/resolve/main/model.onnx)"
            }
            require(segments.size >= 4) { "That link has no revision; use …/$kind/main/…" }
            val revision = revision(segments[3])
            val path = segments.drop(4).joinToString("/")
            if (kind == "tree" || path.isEmpty()) return HuggingFaceModel(repo, revision)
            require(isOnnx(path)) { "That link is not an .onnx file" }
            return HuggingFaceModel(repo, revision, filePath(path))
        }

        private fun fromRepoId(text: String): HuggingFaceModel {
            val at = text.indexOf('@')
            val id = if (at >= 0) text.substring(0, at) else text
            val revision = if (at >= 0) revision(text.substring(at + 1)) else DEFAULT_REVISION
            val parts = id.trim('/').split('/')
            require(parts.size == 2) { "Use owner/model, or paste the repo's huggingface.co link" }
            return HuggingFaceModel(repoId(parts[0], parts[1]), revision)
        }

        private fun repoId(owner: String, name: String): String {
            require(NAME.matches(owner) && NAME.matches(name)) { "'$owner/$name' is not a Hugging Face repo id" }
            return "$owner/$name"
        }

        private fun revision(value: String): String {
            require(REVISION.matches(value) && ".." !in value) { "'$value' is not a branch, tag or commit" }
            return value
        }

        private fun filePath(path: String): String {
            require(".." !in path && !path.startsWith("/")) { "That file path is not allowed" }
            return path
        }

        /** Percent-encodes each segment but keeps the `/` between them. */
        private fun encodePath(path: String) = path.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

        /**
         * The `siblings[].rfilename` names from the model info endpoint. A repo with no files
         * (gated, or still empty) yields an empty list rather than an error, so the caller can
         * say which repo it was.
         */
        fun parseFileList(json: String): List<String> {
            val root = runCatching { Json.parseToJsonElement(json.ifBlank { "{}" }).jsonObject }
                .getOrElse { throw IllegalArgumentException("Hugging Face returned an unexpected answer") }
            val siblings = root["siblings"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
            return siblings.mapNotNull { entry ->
                runCatching { entry.jsonObject["rfilename"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            }.filter { it.isNotBlank() && ".." !in it && !it.startsWith("/") }
        }

        /**
         * The importable ONNX files, best first: `model.onnx` and other top-level files before
         * ones nested in folders, then shortest name. Files ONNX keeps its weights beside
         * (`.onnx_data`, `.onnx.data`) are not importable on their own and are left out.
         */
        fun onnxFiles(files: List<String>): List<String> = files
            .filter { isOnnx(it) }
            .sortedWith(compareBy({ it.count { c -> c == '/' } }, { if (it == "model.onnx") 0 else 1 }, { it.length }, { it }))
    }
}
