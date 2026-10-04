package glucowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HuggingFaceModelTest {
    @Test fun `short names search while repo ids and URLs resolve directly`() {
        assertEquals(true, HuggingFaceModel.isSearch("  citras  "))
        assertEquals(false, HuggingFaceModel.isSearch("owner/model"))
        assertEquals(false, HuggingFaceModel.isSearch("owner/model@v1"))
        assertEquals(false, HuggingFaceModel.isSearch("https://huggingface.co/owner/model"))
        assertEquals(false, HuggingFaceModel.isSearch(""))
        assertEquals("https://huggingface.co/api/models?search=citras+%26+glucose&limit=20",
            HuggingFaceModel.searchUrl("citras & glucose"))
        assertFailsWith<IllegalArgumentException> { HuggingFaceModel.searchUrl(" ") }
    }

    @Test fun `search result ids cannot introduce other hosts or path tricks`() {
        val response = """[
            {"id":"GlucoseDao/CITRAS"}, {"id":"GlucoseDao/CITRAS"},
            {"id":"other/CITRAS"}, {"id":"https://example.com/model"},
            {"id":"owner/../model"}, {"id":"owner/model@revision"},
            {"id":"not a repo"}, {"other":"owner/model"}, null
        ]"""
        assertEquals(listOf(HuggingFaceModel("GlucoseDao/CITRAS"), HuggingFaceModel("other/CITRAS")),
            HuggingFaceModel.parseSearchResults(response))
        assertEquals(emptyList(), HuggingFaceModel.parseSearchResults("[]"))
        assertFailsWith<IllegalArgumentException> { HuggingFaceModel.parseSearchResults("{}") }
    }

    @Test fun `tree directory limits the file chooser`() {
        val model = HuggingFaceModel.parse("https://huggingface.co/owner/model/tree/main/onnx")
        assertEquals("onnx", model.directory)
        assertEquals(listOf("onnx/model.onnx"), model.filesInDirectory(listOf("model.onnx", "onnx/model.onnx", "onnx-other/model.onnx")))
        assertFailsWith<IllegalArgumentException> { HuggingFaceModel.parse("https://huggingface.co/owner/model/tree/main/../other") }
    }

    @Test fun `token is scoped to the Hub HTTPS origin and never a redirect host`() {
        val token = "hf_test"
        assertEquals("Bearer $token", HuggingFaceAuth.authorization("https://huggingface.co/owner/model", token))
        assertEquals(null, HuggingFaceAuth.authorization("https://cdn-lfs.huggingface.co/model", token))
        assertEquals(null, HuggingFaceAuth.authorization("https://huggingface.co.evil.example/model", token))
        assertEquals(null, HuggingFaceAuth.authorization("http://huggingface.co/model", token))
        assertEquals(null, HuggingFaceAuth.authorization("https://huggingface.co:8443/model", token))
        assertEquals(null, HuggingFaceAuth.authorization("https://user@huggingface.co/model", token))
        assertEquals(null, HuggingFaceAuth.authorization("https://huggingface.co/model", ""))
        assertFailsWith<IllegalArgumentException> { HuggingFaceAuth.authorization("https://huggingface.co", "hf_test\r\nHeader: value") }
    }

    @Test fun `accepts a bare repo id and points at its file list`() {
        val model = HuggingFaceModel.parse("  glucosedao/gluformer  ")
        assertEquals("glucosedao/gluformer", model.repo)
        assertEquals("main", model.revision)
        assertEquals(null, model.file)
        assertEquals("https://huggingface.co/api/models/glucosedao/gluformer/revision/main", model.listingUrl)
        assertEquals("glucosedao/gluformer", model.label)
    }

    @Test fun `accepts a repo id pinned to a revision`() {
        val model = HuggingFaceModel.parse("owner/model@v1.2")
        assertEquals("v1.2", model.revision)
        assertEquals("owner/model@v1.2", model.label)
        assertEquals("https://huggingface.co/owner/model/resolve/v1.2/model.onnx", model.downloadUrl("model.onnx"))
    }

    @Test fun `accepts repo, tree, blob and resolve links`() {
        assertEquals(HuggingFaceModel("owner/model"), HuggingFaceModel.parse("https://huggingface.co/owner/model"))
        assertEquals(HuggingFaceModel("owner/model"), HuggingFaceModel.parse("huggingface.co/owner/model/"))
        assertEquals(HuggingFaceModel("owner/model", "dev"), HuggingFaceModel.parse("https://huggingface.co/owner/model/tree/dev"))
        assertEquals(
            HuggingFaceModel("owner/model", "main", "onnx/model.onnx"),
            HuggingFaceModel.parse("https://huggingface.co/owner/model/blob/main/onnx/model.onnx"),
        )
        assertEquals(
            HuggingFaceModel("owner/model", "abc123", "model.onnx"),
            HuggingFaceModel.parse("https://huggingface.co/owner/model/resolve/abc123/model.onnx"),
        )
    }

    @Test fun `builds a download URL with each path segment encoded`() {
        val model = HuggingFaceModel("owner/model", "feature/next")
        assertEquals(
            "https://huggingface.co/owner/model/resolve/feature/next/sub%20dir/model.onnx",
            model.downloadUrl("sub dir/model.onnx"),
        )
    }

    @Test fun `rejects other hosts, other schemes and path tricks`() {
        listOf(
            "https://example.com/owner/model",
            "http://huggingface.co/owner/model",
            "https://huggingface.co/owner",
            "https://huggingface.co/owner/model/resolve/main/weights.bin",
            "https://huggingface.co/owner/model/raw/main/model.onnx",
            "owner/model@../../etc",
            "not a repo",
            "",
        ).forEach { input ->
            assertFailsWith<IllegalArgumentException>("accepted '$input'") { HuggingFaceModel.parse(input) }
        }
    }

    @Test fun `refuses to download anything that is not an onnx file`() {
        assertFailsWith<IllegalArgumentException> { HuggingFaceModel("owner/model").downloadUrl("model.bin") }
    }

    @Test fun `reads the file list and ranks the onnx files`() {
        val json = """
            {"id":"owner/model","siblings":[
              {"rfilename":"README.md"},
              {"rfilename":"onnx/decoder.onnx"},
              {"rfilename":"model.onnx"},
              {"rfilename":"model.onnx_data"},
              {"rfilename":"quantized_model.onnx"}
            ]}
        """.trimIndent()
        val files = HuggingFaceModel.parseFileList(json)
        assertEquals(5, files.size)
        assertEquals(listOf("model.onnx", "quantized_model.onnx", "onnx/decoder.onnx"), HuggingFaceModel.onnxFiles(files))
    }

    @Test fun `treats a repo with no listed files as empty rather than failing`() {
        assertEquals(emptyList(), HuggingFaceModel.parseFileList("""{"id":"owner/model"}"""))
        assertEquals(emptyList(), HuggingFaceModel.parseFileList(""))
    }
}
