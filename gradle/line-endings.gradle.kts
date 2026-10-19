// Applied by app/, watchface/ and phone/. Assets, raw resources and Java resources go into the
// APK byte for byte, so a CRLF copy (a Windows checkout, or an editor that saves CRLF) changes the
// APK, and it no longer matches the build F-Droid makes on Linux. That happened to the 0.1.13 face
// and phone. Git stores these files as LF (.gitattributes), so rewriting the working copy to LF
// before anything reads it gives every machine the same bytes.
// Apply it as `apply { from(...) }`: with AGP 9.0.1, `apply(from = ...)` crashes lintVital.
val packagedVerbatim = fileTree("src") {
    include("*/assets/**", "*/res/raw/**", "*/resources/**")
}

val normalizeLineEndings = tasks.register("normalizeLineEndings") {
    description = "Rewrites CRLF to LF in the files the APK packages byte for byte."
    val files = packagedVerbatim
    val moduleDir = projectDir
    doLast {
        files.forEach { file ->
            // A NUL byte near the start means binary, the same test git uses.
            val head = file.inputStream().use { it.readNBytes(8000) }
            if (head.contains(0)) return@forEach
            // ISO-8859-1 maps every byte to one char, so all other bytes survive unchanged.
            val text = file.readText(Charsets.ISO_8859_1)
            if ("\r\n" !in text) return@forEach
            file.writeText(text.replace("\r\n", "\n"), Charsets.ISO_8859_1)
            logger.warn("Rewrote CRLF line endings to LF: ${file.relativeTo(moduleDir)}")
        }
    }
}

tasks.named { it == "preBuild" }.configureEach { dependsOn(normalizeLineEndings) }
// core/ is a JVM module; its notices must be normalized before Gradle copies them.
tasks.named { it == "processResources" }.configureEach { dependsOn(normalizeLineEndings) }
