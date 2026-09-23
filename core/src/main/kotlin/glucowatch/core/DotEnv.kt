package glucowatch.core

import java.io.File

/**
 * Minimal `.env` reader for developer defaults (see `.env.example` in the project root).
 * Rules: `KEY=value` per line, `#` comments, optional `export ` prefix, optional '…' or "…"
 * quotes (use them when a value contains spaces or ` #`). Real environment variables win.
 * app/build.gradle.kts parses the file with the same rules for debug builds.
 */
object DotEnv {
    const val FILE_NAME = ".env"

    fun parse(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { raw ->
            val line = raw.trim().removePrefix("export ").trim()
            if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@mapNotNull null
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            key to unquote(value)
        }.toMap()

    /** Nearest `.env` from [start] upwards, merged with (and overridden by) the process environment. */
    fun load(start: File = File("").absoluteFile, environment: Map<String, String> = System.getenv()): Map<String, String> {
        val file = generateSequence(start) { it.parentFile }.map { File(it, FILE_NAME) }.firstOrNull { it.isFile }
        return (file?.let { parse(it.readText()) } ?: emptyMap()) + environment
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 && value.first() == value.last() && value.first() in "\"'") {
            value.substring(1, value.length - 1)
        } else {
            value.replace(Regex("""\s+#.*$"""), "")
        }
}
